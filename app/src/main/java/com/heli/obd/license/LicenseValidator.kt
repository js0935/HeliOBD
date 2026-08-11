/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.license

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * OBD 車機 App 授權金鑰驗證器。
 *
 * 與 PC 端工具 `LicenseKeyGenUI` 的「Android 車機 App」模式互通：
 *
 * 金鑰格式：`OBD-{payloadB64url}.{signatureB64url}`
 *   - payload（JSON，簽章對象為其 UTF-8 原始位元組）：
 *     `{"mid":"<32 hex 設備碼>","feat":<功能遮罩 int>,"exp":"<yyyy-MM-dd>"|null}`
 *   - 簽章：SHA256withRSA / PKCS#1 v1.5
 *   - Base64url：標準 Base64 去掉 `=`，`+` → `-`，`/` → `_`
 *
 * 本類別為純 JVM 邏輯（無 Android 依賴），可在 JVM 上直接單元測試。
 */
object LicenseValidator {

    const val PREFIX = "OBD-"

    // ===== 功能位元定義（務必與 LicenseKeyGenUI 的 CheckBox 順序一致） =====
    const val FEAT_ENGINE_SOUND = 0x01 // bit0：引擎聲浪
    const val FEAT_AI_DIAGNOSIS = 0x02 // bit1：AI 診斷
    const val FEAT_TRIP_REVIEW = 0x04  // bit2：行程回顧
    const val FEAT_DATA_COMPARE = 0x08 // bit3：數據對比
    const val FEAT_MULTI_CAR = 0x10    // bit4：多車管理
    const val FEAT_NO_ADS = 0x20       // bit5：移除廣告

    /** 所有已知功能位元遮罩（0x01|0x02|0x04|0x08|0x10|0x20；避免把未知位元誤當成已授權） */
    const val FEAT_ALL_KNOWN = 0x3F

    /** 解析後的金鑰內容 */
    data class Payload(
        val machineId: String,     // 32 碼十六進位 Android 設備碼
        val featureMask: Int,      // 功能位元遮罩
        val expiryDate: String?,   // "yyyy-MM-dd"，null = 永久
    )

    sealed class VerifyResult {
        data object Ok : VerifyResult()
        data class Error(val reason: String) : VerifyResult()
    }

    // ===== 公鑰（由 LicenseKeyGenUI 產生後嵌入，SubjectPublicKeyInfo DER / Base64） =====
    var publicKeyB64: String = ""
        set(value) {
            field = value
            cachedPublicKey = null
        }

    private var cachedPublicKey: PublicKey? = null

    private val publicKey: PublicKey
        get() {
            cachedPublicKey?.let { return it }
            require(publicKeyB64.isNotBlank()) {
                "尚未設定公鑰：請先在 LicenseKeyGenUI 產生金鑰對，再把 base64 公鑰貼入 App.kt"
            }
            val der = Base64.getDecoder().decode(publicKeyB64)
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
            cachedPublicKey = key
            return key
        }

    /**
     * 驗證整把金鑰（格式 + 簽章）。不回傳 payload。
     * @throws IllegalArgumentException 金鑰格式錯誤
     */
    fun verifySignature(licenseKey: String): VerifyResult = try {
        val (payloadB64, sigB64) = split(licenseKey)
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(publicKey)
            update(decodeUrl(payloadB64))
        }
        if (verifier.verify(decodeUrl(sigB64))) VerifyResult.Ok
        else VerifyResult.Error("簽章驗證失敗（金鑰非由你的私鑰簽發）")
    } catch (e: IllegalArgumentException) {
        VerifyResult.Error(e.message ?: "金鑰格式錯誤")
    } catch (e: Exception) {
        VerifyResult.Error("簽章驗證例外：${e.message}")
    }

    /** 解析 payload（不驗證簽章）。@throws IllegalArgumentException 格式錯誤 */
    fun parsePayload(licenseKey: String): Payload {
        val (payloadB64, _) = split(licenseKey)
        val json = decodeUrl(payloadB64).toString(Charsets.UTF_8)

        fun field(name: String): String? {
            val re = Regex("\"$name\"\\s*:\\s*(?:null|\"([^\"]*)\"|(-?\\d+))")
            return re.find(json)?.let { m ->
                when {
                    m.groupValues[1].isNotEmpty() -> m.groupValues[1]
                    m.groupValues[2].isNotEmpty() -> m.groupValues[2]
                    else -> null
                }
            }
        }

        val mid = field("mid") ?: throw IllegalArgumentException("payload 缺少 mid")
        val feat = field("feat")?.toIntOrNull() ?: 0
        val exp = field("exp") // null = 永久

        return Payload(machineId = mid, featureMask = feat, expiryDate = exp)
    }

    /** 檢查到期（exp 為 null = 永久）。本地時間可能被竄改，見 LicenseStore 的時間倒退偵測 */
    fun isExpired(payload: Payload, today: java.time.LocalDate): Boolean {
        val exp = payload.expiryDate ?: return false
        return try {
            java.time.LocalDate.parse(exp) < today
        } catch (_: Exception) {
            true // 無法解析的到期日一律視為無效
        }
    }

    private fun split(licenseKey: String): Pair<String, String> {
        require(licenseKey.startsWith(PREFIX)) { "金鑰必須以 $PREFIX 開頭" }
        val body = licenseKey.removePrefix(PREFIX)
        val dot = body.indexOf('.')
        require(dot > 0 && dot < body.lastIndex) { "金鑰格式錯誤：缺少 . 分隔符" }
        return body.substring(0, dot) to body.substring(dot + 1)
    }

    private fun decodeUrl(s: String): ByteArray {
        require(s.length % 4 != 1) { "Base64url 長度錯誤" }
        val padded = s.replace('-', '+').replace('_', '/')
            .let { if (it.length % 4 == 0) it else it + "=".repeat(4 - it.length % 4) }
        return Base64.getDecoder().decode(padded)
    }
}

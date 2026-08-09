package com.heli.obd.license

import android.content.Context
import java.time.LocalDate

/**
 * OBD 車機 App 授權管理員 —— App 內唯一授權入口。
 *
 * 建議以單例（object 或 DI 容器）建立後由各功能模組共用：
 *
 * ```kotlin
 * val license = LicenseManager(context, "....LicenseKeyGenUI 產生的公鑰 base64....")
 *
 * // 功能閘門（引擎聲浪、AI 診斷等付費功能呼叫此處）：
 * if (license.isFeatureEnabled(LicenseValidator.FEAT_ENGINE_SOUND)) { ... }
 * ```
 */
class LicenseManager(context: Context, publicKeyB64: String) {

    private val store = LicenseStore(context)

    init {
        LicenseValidator.publicKeyB64 = publicKeyB64
    }

    private val appContext: Context = context.applicationContext

    /** 目前裝置設備碼（顯示於 UI 供使用者回報給授權方） */
    fun deviceId(): String = DeviceId.get(appContext)

    // ===== 授權狀態 =====

    enum class Status {
        NOT_ACTIVATED,   // 未授權
        VALID,           // 有效
        EXPIRED,         // 已過期
        DEVICE_MISMATCH, // 金鑰綁定其他設備
        INVALID_SIGNATURE, // 簽章無效（非本公司私鑰簽發）
        TIME_ROLLBACK,   // 偵測到系統時間倒退（疑似竄改）
    }

    /** 啟動時呼叫一次：載入已儲存金鑰並驗證，結果可用於初始化 UI 與背景檢查 */
    fun status(): Status {
        val key = store.load() ?: return Status.NOT_ACTIVATED

        val today = LocalDate.now()

        // 時間倒退偵測（在驗證簽章前先擋下改時間繞過）
        val last = store.lastCheck()
        if (last != null && today < last) {
            store.clear()
            return Status.TIME_ROLLBACK
        }
        store.recordCheck(today)

        return validate(key)
    }

    /**
     * 輸入金鑰啟動授權。成功回傳 VALID 並儲存；失敗回傳對應狀態（不儲存）。
     */
    fun activate(licenseKey: String): Status {
        val key = licenseKey.trim()
        val result = validate(key)
        if (result == Status.VALID) {
            store.recordCheck(LocalDate.now())
            store.save(key)
        }
        return result
    }

    /** 移除授權 */
    fun deactivate() = store.clear()

    /** 功能閘門：查詢某功能是否已授權（未授權/過期/竄改一律 false） */
    fun isFeatureEnabled(featureBit: Int): Boolean {
        if (status() != Status.VALID) return false
        val mask = currentFeatureMask() ?: return false
        return mask and featureBit != 0
    }

    /** 目前授權的功能遮罩（未授權回傳 null） */
    fun currentFeatureMask(): Int? {
        val key = store.load() ?: return null
        return try {
            LicenseValidator.parsePayload(key).featureMask
        } catch (_: Exception) {
            null
        }
    }

    // ===== 內部 =====

    private fun validate(key: String): Status {
        // 1. 格式 + 簽章
        when (val sig = LicenseValidator.verifySignature(key)) {
            is LicenseValidator.VerifyResult.Error -> return Status.INVALID_SIGNATURE
            LicenseValidator.VerifyResult.Ok -> Unit
        }

        // 2. payload 解析
        val payload = try {
            LicenseValidator.parsePayload(key)
        } catch (_: Exception) {
            return Status.INVALID_SIGNATURE
        }

        // 3. 設備綁定
        if (!payload.machineId.equals(deviceId(), ignoreCase = true)) {
            return Status.DEVICE_MISMATCH
        }

        // 4. 到期
        if (LicenseValidator.isExpired(payload, LocalDate.now())) {
            return Status.EXPIRED
        }

        return Status.VALID
    }
}

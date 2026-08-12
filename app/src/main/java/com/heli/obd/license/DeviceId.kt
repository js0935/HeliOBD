/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.license

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * Android 設備碼產生器。
 *
 * 設備碼 = SHA-256(ANDROID_ID) 前 32 hex（小寫）。
 * 與 PC 端 LicenseKeyGenUI 的「32 碼十六進位」格式一致（方便共用輸入驗證邏輯）。
 *
 * 注意：
 * - ANDROID_ID 不需要任何權限，App 重裝後保持不變（同簽名、同裝置、非工作資料夾）
 * - 不採用 IMEI/裝置序號：需要危險權限，Android 10+ 已禁止一般 App 讀取
 */
object DeviceId {

    /** 取得目前裝置的設備碼（32 hex）。 */
    @SuppressLint("HardwareIds")
    fun get(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: "unknown"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** 驗證設備碼格式是否為 32 碼十六進位（供 UI 輸入檢查與金鑰解析檢查共用）。 */
    fun isValid(machineId: String): Boolean =
        machineId.length == 32 && machineId.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}

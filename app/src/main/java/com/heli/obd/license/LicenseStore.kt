package com.heli.obd.license

import android.content.Context
import java.time.LocalDate

/**
 * 授權狀態持久化（SharedPreferences）。
 *
 * 防竄改措施：
 * 1. 授權金鑰存於 SharedPreferences（私有，僅本 App 可讀；App 私有目錄受沙箱保護）
 * 2. 時間倒退偵測：離線 App 最常見的繞過手法是「把系統日期調回到期日前」。
 *    本類記錄「上次成功驗證的日期」，若偵測到系統日期倒退 → 判定為竄改，由
 *    LicenseManager 回報 TIME_ROLLBACK。此機制僅能延緩、無法完全阻止（換機重裝即失效），
 *    進階防護請搭配伺服器端校時或 Play Integrity。
 */
class LicenseStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 儲存已驗證通過的金鑰 */
    fun save(licenseKey: String) {
        prefs.edit().putString(KEY_LICENSE, licenseKey).apply()
    }

    /** 讀取已儲存的金鑰；未授權回傳 null */
    fun load(): String? = prefs.getString(KEY_LICENSE, null)

    /** 清除授權（例如使用者主動移除） */
    fun clear() {
        prefs.edit().remove(KEY_LICENSE).remove(KEY_LAST_CHECK).apply()
    }

    /** 記錄本次驗證日期（用於時間倒退偵測） */
    fun recordCheck(date: LocalDate) {
        prefs.edit().putString(KEY_LAST_CHECK, date.toString()).apply()
    }

    /** 上次成功驗證的日期；尚未有記錄回傳 null */
    fun lastCheck(): LocalDate? = prefs.getString(KEY_LAST_CHECK, null)?.let {
        try { LocalDate.parse(it) } catch (_: Exception) { null }
    }

    companion object {
        private const val PREFS_NAME = "obd_license_store"
        private const val KEY_LICENSE = "license_key"
        private const val KEY_LAST_CHECK = "last_check_date"
    }
}

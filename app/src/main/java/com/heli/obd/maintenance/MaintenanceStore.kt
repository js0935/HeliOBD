package com.heli.obd.maintenance

import android.content.Context

/**
 * 保養提醒資料層：SharedPreferences 持久化。
 */
class MaintenanceStore(context: Context) {

    private val prefs = context.getSharedPreferences("maintenance_prefs", Context.MODE_PRIVATE)

    var lastServiceKm: Int
        get() = prefs.getInt(KEY_LAST_KM, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_KM, value).apply()

    var lastServiceDateMs: Long
        get() = prefs.getLong(KEY_LAST_DATE, System.currentTimeMillis())
        set(value) = prefs.edit().putLong(KEY_LAST_DATE, value).apply()

    var serviceIntervalKm: Int
        get() = prefs.getInt(KEY_INTERVAL_KM, 3000)
        set(value) = prefs.edit().putInt(KEY_INTERVAL_KM, value).apply()

    var serviceIntervalDays: Int
        get() = prefs.getInt(KEY_INTERVAL_DAYS, 180)
        set(value) = prefs.edit().putInt(KEY_INTERVAL_DAYS, value).apply()

    /** 里程累積估計（由車速積分得出，跨啟動保存） */
    var currentKm: Int
        get() = prefs.getInt(KEY_CURRENT_KM, 0)
        set(value) = prefs.edit().putInt(KEY_CURRENT_KM, value).apply()

    fun remainingKm(): Int? {
        if (lastServiceKm <= 0) return null
        return (serviceIntervalKm - (currentKm - lastServiceKm)).coerceAtLeast(0)
    }

    fun daysSinceService(): Long {
        val elapsed = System.currentTimeMillis() - lastServiceDateMs
        return (elapsed / 86_400_000L).coerceAtLeast(0L)
    }

    fun remainingDays(): Long =
        (serviceIntervalDays - daysSinceService()).coerceAtLeast(0L)

    fun isDue(): Boolean {
        val kmDue = lastServiceKm > 0 && currentKm - lastServiceKm >= serviceIntervalKm
        val dayDue = daysSinceService() >= serviceIntervalDays
        return kmDue || dayDue
    }

    companion object {
        private const val KEY_LAST_KM = "last_service_km"
        private const val KEY_LAST_DATE = "last_service_date_ms"
        private const val KEY_INTERVAL_KM = "interval_km"
        private const val KEY_INTERVAL_DAYS = "interval_days"
        private const val KEY_CURRENT_KM = "current_km"
    }
}

/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.content.Context

/**
 * 單位制：公制（km/h、°C、g/s、L/h、Nm）與英制（mph、°F、lb/min、gal/h、lb-ft）。
 * 選擇以 SharedPreferences 持久化（unit_system/system）。
 */
enum class UnitSystem {
    METRIC, IMPERIAL;

    fun speed(v: Float): Float = if (this == IMPERIAL) v * 0.621371f else v
    fun temp(v: Float): Float = if (this == IMPERIAL) v * 9f / 5f + 32f else v
    fun maf(v: Float): Float = if (this == IMPERIAL) v * 0.132277f else v
    fun fuelRate(v: Float): Float = if (this == IMPERIAL) v * 0.264172f else v
    fun torque(v: Float): Float = if (this == IMPERIAL) v * 0.737562f else v

    fun speedUnit(): String = if (this == IMPERIAL) "mph" else "km/h"
    fun tempUnit(): String = if (this == IMPERIAL) "°F" else "°C"
    fun mafUnit(): String = if (this == IMPERIAL) "lb/min" else "g/s"
    fun fuelRateUnit(): String = if (this == IMPERIAL) "gal/h" else "L/h"
    fun torqueUnit(): String = if (this == IMPERIAL) "lb-ft" else "Nm"

    companion object {
        private const val PREFS = "unit_system"
        private const val KEY = "system"

        fun load(context: Context): UnitSystem {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, METRIC.name) ?: METRIC.name
            return runCatching { valueOf(name) }.getOrDefault(METRIC)
        }

        fun save(context: Context, system: UnitSystem) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, system.name)
                .apply()
        }
    }
}

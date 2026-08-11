/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.trip

import android.content.Context

/**
 * 油耗校正：以「里程表 + 實際加油量」反推實測油耗，並計算校正係數供油耗顯示使用。
 *
 * 流程：begin(里程表 A) → 行駛 → finish(里程表 B, 加油量 L)，
 * 實測油耗 = L / (B-A)；校正係數 = 實測 / App 估算（區間無旅程時係數不變）。
 */
object FuelCalibration {

    private const val PREFS = "heliobd_fuel_cal"
    private const val KEY_START_ODO = "start_odo"
    private const val KEY_START_TIME = "start_time"
    private const val KEY_FACTOR = "factor"
    private const val KEY_LAST_ACTUAL = "last_actual"

    data class Result(
        val distanceKm: Double,
        val fuelL: Double,
        val actualL100: Double,
        val estL100: Double,
        val factor: Float,
    )

    private fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 目前校正係數（預設 1.0 = 不校正） */
    fun factor(context: Context): Float = prefs(context).getFloat(KEY_FACTOR, 1f)

    /** 進行中校正的基準里程表（無則 null） */
    fun startOdo(context: Context): Float? =
        if (prefs(context).contains(KEY_START_ODO)) {
            prefs(context).getFloat(KEY_START_ODO, 0f)
        } else {
            null
        }

    fun startTime(context: Context): Long = prefs(context).getLong(KEY_START_TIME, 0L)

    /** 上次實測油耗（L/100km），未校正過為 0 */
    fun lastActualL100(context: Context): Float = prefs(context).getFloat(KEY_LAST_ACTUAL, 0f)

    fun begin(context: Context, odoKm: Float) {
        prefs(context).edit()
            .putFloat(KEY_START_ODO, odoKm)
            .putLong(KEY_START_TIME, System.currentTimeMillis())
            .apply()
    }

    fun cancel(context: Context) {
        prefs(context).edit().remove(KEY_START_ODO).remove(KEY_START_TIME).apply()
    }

    /** 完成校正；estFuelL 為校正區間內 App 估算總油量（可為 0）。輸入無效回傳 null。 */
    fun finish(context: Context, endOdoKm: Float, fuelL: Float, estFuelL: Double): Result? {
        val startOdo = startOdo(context) ?: return null
        val distanceKm = (endOdoKm - startOdo).toDouble()
        if (distanceKm <= 0.0 || fuelL <= 0f) return null
        val actualL100 = fuelL / distanceKm * 100.0
        val estL100 = if (estFuelL > 0.0) estFuelL / distanceKm * 100.0 else 0.0
        val newFactor = if (estL100 > 0.0) {
            (actualL100 / estL100).toFloat().coerceIn(0.2f, 5.0f)
        } else {
            factor(context)
        }
        prefs(context).edit()
            .remove(KEY_START_ODO)
            .remove(KEY_START_TIME)
            .putFloat(KEY_FACTOR, newFactor)
            .putFloat(KEY_LAST_ACTUAL, actualL100.toFloat())
            .apply()
        return Result(distanceKm, fuelL.toDouble(), actualL100, estL100, newFactor)
    }
}

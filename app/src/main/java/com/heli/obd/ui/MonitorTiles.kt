/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.content.Context
import com.heli.obd.R
import com.heli.obd.elm.ObdManager
import com.heli.obd.pid.PidStore

/**
 * 即時數據自訂顯示設定：內建數據目錄 + 自訂 PID 的統一 Tile 模型。
 * 啟用清單以逗號分隔字串持久化（monitor_tiles/enabled_keys），保留勾選與排序順序。
 */
object MonitorTiles {

    data class Tile(
        val key: String,
        val title: String,
        val color: Int,
        val unitOf: (UnitSystem) -> String,
        val valueOf: (UnitSystem, ObdManager.LiveData) -> Float?,
    )

    private const val PREFS = "monitor_tiles"
    private const val KEY_ENABLED = "enabled_keys"

    fun builtin(context: Context): List<Tile> = listOf(
        Tile("rpm", context.getString(R.string.obd_rpm), 0xFF2ECC71.toInt(), { "RPM" }, { _, d -> d.rpm?.toFloat() }),
        Tile("speed", context.getString(R.string.obd_speed), 0xFFF1C40F.toInt(), { it.speedUnit() }, { s, d -> d.speed?.let { v -> s.speed(v.toFloat()) } }),
        Tile("coolant", context.getString(R.string.obd_temp), 0xFFE74C3C.toInt(), { it.tempUnit() }, { s, d -> d.coolant?.let { v -> s.temp(v.toFloat()) } }),
        Tile("voltage", context.getString(R.string.obd_voltage), 0xFF3498DB.toInt(), { "V" }, { _, d -> d.voltage }),
        Tile("load", context.getString(R.string.pid_name_load), 0xFF9B59B6.toInt(), { "%" }, { _, d -> d.load?.toFloat() }),
        Tile("maf", context.getString(R.string.pid_name_maf), 0xFF1ABC9C.toInt(), { it.mafUnit() }, { s, d -> d.maf?.let { v -> s.maf(v) } }),
        Tile("fuelRate", context.getString(R.string.pid_name_fuel_rate), 0xFFE67E22.toInt(), { it.fuelRateUnit() }, { s, d -> d.fuelRate?.let { v -> s.fuelRate(v) } }),
        Tile("torqueNm", context.getString(R.string.obd_torque), 0xFFE8EDF2.toInt(), { it.torqueUnit() }, { s, d -> d.torqueNm?.let { v -> s.torque(v) } }),
        Tile("fuelTrim", context.getString(R.string.pid_name_fuel_trim), 0xFF95A5A6.toInt(), { "%" }, { _, d -> d.fuelTrim }),
        Tile("afr", context.getString(R.string.pid_name_afr), 0xFF00B4D8.toInt(), { "AFR" }, { _, d -> d.afr }),
        Tile("map", context.getString(R.string.pid_name_map), 0xFF8E44AD.toInt(), { "kPa" }, { _, d -> d.map?.toFloat() }),
        Tile("timingAdvance", context.getString(R.string.pid_name_timing_advance), 0xFF16A085.toInt(), { "°" }, { _, d -> d.timingAdvance }),
        Tile("throttle", context.getString(R.string.pid_name_throttle), 0xFF2C3E50.toInt(), { "%" }, { _, d -> d.throttle?.toFloat() }),
        Tile("fuelLevel", context.getString(R.string.pid_name_fuel_level), 0xFFF39C12.toInt(), { "%" }, { _, d -> d.fuelLevel?.toFloat() }),
        Tile("moduleVoltage", context.getString(R.string.pid_name_module_voltage), 0xFF7F8C8D.toInt(), { "V" }, { _, d -> d.moduleVoltage }),
    )

    fun custom(pids: List<PidStore.CustomPid>): List<Tile> =
        pids.mapIndexed { i, p ->
            Tile(
                key = "custom:${p.id}",
                title = p.name,
                color = DataChartView.PALETTE[i % DataChartView.PALETTE.size],
                unitOf = { p.unit },
                valueOf = { _, d -> d.customValues[p.id] },
            )
        }

    /** 未設定過時回傳預設四項（rpm/speed/coolant/voltage） */
    fun loadEnabled(context: Context): LinkedHashSet<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_ENABLED, null) ?: return linkedSetOf("rpm", "speed", "coolant", "voltage")
        return LinkedHashSet(saved.split(",").filter { it.isNotEmpty() })
    }

    /** 以 CSV 儲存，保留勾選順序（即顯示順序） */
    fun saveEnabled(context: Context, keys: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENABLED, keys.joinToString(","))
            .apply()
    }

    /** 歷史數值依單位制換算（自訂 PID 不換算，保持原樣） */
    fun convert(key: String, system: UnitSystem, v: Float): Float = when (key) {
        "speed" -> system.speed(v)
        "coolant" -> system.temp(v)
        "maf" -> system.maf(v)
        "fuelRate" -> system.fuelRate(v)
        "torqueNm" -> system.torque(v)
        else -> v
    }
}

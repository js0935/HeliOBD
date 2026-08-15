/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.vehicles

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 車籍資料庫：以 JSON 儲存多台車輛（files/vehicles.json），
 * 並以 SharedPreferences 記錄目前選擇的車輛 ID。
 */
class VehicleStore(private val context: Context) {

    data class Vehicle(
        val id: Long,
        val name: String,
        val brand: String,
        val model: String = "",
        val engineCc: String,
        val note: String,
        val type: String = TYPE_MOTORCYCLE,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vehicle_prefs", Context.MODE_PRIVATE)

    fun load(): List<Vehicle> {
        val file = vehiclesFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val j = arr.getJSONObject(i)
                Vehicle(
                    id = j.getLong("id"),
                    name = j.getString("name"),
                    brand = j.optString("brand"),
                    model = j.optString("model"),
                    engineCc = j.optString("engineCc"),
                    note = j.optString("note"),
                    type = j.optString("type", TYPE_MOTORCYCLE),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun upsert(vehicle: Vehicle) {
        val list = load().toMutableList()
        val idx = list.indexOfFirst { it.id == vehicle.id }
        if (idx >= 0) list[idx] = vehicle else list.add(vehicle)
        write(list)
    }

    fun delete(id: Long) {
        write(load().filterNot { it.id == id })
        if (currentId() == id) prefs.edit().remove(KEY_CURRENT).apply()
    }

    fun currentId(): Long? =
        if (prefs.contains(KEY_CURRENT)) prefs.getLong(KEY_CURRENT, 0) else null

    fun setCurrent(id: Long) {
        prefs.edit().putLong(KEY_CURRENT, id).apply()
    }

    private fun write(list: List<Vehicle>) {
        val arr = JSONArray()
        list.forEach { v ->
            arr.put(
                JSONObject().apply {
                    put("id", v.id)
                    put("name", v.name)
                    put("brand", v.brand)
                    put("model", v.model)
                    put("engineCc", v.engineCc)
                    put("note", v.note)
                    put("type", v.type)
                }
            )
        }
        vehiclesFile().writeText(arr.toString())
    }

    private fun vehiclesFile(): File =
        File(context.filesDir, "vehicles.json")

    companion object {
        private const val KEY_CURRENT = "current_vehicle_id"
        const val TYPE_CAR = "car"
        const val TYPE_MOTORCYCLE = "motorcycle"
    }
}

package com.heli.obd.pid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自訂 PID 儲存：以 JSON 陣列存放用戶自訂的 OBD PID（名稱/模式/PID/單位/公式/範圍）。
 * 儲存位置：files/custom_pids.json。
 */
class PidStore(private val context: Context) {

    data class CustomPid(
        val id: Long,
        val name: String,
        val mode: String,
        val pid: String,
        val unit: String,
        val formula: String,
        val min: Double?,
        val max: Double?,
    )

    fun load(): List<CustomPid> {
        val file = pidFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fromJson(it) }
            }
        }.getOrElse { emptyList() }
    }

    fun upsert(pid: CustomPid) {
        val list = load().toMutableList()
        val idx = list.indexOfFirst { it.id == pid.id }
        if (idx >= 0) list[idx] = pid else list.add(pid)
        pidFile().writeText(JSONArray(list.map { toJson(it) }).toString())
    }

    fun delete(id: Long) {
        val list = load().filterNot { it.id == id }
        pidFile().writeText(JSONArray(list.map { toJson(it) }).toString())
    }

    private fun pidFile(): File =
        File(context.filesDir, "custom_pids.json")

    private fun toJson(p: CustomPid): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("mode", p.mode)
        put("pid", p.pid)
        put("unit", p.unit)
        put("formula", p.formula)
        p.min?.let { put("min", it) }
        p.max?.let { put("max", it) }
    }

    private fun fromJson(j: JSONObject): CustomPid =
        CustomPid(
            id = j.getLong("id"),
            name = j.getString("name"),
            mode = j.getString("mode"),
            pid = j.getString("pid"),
            unit = j.optString("unit", ""),
            formula = j.getString("formula"),
            min = if (j.has("min")) j.getDouble("min") else null,
            max = if (j.has("max")) j.getDouble("max") else null,
        )
}

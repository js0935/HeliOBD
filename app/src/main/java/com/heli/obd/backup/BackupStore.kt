/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 設定備份 / 還原：將所有 SharedPreferences 與 files 目錄下的使用者資料
 * 打包為單一 JSON 字串，可匯出至雲端（Google Drive 等）或本地儲存。
 */
object BackupStore {

    const val VERSION = 1

    private val PREFS_NAMES = listOf(
        "obd_prefs",
        "maintenance_prefs",
        "vehicle_prefs",
        "alert_prefs",
        "fuel_prefs",
        "trip_prefs",
        "obd_license_store",
        "heliobd_fuel_cal",
        "monitor_tiles",
        "heliobd_demo",
        "unit_system",
    )

    private val FILE_NAMES = listOf(
        "custom_pids.json",
        "vehicles.json",
        "leaf_battery.json",
        "accel_scores.json",
    )

    /** 產生備份 JSON 字串。 */
    fun export(context: Context): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        val prefs = JSONObject()
        PREFS_NAMES.forEach { name ->
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            if (all.isNotEmpty()) {
                val entries = JSONObject()
                all.forEach { (k, v) -> entries.put(k, encodeValue(v)) }
                prefs.put(name, entries)
            }
        }
        root.put("prefs", prefs)

        val files = JSONObject()
        FILE_NAMES.forEach { name ->
            val f = File(context.filesDir, name)
            if (f.exists()) {
                files.put(name, runCatching { f.readText() }.getOrNull().orEmpty())
            }
        }
        root.put("files", files)
        return root.toString()
    }

    /**
     * 從備份 JSON 還原。回傳成功還原的項目數；JSON 格式錯誤時回傳 -1。
     */
    fun import(context: Context, json: String): Int {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return -1
        var restored = 0

        root.optJSONObject("prefs")?.let { prefs ->
            prefs.keys().forEach { name ->
                val entries = prefs.optJSONObject(name) ?: return@forEach
                val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                entries.keys().forEach { key ->
                    decodeValue(entries.optJSONObject(key))?.let { (type, value) ->
                        when (type) {
                            "string" -> editor.putString(key, value as String)
                            "boolean" -> editor.putBoolean(key, value as Boolean)
                            "int" -> editor.putInt(key, (value as Number).toInt())
                            "long" -> editor.putLong(key, (value as Number).toLong())
                            "float" -> editor.putFloat(key, (value as Number).toFloat())
                            "stringset" -> editor.putStringSet(key, (value as? List<*>)?.map { it.toString() }?.toSet())
                        }
                        restored++
                    }
                }
                editor.apply()
            }
        }

        root.optJSONObject("files")?.let { files ->
            files.keys().forEach { name ->
                if (name in FILE_NAMES) {
                    val content = files.optString(name)
                    runCatching { File(context.filesDir, name).writeText(content) }.onSuccess {
                        restored++
                    }
                }
            }
        }
        return restored
    }

    private fun encodeValue(v: Any?): JSONObject = JSONObject().apply {
        when (v) {
            is String -> { put("t", "string"); put("v", v) }
            is Boolean -> { put("t", "boolean"); put("v", v) }
            is Int -> { put("t", "int"); put("v", v) }
            is Long -> { put("t", "long"); put("v", v) }
            is Float -> { put("t", "float"); put("v", v.toDouble()) }
            is Set<*> -> {
                put("t", "stringset")
                put("v", JSONArray(v.map { it.toString() }))
            }
            else -> {
                put("t", "string")
                put("v", v?.toString().orEmpty())
            }
        }
    }

    private fun decodeValue(obj: JSONObject?): Pair<String, Any>? {
        if (obj == null) return null
        val t = obj.optString("t")
        val v = obj.opt("v") ?: return null
        return when (t) {
            "string" -> t to v.toString()
            "boolean" -> t to v as Boolean
            "int" -> t to (v as Number).toInt()
            "long" -> t to (v as Number).toLong()
            "float" -> t to (v as Number).toFloat()
            "stringset" -> t to (v as JSONArray).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
            else -> null
        }
    }
}

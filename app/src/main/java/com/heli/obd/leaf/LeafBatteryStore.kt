/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.leaf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Nissan Leaf 電池健康（SoH）紀錄資料層：JSON 持久化（files/leaf_battery.json）。
 *
 * 每次量測（可從 LeafSpy Pro 或車載資訊得知）紀錄：
 * 里程、SoH（%）、AHr（電池容量）、Hx（內阻指數）、GIDs（能量單位）與備註。
 */
class LeafBatteryStore(private val context: Context) {

    data class LeafRecord(
        val id: Long,
        val timestampMs: Long,
        val mileageKm: Int,
        val soh: Float,
        val ahr: Float,
        val hx: Float,
        val gids: Int,
        val note: String,
    )

    fun load(): List<LeafRecord> {
        val file = recordFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fromJson(it) }
            }
        }.getOrElse { emptyList() }
    }

    fun add(record: LeafRecord) {
        val list = load().toMutableList().apply { add(record) }
        write(list)
    }

    fun delete(id: Long) {
        write(load().filterNot { it.id == id })
    }

    /** 最新一筆紀錄（依時間）。 */
    fun latest(): LeafRecord? = load().maxByOrNull { it.timestampMs }

    /** 每萬公里 SoH 衰退幅度（%），需至少兩筆且里程有進展。 */
    fun sohDecayPer10kKm(): Float? {
        val sorted = load().sortedBy { it.mileageKm }
        if (sorted.size < 2) return null
        val first = sorted.first()
        val last = sorted.last()
        val km = last.mileageKm - first.mileageKm
        if (km <= 0) return null
        return (first.soh - last.soh) / km * 10_000f
    }

    /** 依線性衰退推估 SoH 降至目標值時的里程。 */
    fun estimatedKmTo(targetSoh: Float): Int? {
        val latest = latest() ?: return null
        val decay = sohDecayPer10kKm() ?: return null
        if (decay <= 0f) return null
        val drop = latest.soh - targetSoh
        if (drop <= 0f) return latest.mileageKm
        return (drop / decay * 10_000f).toInt() + latest.mileageKm
    }

    private fun recordFile(): File =
        File(context.filesDir, "leaf_battery.json")

    private fun write(list: List<LeafRecord>) {
        recordFile().writeText(JSONArray(list.map { toJson(it) }).toString())
    }

    private fun toJson(r: LeafRecord): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("ts", r.timestampMs)
        put("km", r.mileageKm)
        put("soh", r.soh)
        put("ahr", r.ahr)
        put("hx", r.hx)
        put("gids", r.gids)
        put("note", r.note)
    }

    private fun fromJson(j: JSONObject): LeafRecord =
        LeafRecord(
            id = j.getLong("id"),
            timestampMs = j.getLong("ts"),
            mileageKm = j.getInt("km"),
            soh = j.getDouble("soh").toFloat(),
            ahr = j.getDouble("ahr").toFloat(),
            hx = j.getDouble("hx").toFloat(),
            gids = j.getInt("gids"),
            note = j.optString("note", ""),
        )
}

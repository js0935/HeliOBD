/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.vehicles

import android.content.Context
import org.json.JSONArray

/**
 * 車廠／車型資料庫：讀取 assets/vehicle_brands.json（取自 OBD2 診斷 App 的 brands_db.json 整理）。
 *
 * 提供新增／編輯車輛時的「選品牌 → 選車型」清單；載入時：
 * - 以大小寫不敏感合併重複品牌（如 MAZDA / Mazda），並合併車型去重。
 * - 過濾佔位項目（[Other brands] 與 Make）。
 */
class VehicleBrands(context: Context) {

    /** 品牌與其內建車型清單（車型可能為空） */
    data class Brand(val name: String, val models: List<String>)

    private val brands: List<Brand> = runCatching {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        val order = mutableListOf<String>()
        val display = mutableMapOf<String, String>()
        val models = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val name = j.optString("brand").trim()
            if (name.isEmpty() || name.lowercase() in PLACEHOLDERS) continue
            val key = name.lowercase()
            display.putIfAbsent(key, name)
            if (key !in models) order += key
            val list = models.getOrPut(key) { mutableListOf() }
            val ma = j.optJSONArray("models")
            if (ma != null) {
                for (k in 0 until ma.length()) {
                    val m = ma.optString(k).trim()
                    if (m.isNotEmpty() && m !in list) list += m
                }
            }
        }
        order.map { key -> Brand(display.getValue(key), models.getValue(key).toList()) }
    }.getOrDefault(emptyList())

    /** 全部品牌名稱（依資料檔順序） */
    fun brandNames(): List<String> = brands.map { it.name }

    /** 指定品牌的內建車型清單；品牌不存在或無車型回傳空清單 */
    fun modelsOf(brand: String): List<String> {
        val key = brand.trim().lowercase()
        return brands.firstOrNull { it.name.lowercase() == key }?.models ?: emptyList()
    }

    companion object {
        private const val ASSET = "vehicle_brands.json"
        private val PLACEHOLDERS = setOf("[other brands]", "make")
    }
}

/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.vwtp

/**
 * VW TP 2.0 感測器單位顯示符號映射。
 *
 * 對應 CarScanner 原始 C# 的 Units enum + GetCaptionInvariant()：公式表 JSON 的
 * unit 字串即 Units enum 成員名（C# 以 TryGetUnitsFromText 反查）。此處僅映射
 * vwtp_formulas.json 實際出現的單位；未知單位回傳原字串（與 C# fallback
 * unit.ToString() 行為一致）。
 */
object VwtpUnitSymbols {

    /** unit 字串（Units enum 名）→ 顯示符號。 */
    private val SYMBOLS: Map<String, String> = mapOf(
        "None" to "",
        "A" to "A",
        "Ah" to "Ah",
        "ATDC" to "ATDC",
        "BTDC" to "BTDC",
        "bar" to "bar",
        "celicium" to "°C",
        "cm" to "cm",
        "db" to "dB",
        "grads" to "°",
        "grads_CS" to "°CS",
        "grads_sec" to "°/s",
        "gramms" to "g",
        "grams_sec" to "g/s",
        "kg_h" to "kg/h",
        "km" to "km",
        "kmh" to "km/h",
        "kOhm" to "kOhm",
        "kW" to "kW",
        "l_per_mm" to "l/mm",
        "Lh" to "L/h",
        "liters" to "L",
        "liters100km" to "L/100km",
        "m_sec2" to "m/s²",
        "mA" to "mA",
        "mbar" to "mbar",
        "meters" to "m",
        "mg" to "mg",
        "mg_hour" to "mg/h",
        "mg_km" to "mg/km",
        "mg_sec" to "mg/s",
        "minutes" to "min",
        "mm" to "mm",
        "mOhm" to "mOhm",
        "ms" to "ms",
        "mV" to "mV",
        "Nm" to "Nm",
        "Ohm" to "Ohm",
        "per_minute" to "/min",
        "per_second" to "/s",
        "percent" to "%",
        "ppm" to "ppm",
        "rpm" to "rpm",
        "seconds" to "s",
        "volts" to "V",
        "W" to "W",
    )

    /** 取得單位顯示符號；未知單位回傳原字串。 */
    fun symbolOf(unit: String): String = SYMBOLS[unit] ?: unit
}

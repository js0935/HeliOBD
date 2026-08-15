/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

/**
 * CAN 11-bit 回應 header 預測（移植 Car Scanner CAN11bitHelper.GetPossibleResponseHeader）。
 * 依請求 header（3 位十六進位，如 7E0）與車廠推斷 ECU 回應 header（如 7E8）。
 * 不同車廠在診斷位址上偏移不同；品牌未指定時套用標準偏移 +8。
 */
object Can11bitHelper {

    /** 依請求 header 預測回應 header；header 非 3 位 hex 時回傳空字串 */
    fun predictResponseHeader(requestHeader: String, brand: String? = null): String {
        val trimmed = requestHeader.trim().uppercase()
        val value = trimmed.toIntOrNull(16) ?: return ""
        if (value !in 0..0x7FF) return ""
        if (value in 0x7E0..0x7E7) return "%03X".format(value + 8)
        return when (brand?.trim()?.uppercase()) {
            "RENAULT", "SAMSUNG", "DACIA", "NISSAN", "INFINITI", "INFINITY" ->
                if (value in 0x7A0..0x7A7) "%03X".format(value + 8) else "%03X".format(value + 32)
            "MITSUBISHI" -> "%03X".format(value + 1)
            "PORSCHE", "BENTLEY", "CUPRA", "SKODA", "VOLKSWAGEN", "AUDI", "SEAT" ->
                "%03X".format(value + 106)
            "PEUGEOT", "CITROEN", "DS" -> "%03X".format(value - 32)
            "SUZUKI" ->
                if (trimmed.startsWith("2")) "6" + trimmed.substring(1, 3)
                else "%03X".format(value + 8)
            "HOVER", "HAVAL", "GREAT WALL" -> "%03X".format(value + 64)
            "ISUZU", "LIFAN", "XPENG", "DONGFENG" -> "%03X".format(value + 128)
            else -> "%03X".format(value + 8)
        }
    }
}

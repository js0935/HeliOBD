/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

/**
 * ECU 名稱查詢（移植 Car Scanner CAN11bitHelper.GetECUName 的精簡品牌對照）。
 * 依「回應 header」與「車廠品牌」回傳模組中文名稱；未收錄回 null。
 * 品牌未指定時僅回傳通用映射（7E8 引擎 / 7E9 變速箱），
 * 收錄品牌可提供更精確的模組命名（供未來品牌選擇功能與掃描結果強化使用）。
 */
object EcuNameHelper {

    /** 依回應 header 與品牌查詢 ECU 名稱；未收錄回 null */
    fun ecuNameFor(responseHeader: String, brand: String? = null): String? {
        val header = responseHeader.trim().uppercase()
        if (header == "7E8") return "引擎"
        if (header == "7E9") return "變速箱"
        val normalized = brand?.trim()?.uppercase() ?: return null
        val table = BRAND_TABLES[normalized] ?: return null
        if (normalized == "BMW" || normalized == "MINI") {
            if (header.length != 3) return null
            return table[header.substring(1, 3)]
        }
        return table[header]
    }

    /** VAG 集團（VW/Audi/Seat/Skoda/Porsche/Bentley/Cupra） */
    private val VAG_TABLE = mapOf(
        "77D" to "ABS", "7DD" to "影音系統", "78D" to "電動尾門", "7ED" to "混合動力電池管理",
        "77C" to "EPS", "79C" to "啟動/存取授權", "84C" to "引擎", "7BC" to "電子手煞車", "7CC" to "煞車系統感測器",
        "7B7" to "前乘客座椅", "797" to "車頂", "7D7" to "電視", "787" to "充電單元",
        "7B0" to "空調", "780" to "主動轉向", "850" to "致動器",
        "778" to "BCM", "788" to "差速鎖", "7B8" to "車道變換輔助",
        "7DA" to "媒體系統 1", "77A" to "CAN 閘道",
        "774" to "停車輔助", "7D4" to "第二加熱器", "784" to "後座空調", "7B4" to "駕駛側車門", "7C4" to "影音系統",
        "7C1" to "自適應巡航", "7B1" to "拖車", "7D1" to "緊急呼叫單元",
        "77F" to "安全氣囊", "7BF" to "自動調平控制單元", "7AF" to "中央舒適單元", "7CF" to "高壓電池充電管理",
        "776" to "轉向", "7B6" to "駕駛座椅", "7D6" to "導航", "796" to "特殊功能單元", "726" to "安全氣囊",
        "77E" to "儀表板", "7BE" to "頭燈水平校正", "7EE" to "電動驅動", "7AE" to "高壓電池充電器",
        "792" to "充電控制", "722" to "後攝影機",
        "779" to "四輪驅動", "7D9" to "音響系統", "7B9" to "前方輔助",
        "7A5" to "煞車輔助", "7B5" to "前乘客車門", "775" to "TPMS", "7D5" to "電話",
        "77B" to "防盜器", "7D3" to "後攝影機",
    )

    /** BMW / Mini（以回應 header 後兩碼查詢） */
    private val BMW_TABLE = mapOf(
        "00" to "IPDM", "20" to "TPMS", "30" to "轉向", "40" to "進入與啟動系統", "60" to "儀表板",
        "01" to "安全氣囊", "21" to "自適應巡航", "31" to "DVD 換片機",
        "0B" to "引擎", "0F" to "差速鎖",
        "12" to "引擎", "62" to "中央閘道",
        "16" to "主動轉向",
        "18" to "變速箱", "38" to "氣壓懸吊", "78" to "空調",
        "19" to "四輪驅動", "29" to "DSC", "79" to "後加熱器",
        "1C" to "懸吊",
        "2A" to "電子手煞車",
        "64" to "停車輔助",
    )

    /** Hyundai / Genesis / Kia */
    private val HYUNDAI_TABLE = mapOf(
        "7ED" to "四輪驅動", "7CD" to "LDC", "748" to "四輪驅動", "7A8" to "TPMS",
        "7DC" to "EPS", "7EC" to "EV BMS",
        "7D9" to "ABS",
        "7DE" to "TPMS", "7CE" to "儀表板",
        "7EA" to "VMCU", "7DA" to "安全氣囊",
        "7BB" to "EV 空調",
        "7CF" to "緊急呼叫",
    )

    /** Toyota / Lexus */
    private val TOYOTA_TABLE = mapOf(
        "72E" to "啟動/停止系統",
        "70D" to "後馬達發電機", "74D" to "插電式控制",
        "74F" to "高壓電池",
        "70B" to "太陽能充電控制", "789" to "太陽能充電控制",
        "799" to "雷達巡航控制", "7A9" to "EMPS", "7B9" to "儀表板",
        "798" to "雷達巡航控制", "7B8" to "ABS", "7C8" to "儀表板", "788" to "安全氣囊",
        "758" to "閘道",
        "7CC" to "空調",
    )

    /** Nissan / Renault / Dacia / Infiniti / Infinity */
    private val NISSAN_TABLE = mapOf(
        "7ED" to "混合動力電池", "76D" to "IPDM",
        "7AC" to "差速鎖", "76C" to "智慧鑰匙", "72C" to "底盤",
        "75F" to "e-4WD",
        "779" to "側車門",
        "772" to "安全氣囊", "762" to "EPS",
        "768" to "四輪驅動",
        "765" to "BCM",
        "764" to "空調/暖氣",
        "763" to "儀表板",
        "760" to "ABS",
    )

    /** Ford / Mazda */
    private val FORD_TABLE = mapOf(
        "728" to "儀表板", "72E" to "BCM",
        "738" to "轉向", "739" to "進入系統", "73C" to "AFS/ALM", "73F" to "安全氣囊",
        "74C" to "駕駛座椅",
        "75C" to "DCM", "75E" to "電子手煞車",
        "768" to "ABS/ESP",
        "77D" to "後車門",
        "78C" to "多媒體",
        "7AC" to "擴大機",
    )

    /** Peugeot / Citroen / DS */
    private val PSA_TABLE = mapOf(
        "688" to "引擎", "689" to "變速箱",
    )

    /** Volvo */
    private val VOLVO_TABLE = mapOf(
        "79B" to "CVM", "799" to "TRM", "79C" to "PPM", "79F" to "SAS",
        "72E" to "CEM", "72F" to "IAM", "728" to "DIM",
        "739" to "KVM", "73B" to "CCM", "73E" to "PAM", "738" to "PSCM", "73F" to "安全氣囊",
        "7CC" to "SODL", "7CE" to "SODR", "7C9" to "PAC",
        "7AC" to "AUD", "7DE" to "DABM", "70F" to "TVM", "78C" to "ICM",
        "75C" to "PHM", "75E" to "PBM", "748" to "DDM", "749" to "PDM", "74C" to "PSM", "768" to "BCM",
    )

    /** Subaru */
    private val SUBARU_TABLE = mapOf(
        "7B8" to "ABS", "75A" to "BCM",
        "788" to "安全氣囊", "78B" to "儀表板", "78A" to "啟動-停止系統",
        "7CC" to "空調/暖氣",
        "7DD" to "顯示器",
    )

    /** Mitsubishi */
    private val MITSUBISHI_TABLE = mapOf(
        "785" to "ABS", "7B7" to "AWC 四輪驅動", "689" to "空調", "6A1" to "儀表板", "774" to "TPMS",
    )

    /** Land Rover / Range Rover */
    private val LANDROVER_TABLE = mapOf(
        "79D" to "後差速器", "769" to "分動箱", "768" to "ABS", "79A" to "地形反應",
    )

    /** Lada / VAZ */
    private val LADA_TABLE = mapOf(
        "7EB" to "ABS", "7E7" to "BCM", "7E5" to "安全氣囊",
    )

    private val BRAND_TABLES: Map<String, Map<String, String>> = buildMap {
        val vag = setOf("VOLKSWAGEN", "AUDI", "SEAT", "SKODA", "PORSCHE", "BENTLEY", "CUPRA")
        vag.forEach { put(it, VAG_TABLE) }
        setOf("BMW", "MINI").forEach { put(it, BMW_TABLE) }
        setOf("HYUNDAI", "GENESIS", "KIA").forEach { put(it, HYUNDAI_TABLE) }
        setOf("TOYOTA", "LEXUS").forEach { put(it, TOYOTA_TABLE) }
        setOf("NISSAN", "RENAULT", "DACIA", "INFINITI", "INFINITY").forEach { put(it, NISSAN_TABLE) }
        setOf("FORD", "MAZDA").forEach { put(it, FORD_TABLE) }
        setOf("PEUGEOT", "CITROEN", "DS").forEach { put(it, PSA_TABLE) }
        put("VOLVO", VOLVO_TABLE)
        put("SUBARU", SUBARU_TABLE)
        put("MITSUBISHI", MITSUBISHI_TABLE)
        setOf("LAND ROVER", "RANGE ROVER").forEach { put(it, LANDROVER_TABLE) }
        setOf("LADA", "VAZ").forEach { put(it, LADA_TABLE) }
    }
}

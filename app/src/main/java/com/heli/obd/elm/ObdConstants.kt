/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import androidx.annotation.StringRes
import com.heli.obd.R

/**
 * ELM327 OBD-II 通訊常數與 DTC 描述表。
 */
object ObdConstants {

    /** 藍牙 SPP（序列埠）標準 UUID */
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    /** 判斷 ELM327 裝置名稱的關鍵字（配對裝置常見品牌/樣式，放寬以涵蓋山寨晶片） */
    val ELM327_NAME_KEYWORDS = listOf(
        "OBD", "ELM", "V-LINK", "CARSCANNER", "OBDLINK", "KIWI", "PLX",
        "ICAR", "VEEPEAK", "BAFX", "KONNWEI", "OBDMATE", "AUTOPHIX", "MX", "LINK",
    )

    // ===== ELM327 AT 指令 =====
    const val CMD_RESET = "ATZ"          // 重設
    const val CMD_ECHO_OFF = "ATE0"      // 關閉回應
    const val CMD_LINEFEED_OFF = "ATL0"  // 關閉換行
    const val CMD_SPACES_OFF = "ATS0"    // 關閉空格
    const val CMD_HEADERS_OFF = "ATH0"   // 關閉標頭
    const val CMD_AUTO_PROTOCOL = "ATSP0" // 自動協定
    const val CMD_AUTO_PROTOCOL_ALT = "ATSP A0" // 自動協定（空白寫法，部分 clone 才接受）
    const val CMD_VOLTAGE = "ATRV"       // 電瓶電壓

    // ===== OBD 服務模式 =====
    const val MODE_CURRENT_DATA = "01"   // 即時數據
    const val MODE_FREEZE_FRAME = "02"   // 凍結框
    const val MODE_DTC = "03"            // 讀取故障碼
    const val MODE_CLEAR_DTC = "04"      // 清除故障碼
    const val MODE_PENDING_DTC = "07"    // 待處理故障碼
    const val MODE_VEHICLE_INFO = "09"   // 車輛資訊（VIN）
    const val MODE_PERMANENT_DTC = "0A"  // 永久故障碼

    // ===== 常用 PID =====
    const val PID_STATUS = "01"          // I/M 就緒狀態
    const val PID_FREEZE_DTC = "02"      // 凍結框觸發碼
    const val PID_LOAD = "04"            // 引擎負載
    const val PID_COOLANT = "05"         // 水溫
    const val PID_SHORT_FUEL_TRIM = "06" // 短期燃油修正
    const val PID_LONG_FUEL_TRIM = "07"  // 長期燃油修正
    const val PID_INTAKE = "0F"          // 進氣溫度
    const val PID_MAF = "10"             // 空氣流量
    const val PID_RPM = "0C"             // 引擎轉速
    const val PID_SPEED = "0D"           // 車速
    const val PID_FUEL_RATE = "5E"       // 燃油消耗率
    const val PID_TORQUE = "63"          // 引擎扭力
    const val PID_WIDEBAND_AFR = "34"    // 寬域空燃比

    /** 凍結框常用 PID（以即時數據相同格式解碼） */
    val FREEZE_FRAME_PIDS = listOf(
        PID_COOLANT to R.string.pid_name_coolant,
        PID_RPM to R.string.pid_name_rpm,
        PID_SPEED to R.string.pid_name_speed,
        PID_LOAD to R.string.pid_name_load,
    )

    /** 即時數據輪詢間隔（毫秒） */
    const val POLL_INTERVAL_MS = 500L

    /** ELM327 指令回應逾時（毫秒） */
    const val COMMAND_TIMEOUT_MS = 2000L

    /** 常見 DTC 描述表（未收錄的碼以通用格式顯示） */
    val dtcDescriptions: Map<String, Int> = mapOf(
        "P0100" to R.string.dtc_p0100,
        "P0101" to R.string.dtc_p0101,
        "P0102" to R.string.dtc_p0102,
        "P0103" to R.string.dtc_p0103,
        "P0106" to R.string.dtc_p0106,
        "P0107" to R.string.dtc_p0107,
        "P0108" to R.string.dtc_p0108,
        "P0110" to R.string.dtc_p0110,
        "P0112" to R.string.dtc_p0112,
        "P0113" to R.string.dtc_p0113,
        "P0115" to R.string.dtc_p0115,
        "P0116" to R.string.dtc_p0116,
        "P0117" to R.string.dtc_p0117,
        "P0118" to R.string.dtc_p0118,
        "P0120" to R.string.dtc_p0120,
        "P0121" to R.string.dtc_p0121,
        "P0122" to R.string.dtc_p0122,
        "P0123" to R.string.dtc_p0123,
        "P0130" to R.string.dtc_p0130,
        "P0131" to R.string.dtc_p0131,
        "P0132" to R.string.dtc_p0132,
        "P0133" to R.string.dtc_p0133,
        "P0134" to R.string.dtc_p0134,
        "P0171" to R.string.dtc_p0171,
        "P0172" to R.string.dtc_p0172,
        "P0201" to R.string.dtc_p0201,
        "P0202" to R.string.dtc_p0202,
        "P0203" to R.string.dtc_p0203,
        "P0204" to R.string.dtc_p0204,
        "P0300" to R.string.dtc_p0300,
        "P0301" to R.string.dtc_p0301,
        "P0302" to R.string.dtc_p0302,
        "P0303" to R.string.dtc_p0303,
        "P0304" to R.string.dtc_p0304,
        "P0325" to R.string.dtc_p0325,
        "P0327" to R.string.dtc_p0327,
        "P0328" to R.string.dtc_p0328,
        "P0335" to R.string.dtc_p0335,
        "P0336" to R.string.dtc_p0336,
        "P0340" to R.string.dtc_p0340,
        "P0400" to R.string.dtc_p0400,
        "P0401" to R.string.dtc_p0401,
        "P0420" to R.string.dtc_p0420,
        "P0440" to R.string.dtc_p0440,
        "P0442" to R.string.dtc_p0442,
        "P0455" to R.string.dtc_p0455,
        "P0500" to R.string.dtc_p0500,
        "P0505" to R.string.dtc_p0505,
        "P0506" to R.string.dtc_p0506,
        "P0507" to R.string.dtc_p0507,
        "P0562" to R.string.dtc_p0562,
        "P0563" to R.string.dtc_p0563,
        "P0601" to R.string.dtc_p0601,
        "P0606" to R.string.dtc_p0606,
        "P0700" to R.string.dtc_p0700,
        "P1125" to R.string.dtc_p1125,
        "P1128" to R.string.dtc_p1128,
        "P1129" to R.string.dtc_p1129,
        "P1130" to R.string.dtc_p1130,
        "P1235" to R.string.dtc_p1235,
        "P2120" to R.string.dtc_p2120,
        "P2122" to R.string.dtc_p2122,
        "P2127" to R.string.dtc_p2127,
        "C0035" to R.string.dtc_c0035,
        "C0040" to R.string.dtc_c0040,
        "C0045" to R.string.dtc_c0045,
        "C0050" to R.string.dtc_c0050,
        "C0130" to R.string.dtc_c0130,
        "C0245" to R.string.dtc_c0245,
        "U0100" to R.string.dtc_u0100,
        "U0121" to R.string.dtc_u0121,
        "U0140" to R.string.dtc_u0140,
        "U0155" to R.string.dtc_u0155,
    )

    /** 取得 DTC 描述資源；未收錄回傳通用說明 */
    @StringRes
    fun dtcDescriptionRes(code: String): Int =
        dtcDescriptions[code] ?: R.string.dtc_unknown

    /** 故障碼嚴重度分級 */
    enum class DtcSeverity { NORMAL, WARNING, CRITICAL }

    /** 依故障碼前綴分級：失火/水溫/曲軸/ECU/電子節氣門/ABS/通訊為嚴重，其餘動力與底盤碼為警告 */
    fun dtcSeverity(code: String): DtcSeverity {
        val c = code.uppercase().trim()
        return when {
            c.startsWith("U0") || c.startsWith("C013") || c.startsWith("C024") ->
                DtcSeverity.CRITICAL
            c.startsWith("P030") || c.startsWith("P033") || c.startsWith("P060") ||
                c.startsWith("P212") || c.startsWith("P0115") || c.startsWith("P0116") ||
                c.startsWith("P0117") || c.startsWith("P0118") -> DtcSeverity.CRITICAL
            c.startsWith("C") || c.startsWith("P") -> DtcSeverity.WARNING
            else -> DtcSeverity.NORMAL
        }
    }

    /** 維修建議群組（前綴比對，較長前綴需先列出以免誤命中） */
    private val ADVICE_GROUPS = listOf(
        "P0115" to R.string.dtc_advice_coolant,
        "P0116" to R.string.dtc_advice_coolant,
        "P0117" to R.string.dtc_advice_coolant,
        "P0118" to R.string.dtc_advice_coolant,
        "P0110" to R.string.dtc_advice_iat,
        "P0112" to R.string.dtc_advice_iat,
        "P0113" to R.string.dtc_advice_iat,
        "P030" to R.string.dtc_advice_ignition,
        "P017" to R.string.dtc_advice_fuel_mix,
        "P010" to R.string.dtc_advice_air,
        "P012" to R.string.dtc_advice_throttle,
        "P013" to R.string.dtc_advice_o2,
        "P020" to R.string.dtc_advice_injector,
        "P033" to R.string.dtc_advice_crank,
        "P032" to R.string.dtc_advice_knock,
        "P034" to R.string.dtc_advice_cam,
        "P040" to R.string.dtc_advice_egr,
        "P042" to R.string.dtc_advice_cat,
        "P044" to R.string.dtc_advice_evap,
        "P045" to R.string.dtc_advice_evap,
        "P050" to R.string.dtc_advice_speed_idle,
        "P056" to R.string.dtc_advice_battery,
        "P060" to R.string.dtc_advice_ecu,
        "P070" to R.string.dtc_advice_transmission,
        "P112" to R.string.dtc_advice_oem_fuel,
        "P113" to R.string.dtc_advice_oem_fuel,
        "P123" to R.string.dtc_advice_oem_fuel,
        "P212" to R.string.dtc_advice_throttle,
        "C0" to R.string.dtc_advice_wheel_speed,
        "C013" to R.string.dtc_advice_abs,
        "C024" to R.string.dtc_advice_abs,
        "U0" to R.string.dtc_advice_comm,
    )

    /** 取得 DTC 維修建議資源；未收錄回傳通用建議 */
    @StringRes
    fun dtcAdviceRes(code: String): Int {
        val c = code.uppercase().trim()
        return ADVICE_GROUPS.firstOrNull { c.startsWith(it.first) }?.second
            ?: R.string.dtc_advice_generic
    }
}

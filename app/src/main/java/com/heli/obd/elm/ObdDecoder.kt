/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import androidx.annotation.StringRes
import com.heli.obd.R

/** I/M 就緒監測單一測試項目 */
data class ImMonitorTest(@StringRes val nameRes: Int, val supported: Boolean, val ready: Boolean)

/** I/M 排放就緒狀態（mode 01 PID 01） */
data class ImReadiness(
    val milOn: Boolean,
    val dtcCount: Int,
    val tests: List<ImMonitorTest>,
) {
    val supportedCount: Int get() = tests.count { it.supported }
    val readyCount: Int get() = tests.count { it.supported && it.ready }
    val allReady: Boolean get() = supportedCount > 0 && readyCount == supportedCount
}

/** 凍結框資料（mode 02，觸發故障碼 + 當下關鍵數據；values 的 key 為字串資源 ID） */
data class FreezeFrame(
    val triggerDtc: String?,
    val values: Map<Int, Int?>,
)

/** Mode 06 車載監控單一測試結果 */
data class MonitorTest(
    val tid: Int,
    val testId: Int,
    val value: Long,
    val nameRes: Int?,
    val cylinder: Int? = null,
)

/** Mode 05 氧感測器測試單一結果 */
data class O2Test(
    val sensor: Int,
    val pid: Int,
    val nameRes: Int,
    val value: Float?,
    val unit: String,
)

/** Mode 08 雙向控制測試結果 */
data class EvapTest(
    val status: Int,
    @StringRes val statusRes: Int,
)

/** ECU 模組掃描結果（11-bit CAN header → 模組名稱） */
data class EcuModule(
    val header: String,
    @StringRes val nameRes: Int,
)

/** 連線診斷資訊（AT 指令查詢 adapter 版本 / 裝置描述 / 電壓 / 通訊協定） */
data class ConnectionDiag(
    val version: String?,
    val deviceDesc: String?,
    val voltage: Float?,
    val protocol: String?,
    val protocolNumber: String?,
)

/**
 * OBD 回應解碼器（純邏輯，無 Android 依賴）。
 *
 * ELM327 標準回應格式（ATH0 + ATE0 之後）：
 *  - 即時數據：`41 <PID> <data...>`，例如 `41 0C 1A F8`
 *  - 電壓：    `12.5V`
 *  - 故障碼：  `43 <2 bytes/DTC> ...`，例如 `43 01 03 00 02 11`
 */
object ObdDecoder {

    /** 引擎轉速（PID 0C）：A*256+B，再除以 4 → RPM */
    fun rpm(hexResponse: String): Int? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 4) return null
        val a = bytes[2]
        val b = bytes[3]
        return ((a shl 8) or b) / 4
    }

    /** 車速（PID 0D）：A → km/h */
    fun speed(hexResponse: String): Int? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return bytes[2]
    }

    /** 水溫（PID 05）：A-40 → °C */
    fun coolantTemp(hexResponse: String): Int? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return bytes[2] - 40
    }

    /** 進氣溫度（PID 0F）：A-40 → °C */
    fun intakeTemp(hexResponse: String): Int? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return bytes[2] - 40
    }

    /** 電瓶電壓（ATRV）：`12.5V` → 12.5；失敗回傳 null */
    fun voltage(raw: String): Float? {
        val m = Regex("([0-9]+(?:\\.[0-9]+)?)").find(raw) ?: return null
        return m.groupValues[1].toFloatOrNull()
    }

    /** 引擎負載（PID 04）：A*100/255 → % */
    fun engineLoad(hexResponse: String): Int? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return bytes[2] * 100 / 255
    }

    /** 空氣流量（PID 10）：(A*256+B)/100 → g/s */
    fun maf(hexResponse: String): Float? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 4) return null
        return ((bytes[2] shl 8) or bytes[3]) / 100f
    }

    /** 燃油消耗率（PID 5E）：(A*256+B)*0.05 → L/h */
    fun fuelRate(hexResponse: String): Float? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 4) return null
        return ((bytes[2] shl 8) or bytes[3]) * 0.05f
    }

    /** 短期燃油修正（PID 06）：(A-128)*100/128 → %（負 = 供油偏濃、正 = 偏稀） */
    fun fuelTrim(hexResponse: String): Float? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return (bytes[2] - 128) * 100f / 128f
    }

    /** 寬域空燃比（PID 34）：(A*256+B)/32768*14.7 → AFR */
    fun widebandAfr(hexResponse: String): Float? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 4) return null
        return ((bytes[2] shl 8) or bytes[3]) / 32768f * 14.7f
    }

    /** 引擎扭力（PID 63）：A*256+B → Nm */
    fun torqueNm(hexResponse: String): Float? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 4) return null
        return ((bytes[2] shl 8) or bytes[3]).toFloat()
    }

    /**
     * 馬力/扭力估算（P = T*RPM/9549）。
     * 以 PID 63 即時扭力與當前轉速計算輸出功率（kW）。
     */
    fun powerKw(rpm: Int, torqueNm: Float): Float = torqueNm * rpm / 9549f

    /** 以 MAF 反推功率（kW）≈ MAF*0.98（假設 BSFC 250g/kWh） */
    fun powerKwFromMaf(maf: Float): Float = maf * 0.98f

    /** kW → HP 換算（1 kW ≈ 1.341 HP） */
    fun kwToHp(kw: Float): Float = kw * 1.341f

    /** I/M 就緒狀態（mode 01 PID 01）：`41 01 A B C D E`，A 高位元=MIL、低 7 bits=DTC 數 */
    fun imReadiness(hexResponse: String): ImReadiness? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 7 || bytes[0] != 0x41) return null
        val support = (bytes[3].toLong() shl 8) or bytes[4].toLong()
        val ready = (bytes[5].toLong() shl 8) or bytes[6].toLong()
        val names = listOf(
            R.string.diag_im_test_misfire, R.string.diag_im_test_fuel, R.string.diag_im_test_components,
            R.string.diag_im_test_catalyst, R.string.diag_im_test_heated_catalyst,
            R.string.diag_im_test_evap, R.string.diag_im_test_secondary_air, R.string.diag_im_test_ac,
            R.string.diag_im_test_o2, R.string.diag_im_test_o2_heater, R.string.diag_im_test_egr,
        )
        val tests = names.mapIndexed { index, nameRes ->
            val supported = (support shr index) and 1L == 1L
            val isReady = (ready shr index) and 1L == 1L
            ImMonitorTest(nameRes, supported, isReady)
        }
        return ImReadiness(
            milOn = (bytes[2] and 0x80) != 0,
            dtcCount = bytes[2] and 0x7F,
            tests = tests,
        )
    }

    /** 凍結框觸發碼（mode 01 PID 02）：`41 02 XX XX` → P/C/B/U 碼 */
    fun freezeDtc(hexResponse: String): String? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 4 || bytes[0] != 0x41) return null
        return decodeDtc(bytes[2], bytes[3])
    }

    /**
     * Mode 06 車載監控測試結果（`46 <TID> <TestID> <data> ...`）。
     * 回應無長度欄位，依 TestID 對照表取值寬（預設 2 bytes）；失火計數（0x00）的
     * 高 4 bits 為缸號、低 12 bits 為計數（0xFFF = 超過上限）。
     */
    fun monitorTests(hexResponse: String): List<MonitorTest> {
        val bytes = parseBytes(hexResponse) ?: return emptyList()
        if (bytes.size < 3 || bytes[0] != 0x46) return emptyList()
        val tid = bytes[1]
        val result = mutableListOf<MonitorTest>()
        var i = 2
        while (i < bytes.size) {
            val testId = bytes[i]
            i++
            val width = valueWidth(tid, testId)
            if (i + width > bytes.size) break
            var value = 0L
            repeat(width) { value = (value shl 8) or bytes[i + it].toLong() }
            if (testId == 0x00 && width == 2) {
                val cylinder = (bytes[i] ushr 4) and 0x0F
                val count = ((bytes[i] and 0x0F) shl 8) or bytes[i + 1]
                result.add(
                    MonitorTest(tid, testId, count.toLong(), ObdConstants.MONITOR_TEST_NAMES[0x00], cylinder)
                )
            } else {
                result.add(MonitorTest(tid, testId, value, ObdConstants.MONITOR_TEST_NAMES[testId]))
            }
            i += width
        }
        return result
    }

    private fun valueWidth(tid: Int, testId: Int): Int = 2

    /**
     * Mode 05 氧感測器測試結果（`45 <PID> <A> <B> <C> <D>`）。
     * PID 01-06 為感測器 1、07-0C 為感測器 2、0D-12 為感測器 3、13-18 為感測器 4；
     * 每組內 1-4 為閾值電壓（/8192 V）、5-6 為轉換時間（/1000 s）。
     */
    fun o2Tests(hexResponse: String): List<O2Test> {
        val bytes = parseBytes(hexResponse) ?: return emptyList()
        if (bytes.size < 3 || bytes[0] != 0x45) return emptyList()
        val result = mutableListOf<O2Test>()
        var i = 1
        while (i + 5 <= bytes.size) {
            val pid = bytes[i]
            val a = bytes[i + 1]
            val b = bytes[i + 2]
            val sensor = ObdConstants.o2SensorOf(pid) ?: break
            val nameRes = ObdConstants.O2_TEST_NAMES[pid] ?: break
            val withinGroup = ((pid - 1) % 6) + 1
            val isVoltage = withinGroup <= 4
            val raw = (a shl 8) or b
            val value = if (isVoltage) raw / 8192f else raw / 1000f
            result.add(O2Test(sensor, pid, nameRes, value, if (isVoltage) "V" else "s"))
            i += 5
        }
        return result
    }

    /** Mode 08 EVAP 測試狀態（`48 <PID> <status>`）：0=未開始、1=進行中、2=通過、3=失敗、4=無法執行 */
    fun evapStatus(hexResponse: String): EvapTest? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3 || bytes[0] != 0x48) return null
        val status = bytes[2]
        val nameRes = ObdConstants.EVAP_STATUS_NAMES[status] ?: R.string.evap_status_unknown
        return EvapTest(status, nameRes)
    }

    /**
     * 校正 ID（mode 09 PID 0A）：多幀 ASCII，解析方式同 VIN（剝離 ISO-TP 幀標頭與續幀索引）。
     */
    fun calibrationId(hexResponse: String): String? {
        val bytes = parseBytes(hexResponse) ?: return null
        var start = -1
        for (i in 0..bytes.lastIndex - 2) {
            if (bytes[i] == 0x49 && bytes[i + 1] == 0x0A) {
                start = i + 2
                break
            }
        }
        if (start == -1) return null
        if (start < bytes.size && bytes[start] in 1..62) start++
        val sb = StringBuilder()
        for (i in start until bytes.size) {
            val b = bytes[i]
            if (b in 0x10..0x2F) continue
            if (b !in 0x30..0x7E) continue
            sb.append(b.toChar())
            if (sb.length >= 16) break
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** 校驗號碼（mode 09 PID 0B）：`49 0B XX XX XX XX` → 8 位 hex */
    fun cvn(hexResponse: String): String? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 6 || bytes[0] != 0x49 || bytes[1] != 0x0B) return null
        return bytes.copyOfRange(2, 6).joinToString("") { "%02X".format(it) }
    }

    /**
     * VIN（mode 09 PID 02）：多幀 ASCII 串接。
     * 回應含 `49 02 01` 前綴、ISO-TP 幀標頭（0x01/0x10 系列）與續幀索引（0x21/0x22 系列），
     * 需全部剝除後取 17 個可列印字元。
     */
    fun vin(hexResponse: String): String? {
        val bytes = parseBytes(hexResponse) ?: return null
        var start = -1
        for (i in 0..bytes.lastIndex - 2) {
            if (bytes[i] == 0x49 && bytes[i + 1] == 0x02) {
                start = i + 2
                break
            }
        }
        if (start == -1) return null
        if (start < bytes.size && bytes[start] in 1..62) start++
        val sb = StringBuilder()
        for (i in start until bytes.size) {
            val b = bytes[i]
            if (b in 0x10..0x2F) continue
            if (b !in 0x30..0x7E) continue
            sb.append(b.toChar())
            if (sb.length >= 17) break
        }
        return sb.toString().takeIf { it.length >= 11 }
    }

    /**
     * 故障碼清單。DTC 編碼（ISO 15031-6）：
     * 每 2 bytes 一碼：byte1 高 2 bits = 系統（00=P、01=C、10=B、11=U），
     * 剩餘 6 bits + byte2 = 4 位十六進位碼。
     * @param modeByte 回應首碼：0x43（mode 03）、0x47（mode 07 待處理）、0x4A（mode 0A 永久）
     */
    fun dtcList(hexResponse: String, modeByte: Int = 0x43): List<String> {
        val bytes = parseBytes(hexResponse) ?: return emptyList()
        if (bytes.size < 2 || bytes[0] != modeByte) return emptyList()

        val codes = mutableListOf<String>()
        var i = 1
        // 依序讀取 2 bytes 一組的 DTC
        while (i + 1 < bytes.size) {
            val b1 = bytes[i]
            val b2 = bytes[i + 1]
            // 00 00 代表無更多故障碼
            if (b1 == 0 && b2 == 0) break
            codes.add(decodeDtc(b1, b2))
            i += 2
        }
        return codes
    }

    /** 解碼單一 DTC 碼 */
    fun decodeDtc(b1: Int, b2: Int): String {
        val system = when ((b1 ushr 2) and 0x03) {
            0 -> "P"
            1 -> "C"
            2 -> "B"
            else -> "U"
        }
        val numeric = (b1 and 0x3F) shl 8 or b2
        return system + "%04X".format(numeric)
    }

    /** 自訂 PID raw 位元組：跳過回應模式與 PID echo（前 2 位元組）後續即 A/B/C/D…；格式錯誤回傳 null */
    fun rawValues(hexResponse: String): IntArray? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return bytes.copyOfRange(2, bytes.size)
    }

    /** 將 `41 0C 1A F8` 這類回應拆成位元組陣列；格式錯誤回傳 null */
    private fun parseBytes(hex: String): IntArray? {
        val tokens = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return try {
            IntArray(tokens.size) { tokens[it].toInt(16) }
        } catch (_: NumberFormatException) {
            null
        }
    }
}

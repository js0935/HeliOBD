/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import androidx.annotation.StringRes
import com.heli.obd.R
import java.util.Locale
import kotlin.math.roundToLong

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
    val floatValues: Map<Int, Float?> = emptyMap(),
)

/** Mode 06 車載監控單一測試結果 */
data class MonitorTest(
    val tid: Int,
    val testId: Int,
    val value: Long,
    val nameRes: Int?,
    val cylinder: Int? = null,
    /** 依 TestID 縮放後的值（SAE J1979 縮放表）；null 表示無縮放規格、以 raw 顯示 */
    val scaledValue: Double? = null,
    val unit: String = "",
    val minValue: Double? = null,
    val maxValue: Double? = null,
    /** 值是否落在 [minValue, maxValue] 區間（通過測試）；無上下限時為 null */
    val passed: Boolean? = null,
    val tidNameRes: Int? = null,
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

    /** 長期燃油修正（PID 07）：與短期燃油修正相同公式（A-128）*100/128 → % */
    fun fuelTrimLong(hexResponse: String): Float? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return (bytes[2] - 128) * 100f / 128f
    }

    /** 環境溫度（PID 46）：A-40 → °C */
    fun ambientTemp(hexResponse: String): Int? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return bytes[2] - 40
    }

    /** 引擎機油溫度（PID 5C）：A-40 → °C */
    fun oilTemp(hexResponse: String): Int? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return bytes[2] - 40
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

    /** 進氣歧管絕對壓力（PID 0B）：A → kPa */
    fun manifoldPressure(hexResponse: String): Int? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return bytes[2]
    }

    /** 點火提前角（PID 0E）：A/2 - 64 → ° */
    fun timingAdvance(hexResponse: String): Float? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return bytes[2] / 2f - 64f
    }

    /** 節氣門位置（PID 11）：A*100/255 → % */
    fun throttlePosition(hexResponse: String): Int? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return bytes[2] * 100 / 255
    }

    /** 燃油油位（PID 2F）：A*100/255 → % */
    fun fuelLevel(hexResponse: String): Int? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        return bytes[2] * 100 / 255
    }

    /** 控制模組電壓（PID 42）：(A*256+B)/1000 → V */
    fun moduleVoltage(hexResponse: String): Float? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 4) return null
        return ((bytes[2] shl 8) or bytes[3]) / 1000f
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

    /**
     * 支援 PID 清單（mode 01 PID 00/20/40）：`41 00 A B C D` → 32-bit 位元遮罩。
     * 最高位（bit 31）= PID 01、最低位（bit 0）= PID 32（此位亦表示下一個區塊存在）。
     * 格式不符回傳 null。
     */
    fun supportedPidMask(hexResponse: String): Long? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 6 || bytes[0] != 0x41) return null
        return (bytes[2].toLong() shl 24) or (bytes[3].toLong() shl 16) or
            (bytes[4].toLong() shl 8) or bytes[5].toLong()
    }

    /** 凍結框觸發碼（mode 01 PID 02）：`41 02 XX XX` → P/C/B/U 碼 */
    fun freezeDtc(hexResponse: String): String? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 4 || bytes[0] != 0x41) return null
        return decodeDtc(bytes[2], bytes[3])
    }

    /**
     * Mode 06 車載監控測試結果（`46 <TID> <TestID> <value:2> <min:2> <max:2> ...`）。
     * 每筆測試為 7 bytes（mode echo 46 + TID 後，TestID + 值/下限/上限各 2 bytes）；
     * 依 TestID 對照 SAE J1979 縮放表：標準 TestID（<128）以 unsigned 16-bit 讀取、
     * 製造商 TestID（>=128）以 signed 16-bit 讀取，並算出縮放值、單位與通過與否。
     * 失火計數（0x00）的高 4 bits 為缸號、低 12 bits 為計數（0xFFF = 超過上限）。
     */
    fun monitorTests(hexResponse: String): List<MonitorTest> {
        val bytes = parseBytes(hexResponse) ?: return emptyList()
        if (bytes.size < 3 || bytes[0] != 0x46) return emptyList()
        var tid = bytes[1]
        var tidNameRes = ObdConstants.monitorTidNameRes(tid)
        val result = mutableListOf<MonitorTest>()
        var i = 2
        while (i + 6 <= bytes.size) {
            // 多 TID 合併回應時，新的一組以 `46 <TID>` 起頭，切換 TID 後繼續
            if (bytes[i] == 0x46) {
                tid = bytes[i + 1]
                tidNameRes = ObdConstants.monitorTidNameRes(tid)
                i += 2
                continue
            }
            val testId = bytes[i]
            val hi = bytes[i + 1]
            val lo = bytes[i + 2]
            val minHi = bytes[i + 3]
            val minLo = bytes[i + 4]
            val maxHi = bytes[i + 5]
            val maxLo = bytes[i + 6]
            val spec = ObdConstants.mode06SpecOf(testId)
            val signed = spec?.signed == true
            val raw = if (signed) toSigned16(hi, lo) else (hi shl 8) or lo
            val minRaw = if (signed) toSigned16(minHi, minLo) else (minHi shl 8) or minLo
            val maxRaw = if (signed) toSigned16(maxHi, maxLo) else (maxHi shl 8) or maxLo

            if (testId == 0x00) {
                // 失火計數：高 4 bits 缸號、低 12 bits 計數
                val cylinder = (hi ushr 4) and 0x0F
                val count = ((hi and 0x0F) shl 8) or lo
                result.add(
                    MonitorTest(
                        tid, testId, count.toLong(), ObdConstants.MONITOR_TEST_NAMES[0x00],
                        cylinder = cylinder,
                        scaledValue = count.toDouble(),
                        tidNameRes = tidNameRes,
                    )
                )
            } else {
                val scaled = scale(raw, spec)
                val minScaled = scale(minRaw, spec)
                val maxScaled = scale(maxRaw, spec)
                val passed = if (spec != null) scaled >= minScaled && scaled <= maxScaled else null
                result.add(
                    MonitorTest(
                        tid, testId, raw.toLong(), ObdConstants.MONITOR_TEST_NAMES[testId],
                        scaledValue = scaled,
                        unit = spec?.unit.orEmpty(),
                        minValue = minScaled,
                        maxValue = maxScaled,
                        passed = passed,
                        tidNameRes = tidNameRes,
                    )
                )
            }
            i += 7
        }
        return result
    }

    /** 依縮放規格計算縮放值；無規格時回傳 raw 本身 */
    private fun scale(raw: Int, spec: ObdConstants.Mode06Spec?): Double {
        if (spec == null) return raw.toDouble()
        return if (spec.signed) raw * spec.m else raw * spec.m / 65535.0
    }

    /** 16-bit signed 解讀：0xFFFF → -1 */
    private fun toSigned16(hi: Int, lo: Int): Int {
        val raw = (hi shl 8) or lo
        return if (raw and 0x8000 != 0) raw - 0x10000 else raw
    }

    /** 縮放值格式化：至多 2 位小數、去除尾零（675.00 → 675、12.50 → 12.5） */
    fun formatScaled(value: Double): String {
        val rounded = (value * 100.0).roundToLong() / 100.0
        return "%.2f".format(Locale.US, rounded)
            .removeSuffix("0").removeSuffix("0").removeSuffix(".")
    }

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
     * ISO-TP 多幀回應重組（ISO 15765-2 over CAN 11/29-bit）。
     * ELM327 多幀回應為每幀一行，行首為 PCI（Protocol Control Information）：
     *  - 0x10-0x1F 首幀：`10 LL` 為 12-bit 總長度（含 OBD 前綴），資料自第 3 byte 起
     *  - 0x20-0x2F 續幀：`2N` 為序號，資料自第 2 byte 起（每幀最多 7 bytes）
     *  - 0x00-0x0F 單幀/流控：非資料幀，忽略
     *  - 其他（如 `49 02 ...`）：視為 ELM 已剝離 PCI 的資料行，整行收下
     * 重組後依首幀宣告長度截斷（去除 padding）。非多幀回應（單行資料）直接回傳解析結果。
     * 格式錯誤回傳 null。
     */
    fun assembleIsoTp(hexResponse: String): IntArray? {
        val lines = hexResponse.trim().split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val out = mutableListOf<Int>()
        var declaredLength = -1
        var isMultiFrame = false
        for (line in lines) {
            val bytes = parseLineBytes(line) ?: continue
            if (bytes.isEmpty()) continue
            val pci = bytes[0]
            when {
                pci in 0x10..0x1F -> {
                    // 首幀：宣告總長度，資料自第 3 byte 起
                    isMultiFrame = true
                    declaredLength = if (bytes.size > 1) ((pci and 0x0F) shl 8) or bytes[1] else (pci and 0x0F)
                    if (bytes.size > 2) out.addAll(bytes.drop(2))
                }
                pci in 0x20..0x2F -> {
                    // 續幀：資料自第 2 byte 起（最多 7 bytes）
                    isMultiFrame = true
                    if (bytes.size > 1) out.addAll(bytes.drop(1))
                }
                pci in 0x00..0x0F -> {
                    // 單幀（長度 = PCI）或流控幀；ELM 一般已剝離，直接忽略
                }
                else -> {
                    // 無 PCI 的資料行（ELM 已剝離 / 單幀直通 / KWP header 已移除）
                    bytes.forEach { out.add(it) }
                }
            }
        }
        if (out.isEmpty()) return null
        if (declaredLength >= 0 && out.size > declaredLength) {
            return out.subList(0, declaredLength).toIntArray()
        }
        return out.toIntArray()
    }

    /** 解析單行 hex → bytes；格式錯誤回傳 null */
    private fun parseLineBytes(line: String): IntArray? {
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val bytes = try {
            IntArray(tokens.size) { tokens[it].toInt(16) }
        } catch (_: NumberFormatException) {
            return null
        }
        return stripElmHeader(bytes)
    }

    /** 校正 ID（mode 09 PID 0A）：ISO-TP 多幀重組後取 ASCII，解析方式同 VIN。 */
    fun calibrationId(hexResponse: String): String? =
        decodeAsciiInfo(hexResponse, pidByte = 0x0A, maxLen = 16)

    /** ECU 名稱（mode 09 PID 0D）：ISO-TP 多幀重組後取 ASCII，解析方式同校正 ID。 */
    fun ecuName(hexResponse: String): String? =
        decodeAsciiInfo(hexResponse, pidByte = 0x0D, maxLen = 20)

    /** mode 09 多幀 ASCII 共用解析：ISO-TP 重組後定位 `49 <pidByte>` 前綴，收集可列印字元 */
    private fun decodeAsciiInfo(hexResponse: String, pidByte: Int, maxLen: Int): String? {
        val payload = assembleIsoTp(hexResponse) ?: return null
        var start = -1
        for (i in 0..payload.lastIndex - 1) {
            if (payload[i] == 0x49 && payload[i + 1] == pidByte) {
                start = i + 2
                break
            }
        }
        if (start == -1) return null
        if (start < payload.size && payload[start] in 1..62) start++
        val sb = StringBuilder()
        for (i in start until payload.size) {
            val b = payload[i]
            if (b !in 0x30..0x7E) continue
            sb.append(b.toChar())
            if (sb.length >= maxLen) break
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
     * VIN（mode 09 PID 02）：ISO-TP 多幀重組後取 17 個可列印字元。
     */
    fun vin(hexResponse: String): String? {
        val payload = assembleIsoTp(hexResponse) ?: return null
        var start = -1
        for (i in 0..payload.lastIndex - 1) {
            if (payload[i] == 0x49 && payload[i + 1] == 0x02) {
                start = i + 2
                break
            }
        }
        if (start == -1) return null
        if (start < payload.size && payload[start] in 1..62) start++
        val sb = StringBuilder()
        for (i in start until payload.size) {
            val b = payload[i]
            if (b !in 0x30..0x7E) continue
            sb.append(b.toChar())
            if (sb.length >= 17) break
        }
        return sb.toString().takeIf { it.length >= 11 }
    }

    /** DTC 狀態：解讀自回應 status byte 或依模式推斷 */
    enum class DtcStatus { CURRENT, HISTORY, PENDING, CONFIRMED, PERMANENT, UNKNOWN }

    /** 單筆 DTC：碼 + 原始 status byte（2-byte 制式為 null）+ 解讀後狀態 */
    data class DtcRecord(val code: String, val statusRaw: Int?, val status: DtcStatus)

    /**
     * 故障碼清單。DTC 編碼（ISO 15031-6）：
     * 每 2 bytes 一碼：byte1 高 2 bits = 系統（00=P、01=C、10=B、11=U），
     * 剩餘 6 bits + byte2 = 4 位十六進位碼。
     * @param modeByte 回應首碼：0x43（mode 03）、0x47（mode 07 待處理）、0x4A（mode 0A 永久）
     * @param protocolNumber ATDPN 協定編號；用於判別 2-byte / 3-byte（含 status）制式
     */
    fun dtcList(
        hexResponse: String,
        modeByte: Int = 0x43,
        protocolNumber: Int? = null,
    ): List<String> = dtcRecords(hexResponse, modeByte, protocolNumber).map { it.code }

    /**
     * 故障碼清單（含狀態）。回應有三制式：
     * 1. 標準 OBD / CAN（ISO 15765）：每碼 2 bytes，無 status byte，狀態依模式推斷。
     * 2. KWP / ISO 串列（ISO 14230/9141）：每碼 3 bytes（DTC + status），
     *    status 0xFF 表示無狀態；否則 bit0 = 目前故障、bit1 = 上次清除後曾發生。
     * 3. UDS 制式（ISO 14229）：每碼 3 bytes，status 為 DTC status mask
     *    （bit0 test failed、bit2 pending、bit3 confirmed）。
     * @param protocolNumber ATDPN 協定編號；兩制式皆可整除時（6 bytes 倍數）用其判定 2/3-byte，
     *        且影響 3-byte status 的位元語意（串列 → KWP 制式、CAN → UDS 制式）
     */
    fun dtcRecords(
        hexResponse: String,
        modeByte: Int = 0x43,
        protocolNumber: Int? = null,
    ): List<DtcRecord> {
        val bytes = parseBytes(hexResponse) ?: return emptyList()
        if (bytes.size < 2 || bytes[0] != modeByte) return emptyList()

        val payload = bytes.drop(1)
        val step = dtcStepSize(payload.size, protocolNumber)
        val records = mutableListOf<DtcRecord>()
        var i = 0
        while (i + 1 < payload.size) {
            val b1 = payload[i]
            val b2 = payload[i + 1]
            // 00 00 代表無更多故障碼
            if (b1 == 0 && b2 == 0) break
            var statusRaw: Int? = null
            if (step == 3 && i + 2 < payload.size) statusRaw = payload[i + 2]
            records.add(
                DtcRecord(
                    code = decodeDtc(b1, b2),
                    statusRaw = statusRaw,
                    status = inferDtcStatus(modeByte, statusRaw, protocolNumber),
                )
            )
            i += step
        }
        return records
    }

    /** 決定每筆 DTC 的 byte 寬度：3-byte（含 status）或 2-byte（標準） */
    private fun dtcStepSize(payloadLen: Int, protocolNumber: Int?): Int = when {
        payloadLen % 3 == 0 && payloadLen % 2 != 0 -> 3
        payloadLen % 3 == 0 && payloadLen % 2 == 0 ->
            if (protocolNumber in ObdConstants.SLOW_PROTOCOL_NUMBERS) 3 else 2
        else -> 2
    }

    /** 推斷 DTC 狀態：3-byte 制式有 status byte 時依協定語意解讀，否則依模式推斷 */
    private fun inferDtcStatus(
        modeByte: Int,
        statusRaw: Int?,
        protocolNumber: Int?,
    ): DtcStatus {
        if (statusRaw == null) {
            return when (modeByte) {
                0x47 -> DtcStatus.PENDING
                0x4A -> DtcStatus.PERMANENT
                else -> DtcStatus.CONFIRMED
            }
        }
        // 0xFF：KWP 慣例的「無可用狀態」
        if (statusRaw == 0xFF) return DtcStatus.UNKNOWN
        return if (protocolNumber in ObdConstants.SLOW_PROTOCOL_NUMBERS) {
            // KWP / ISO 串列：bit0 = 目前故障、bit1 = 上次清除後曾發生
            when {
                statusRaw and 0x01 != 0 && statusRaw and 0x02 != 0 -> DtcStatus.CONFIRMED
                statusRaw and 0x01 != 0 -> DtcStatus.CURRENT
                statusRaw and 0x02 != 0 -> DtcStatus.HISTORY
                else -> DtcStatus.UNKNOWN
            }
        } else {
            // UDS 制式：bit0 = test failed、bit2 = pending、bit3 = confirmed
            when {
                statusRaw and 0x04 != 0 -> DtcStatus.PENDING
                statusRaw and 0x08 != 0 -> DtcStatus.CONFIRMED
                statusRaw and 0x01 != 0 -> DtcStatus.CURRENT
                else -> DtcStatus.UNKNOWN
            }
        }
    }

    /** 解碼單一 DTC 碼 */
    fun decodeDtc(b1: Int, b2: Int): String {
        val system = when ((b1 ushr 6) and 0x03) {
            0 -> "P"
            1 -> "C"
            2 -> "B"
            else -> "U"
        }
        val numeric = (b1 and 0x3F) shl 8 or b2
        return system + "%04X".format(numeric)
    }

    /**
     * 多 PID 合併指令（如 `01 0C 0D 05`）的多行回應拆解。
     * 每行格式 `<modeByte> <PID> <data...>`（mode 01 為 `41`、mode 02 凍結框為 `42`）。
     * 回傳 PID（2 位大寫 hex）→ 該行完整回應；無法解析的行直接略過。
     */
    fun parseMode01Batch(hexResponse: String, modeByte: Int = 0x41): Map<String, String> {
        val prefix = "%02X".format(modeByte)
        val result = mutableMapOf<String, String>()
        hexResponse.lines().forEach { line ->
            val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.size < 2) return@forEach
            // ISO 9141-2 / ISO 14230-4（KWP）回應帶 3 位元組 header（如 `48 6B 10`），
            // 山寨 ELM327 常無法以 ATH0 關閉，先剝除才能匹配 `<modeByte> <PID>`。
            val data = if (isElmHeaderTokens(tokens)) tokens.drop(3) else tokens
            if (data.size >= 2 && data[0].equals(prefix, ignoreCase = true)) {
                result[data[1].uppercase()] = data.joinToString(" ")
            }
        }
        return result
    }

    /** 自訂 PID raw 位元組：跳過回應模式與 PID echo（前 2 位元組）後續即 A/B/C/D…；格式錯誤回傳 null */
    fun rawValues(hexResponse: String): IntArray? {
        val bytes = parseBytes(hexResponse) ?: return null
        if (bytes.size < 3) return null
        // UDS ReadDataByIdentifier（mode 22）回應 `62 <DID高> <DID低> <data…>`，
        // 剝離 0x62 前綴與 2 位元組 DID，與 OBD-II `41 <pid> <data…>` 取得一致的資料區。
        if (bytes[0] == 0x62) {
            if (bytes.size < 4) return IntArray(0)
            return bytes.copyOfRange(3, bytes.size)
        }
        return bytes.copyOfRange(2, bytes.size)
    }

    /**
     * 將 UDS 回應轉換為 OBD-II 回應格式，供現有解碼器直接解析。
     * UDS ReadDataByIdentifier 回應為 `62 <DID高> <DID低> <data…>`（如 `62 F4 0C …`）；
     * 轉為 `<targetMode> <DID低> <data…>`（如 `41 0C …`）。
     * 僅處理 DID 高 byte 為 0xF0-0xFF 的行（F4=mode 01 前綴、F8=mode 09 前綴）；其餘行保留原樣。
     */
    fun normalizeUdsResponse(hexResponse: String, targetMode: Int = 0x41): String {
        val lines = hexResponse.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return hexResponse
        val out = mutableListOf<String>()
        for (line in lines) {
            val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.size >= 3 && tokens[0].equals("62", ignoreCase = true)) {
                val didHi = tokens[1].toIntOrNull(16)
                if (didHi != null && (didHi and 0xF0) == 0xF0) {
                    out.add((listOf("%02X".format(targetMode), tokens[2]) + tokens.drop(3)).joinToString(" "))
                    continue
                }
            }
            out.add(line)
        }
        return out.joinToString("\n")
    }

    /** 將 `41 0C 1A F8` 這類回應拆成位元組陣列；格式錯誤回傳 null */
    private fun parseBytes(hex: String): IntArray? {
        val tokens = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val bytes = try {
            IntArray(tokens.size) { tokens[it].toInt(16) }
        } catch (_: NumberFormatException) {
            return null
        }
        return stripElmHeader(bytes)
    }

    // ===== ISO 9141-2 / ISO 14230-4（KWP）回應 header 剝離 =====

    /** KWP/ISO9141 回應 header 的目標位址（byte1），0x6B 最常見 */
    private val ELM_HEADER_TARGETS = setOf(
        0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F,
        0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xED, 0xEE, 0xEF,
        0xF1,
    )

    /** KWP/ISO9141 回應 header 的源位址（byte2），0x10 最常見 */
    private val ELM_HEADER_SOURCES = setOf(
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F,
        0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,
        0xF1, 0x68, 0x6B,
    )

    /**
     * 剝離 ELM327 在 ISO 9141-2 / ISO 14230-4（KWP）下回應的 3 位元組 header。
     * 原始 frame 為 `<長度欄位> <目標位址> <源位址> <資料…>`，
     * 例：`48 6B 10 41 0C 1A F8`（長度 0x48、目標 0x6B、源 0x10）。
     * 山寨 ELM327（v1.5）常無法以 ATH0 關閉此 header，故於解析前剝除。
     * 長度欄位依資料長度而異（0x40–0x5F），故以「長度欄位＋目標位址＋源位址」
     * 組合判斷，並要求剝離後首 byte 為 OBD mode echo（0x40–0x5F），
     * 避免誤剝離無 header 的 mode 05–0B 回應（其 bytes[1] 為 PID/TID，不會落在目標位址）。
     */
    private fun stripElmHeader(bytes: IntArray): IntArray {
        if (bytes.size < 4) return bytes
        if (bytes[0] !in 0x40..0x5F) return bytes
        if (bytes[1] !in ELM_HEADER_TARGETS) return bytes
        if (bytes[2] !in ELM_HEADER_SOURCES) return bytes
        if (bytes[3] !in 0x40..0x5F) return bytes
        return bytes.copyOfRange(3, bytes.size)
    }

    /** 判別字串 tokens 是否為 KWP/ISO9141 回應 header（供 parseMode01Batch 逐行剝離） */
    private fun isElmHeaderTokens(tokens: List<String>): Boolean {
        if (tokens.size < 4) return false
        return try {
            val b0 = tokens[0].toInt(16)
            val b1 = tokens[1].toInt(16)
            val b2 = tokens[2].toInt(16)
            val b3 = tokens[3].toInt(16)
            b0 in 0x40..0x5F &&
                b1 in ELM_HEADER_TARGETS &&
                b2 in ELM_HEADER_SOURCES &&
                b3 in 0x40..0x5F
        } catch (_: NumberFormatException) {
            false
        }
    }

    /**
     * 以 `01 00` 探測回應推斷協定族（ATDPN 回 '?' / 空白時的兜底）。
     * 回傳對應的 ATDPN 協定編號：5 = ISO 14230-4 KWP、6 = ISO 15765-4 CAN 11/500、0 = 無法判斷。
     */
    fun inferProtocolFromProbe(probeResponse: String): Int {
        val tokens = probeResponse.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return 0
        if (isElmHeaderTokens(tokens)) return 5
        val first = tokens[0].toIntOrNull(16) ?: return 0
        if (first == 0x41) return 6
        return 0
    }

    // ===== UDS 負回應（NRC）解讀 =====

    /** ISO 14229 負回應碼（NRC）含義對照；未定義的回傳 null */
    val negativeResponseMessages: Map<Int, String> = mapOf(
        0x00 to "成功（正回應）",
        0x10 to "一般拒絕",
        0x11 to "服務不支援",
        0x12 to "子功能不支援",
        0x13 to "不正確的訊息長度或格式",
        0x14 to "回應長度超出",
        0x21 to "忙碌，請重複請求",
        0x22 to "條件不正確",
        0x24 to "請求序列錯誤",
        0x25 to "子網路無回應",
        0x26 to "失敗防止條件未滿足",
        0x31 to "請求超出範圍",
        0x33 to "安全存取被拒絕",
        0x34 to "認證失敗",
        0x35 to "無效金鑰",
        0x36 to "超過嘗試次數",
        0x37 to "延遲時間未到",
        0x70 to "上傳下載不接受",
        0x71 to "傳輸資料中斷",
        0x72 to "一般程式設計失敗",
        0x73 to "不正確的區塊序號計數",
        0x78 to "請求正確收到，回應暫緩",
        0x7E to "子功能於現行工作階段不支援",
        0x7F to "服務於現行工作階段不支援",
        0x81 to "超過最大工作階段數",
        0x82 to "服務不支援",
        0x83 to "身分存取被鎖定",
        0x84 to "有效憑證已到期",
    )

    /**
     * 從回應解析 UDS 負回應碼（NRC）。回應格式為 `7F <service> <NRC>`（可帶 header）。
     * 若指定 [service]，僅接受該 service 的負回應；未指定則取第一個 `7F xx xx` 三元組。
     * 找不到負回應回傳 null。
     */
    fun negativeResponseCode(hexResponse: String, service: Int? = null): Int? {
        val lines = hexResponse.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.contains("NO DATA", ignoreCase = true)) continue
            val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            for (i in 0..tokens.size - 3) {
                if (!tokens[i].equals("7F", ignoreCase = true)) continue
                val svc = tokens[i + 1].toIntOrNull(16) ?: continue
                val nrc = tokens[i + 2].toIntOrNull(16) ?: continue
                if (service != null && svc != service) continue
                return nrc
            }
        }
        return null
    }

    /** 依 NRC 回傳中文含義；未定義時回傳 null */
    fun negativeResponseMessage(nrc: Int): String? = negativeResponseMessages[nrc]

    /**
     * 判斷回應是否為「無資料或負回應」（移植 Car Scanner IsNoDataOrNegativeResponse）。
     * 空回應或含 NO DATA → true；含具體負回應（非 0x00）→ true；成功 → false。
     */
    fun isNoDataOrNegativeResponse(hexResponse: String?, service: Int? = null): Boolean {
        if (hexResponse.isNullOrBlank()) return true
        if (hexResponse.contains("NO DATA", ignoreCase = true)) return true
        val code = negativeResponseCode(hexResponse, service)
        return code != null && code != 0x00
    }
}

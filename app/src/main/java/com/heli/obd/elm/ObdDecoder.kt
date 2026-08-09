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

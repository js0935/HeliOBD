package com.heli.obd.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdDecoderTest {

    // ===== ISO 14230-4 (KWP) / ISO 9141-2 帶 3-byte header 的回應 =====

    @Test
    fun `rpm 剝離 KWP header`() {
        assertEquals(1726, ObdDecoder.rpm("48 6B 10 41 0C 1A F8"))
    }

    @Test
    fun `rpm 剝離 KWP header 且長度欄位依資料變動`() {
        assertEquals(1726, ObdDecoder.rpm("44 6B 10 41 0C 1A F8"))
    }

    @Test
    fun `rpm 無 header（CAN 協定）不受影響`() {
        assertEquals(1726, ObdDecoder.rpm("41 0C 1A F8"))
    }

    @Test
    fun `speed 剝離 KWP header`() {
        assertEquals(0, ObdDecoder.speed("48 6B 10 41 0D 00"))
        assertEquals(72, ObdDecoder.speed("48 6B 10 41 0D 48"))
    }

    @Test
    fun `水溫剝離 KWP header`() {
        assertEquals(30, ObdDecoder.coolantTemp("48 6B 10 41 05 46"))
    }

    @Test
    fun `長期燃油修正剝離 KWP header`() {
        assertEquals(0f, ObdDecoder.fuelTrimLong("48 6B 10 41 07 80") ?: -1f)
        assertEquals(-3.125f, ObdDecoder.fuelTrimLong("48 6B 10 41 07 7C"))
    }

    @Test
    fun `環境溫度剝離 KWP header`() {
        assertEquals(30, ObdDecoder.ambientTemp("48 6B 10 41 46 46"))
        assertEquals(30, ObdDecoder.ambientTemp("41 46 46"))
    }

    @Test
    fun `機油溫度剝離 KWP header`() {
        assertEquals(60, ObdDecoder.oilTemp("48 6B 10 41 5C 64"))
        assertEquals(60, ObdDecoder.oilTemp("41 5C 64"))
    }

    @Test
    fun `supportedPidMask 剝離 KWP header`() {
        assertEquals(0xBE3FA813L, ObdDecoder.supportedPidMask("48 6B 10 41 00 BE 3F A8 13"))
    }

    @Test
    fun `dtcList 剝離 KWP header`() {
        assertEquals(
            listOf("P0103", "P0002", "P0011"),
            ObdDecoder.dtcList("48 6B 10 43 01 03 00 02 00 11"),
        )
    }

    @Test
    fun `freezeDtc 剝離 KWP header`() {
        assertEquals("P0103", ObdDecoder.freezeDtc("48 6B 10 41 02 01 03"))
    }

    @Test
    fun `mode01 批次多行剝離 KWP header`() {
        val raw = "48 6B 10 41 0C 1A F8\n48 6B 10 41 0D 00\n48 6B 10 41 05 46"
        val parsed = ObdDecoder.parseMode01Batch(raw)
        assertEquals(setOf("0C", "0D", "05"), parsed.keys)
        assertEquals("41 0C 1A F8", parsed["0C"])
        assertEquals("41 05 46", parsed["05"])
    }

    // ===== 不誤剝離無 header 的回應（mode 05–0B）=====

    @Test
    fun `vin 剝離 KWP header`() {
        val vin = ObdDecoder.vin(
            "48 6B 10 49 02 01 4D 4F 54 4F 44 49 41 47 31 32 33 34 35 36 37 38 39 30"
        )
        assertEquals("MOTODIAG123456789", vin)
    }

    @Test
    fun `vin 無 header 不被誤剝離`() {
        val vin = ObdDecoder.vin(
            "49 02 01 4D 4F 54 4F 44 49 41 47 31 32 33 34 35 36 37 38 39 30"
        )
        assertEquals("MOTODIAG123456789", vin)
    }

    @Test
    fun `cvn 無 header 不被誤剝離`() {
        assertEquals("01020304", ObdDecoder.cvn("49 0B 01 02 03 04"))
    }

    @Test
    fun `mode08 回應首 byte 0x48 不被誤剝離`() {
        // rawValues 跳過 mode 與 PID echo（前 2 bytes），剩 02 03
        assertEquals(0x02, ObdDecoder.rawValues("48 01 02 03")?.get(0))
        assertEquals(0x03, ObdDecoder.rawValues("48 01 02 03")?.get(1))
    }

    @Test
    fun `rawValues 剝離 UDS 62 回應的 DID`() {
        // `62 <DID高> <DID低> <data>` → 僅回 data
        assertEquals(listOf(0x1A, 0xF8), ObdDecoder.rawValues("62 F4 0C 1A F8")?.toList())
        assertEquals(listOf(0x06, 0x5A), ObdDecoder.rawValues("62 18 01 06 5A")?.toList())
        // 僅 header + 62 + DID、無資料 → 空陣列（非 null）
        assertEquals(0, ObdDecoder.rawValues("62 F4 0C")?.size)
    }

    @Test
    fun `rawValues UDS 62 不影響 mode01 回應`() {
        assertEquals(listOf(0x1A, 0xF8), ObdDecoder.rawValues("41 0C 1A F8")?.toList())
    }

    @Test
    fun `mode01 無 header 回歸`() {
        assertEquals(30, ObdDecoder.coolantTemp("41 05 46"))
        assertEquals(0xBE3FA813L, ObdDecoder.supportedPidMask("41 00 BE 3F A8 13"))
        assertEquals(listOf("P0103", "P0002"), ObdDecoder.dtcList("43 01 03 00 02"))
    }

    @Test
    fun `回應前綴含 48 但非合法位址不剝離`() {
        // bytes[1]=0x10 屬 SOURCES 但非 TARGETS，仍算無 header → 解析為 mode 09 無效 → null
        assertNull(ObdDecoder.cvn("49 10 01 02 03 04"))
    }

    // ===== DTC 系統位元解碼（ISO 15031-6：byte1 bit7-6 = P/C/B/U）=====

    @Test
    fun `DTC 系統位元正確解碼 C 開頭碼`() {
        assertEquals("C0035", ObdDecoder.decodeDtc(0x40, 0x35))
        assertEquals("C0130", ObdDecoder.decodeDtc(0x41, 0x30))
    }

    @Test
    fun `DTC 系統位元正確解碼 B 開頭碼`() {
        assertEquals("B1000", ObdDecoder.decodeDtc(0x90, 0x00))
    }

    @Test
    fun `DTC 系統位元正確解碼 U 開頭碼`() {
        assertEquals("U0100", ObdDecoder.decodeDtc(0xC1, 0x00))
        assertEquals("U0121", ObdDecoder.decodeDtc(0xC1, 0x21))
    }

    @Test
    fun `DTC 系統位元 P 碼不受影響`() {
        assertEquals("P0300", ObdDecoder.decodeDtc(0x03, 0x00))
        assertEquals("P0420", ObdDecoder.decodeDtc(0x04, 0x20))
    }

    @Test
    fun `dtcList 解碼 C 與 U 開頭碼`() {
        assertEquals(listOf("C0035", "U0100"), ObdDecoder.dtcList("43 40 35 C1 00"))
    }

    // ===== 協定回應分析兜底 =====

    @Test
    fun `inferProtocolFromProbe 判 KWP 帶 header`() {
        assertEquals(5, ObdDecoder.inferProtocolFromProbe("48 6B 10 41 00 BE 3F A8 13"))
    }

    @Test
    fun `inferProtocolFromProbe 判 CAN`() {
        assertEquals(6, ObdDecoder.inferProtocolFromProbe("41 00 BE 3F A8 13"))
    }

    @Test
    fun `inferProtocolFromProbe 無法判斷時回 0`() {
        assertEquals(0, ObdDecoder.inferProtocolFromProbe("?"))
        assertEquals(0, ObdDecoder.inferProtocolFromProbe(""))
    }

    // ===== Mode 06 車載監控測試 =====

    @Test
    fun `monitorTests 標準 TestID 依縮放表計算（rpm）`() {
        // TID 01、TestID 07（rpm，m=16383.75）：raw 0x0A8C=2700 → 2700*16383.75/65535 = 675.0
        // min 0x0000=0 → 0.0、max 0x7D00=32000 → 8000.0；675 在範圍內 → 通過
        val tests = ObdDecoder.monitorTests("46 01 07 0A 8C 00 00 7D 00")
        assertEquals(1, tests.size)
        val t = tests[0]
        assertEquals(1, t.tid)
        assertEquals(7, t.testId)
        assertEquals(675.0, t.scaledValue!!, 0.001)
        assertEquals("rpm", t.unit)
        assertEquals(0.0, t.minValue!!, 0.001)
        assertEquals(8000.0, t.maxValue!!, 0.001)
        assertEquals(true, t.passed)
    }

    @Test
    fun `monitorTests 失火計數拆缸號與計數`() {
        // TID 01、TestID 00：0x1A0B → 缸 1、計數 0x0A0B=2571
        val tests = ObdDecoder.monitorTests("46 01 00 1A 0B 00 00 00 00")
        assertEquals(1, tests.size)
        val t = tests[0]
        assertEquals(1, t.cylinder)
        assertEquals(2571L, t.value)
        assertEquals(2571.0, t.scaledValue!!, 0.001)
    }

    @Test
    fun `monitorTests 製造商 TestID 以 signed 解讀並縮放`() {
        // TID 41、TestID 0x82=130（signed，m=0.1）：0xFF38 = -200 → -20.0
        val tests = ObdDecoder.monitorTests("46 41 82 FF 38 00 00 00 00")
        assertEquals(1, tests.size)
        val t = tests[0]
        assertEquals(-20.0, t.scaledValue!!, 0.001)
    }

    @Test
    fun `monitorTests 未收錄縮放表者回 raw 值`() {
        // TestID 0x3A=58 無縮放規格 → scaledValue = raw（0x0100 = 256）
        val tests = ObdDecoder.monitorTests("46 01 3A 01 00 00 00 00 00")
        assertEquals(1, tests.size)
        assertEquals(256.0, tests[0].scaledValue!!, 0.001)
        assertEquals("", tests[0].unit)
    }

    @Test
    fun `monitorTests 多組 TID 合併回應以 46 分隔解析`() {
        // TID 01（失火 rpm）+ TID 21（燃油系統）兩組合併回應
        val tests = ObdDecoder.monitorTests("46 01 07 0A 8C 00 00 7D 00 46 21 07 00 64 00 00 00 00")
        assertEquals(2, tests.size)
        assertEquals(1, tests[0].tid)
        assertEquals(0x21, tests[1].tid)
        assertEquals(25.0, tests[1].scaledValue!!, 0.001)
    }

    @Test
    fun `monitorTests TID 名稱資源對照`() {
        assertEquals(com.heli.obd.R.string.diag_tid_catalyst, ObdConstants.monitorTidNameRes(0x41))
        assertEquals(com.heli.obd.R.string.diag_tid_o2, ObdConstants.monitorTidNameRes(0x91))
        assertEquals(com.heli.obd.R.string.diag_tid_misfire, ObdConstants.monitorTidNameRes(0x01))
        assertEquals(null, ObdConstants.monitorTidNameRes(0x20))
    }

    @Test
    fun `formatScaled 整數與小數格式`() {
        assertEquals("675", ObdDecoder.formatScaled(675.0))
        assertEquals("0.45", ObdDecoder.formatScaled(0.45))
        assertEquals("12.5", ObdDecoder.formatScaled(12.5))
    }

    // ===== ISO-TP 多幀重組 =====

    @Test
    fun `assembleIsoTp 重組含 PCI 多幀`() {
        val response = """
            10 14 49 02 01 31 47 31
            21 59 34 55 55 32 34 32
            22 39 31 30 30 31 39 38
        """.trimIndent()
        val payload = ObdDecoder.assembleIsoTp(response)
        assertEquals(20, payload!!.size)
        assertEquals(0x49, payload[0])
        assertEquals(0x02, payload[1])
        assertEquals(0x31, payload[3])
        assertEquals(0x38, payload[19])
    }

    @Test
    fun `assembleIsoTp 無 PCI 行直接串接`() {
        // 非多幀（如 KWP/ELM 已剝離 PCI）回應：整行視為資料
        val payload = ObdDecoder.assembleIsoTp("49 02 01 31 47 31 59 34 55")
        assertEquals(9, payload!!.size)
        assertEquals(0x49, payload[0])
    }

    @Test
    fun `assembleIsoTp 依宣告長度截斷 padding`() {
        val response = """
            10 14 49 02 01 31 47 31
            21 59 34 55 55 32 34 32
            22 39 31 30 30 31 39 38 00 00
        """.trimIndent()
        assertEquals(20, ObdDecoder.assembleIsoTp(response)!!.size)
    }

    @Test
    fun `vin 多幀含 PCI 回應解出 VIN`() {
        val response = """
            10 14 49 02 01 31 47 31
            21 59 34 55 55 32 34 32
            22 39 31 30 30 31 39 38
        """.trimIndent()
        assertEquals("1G1Y4UU2429100198", ObdDecoder.vin(response))
    }

    @Test
    fun `vin 單幀無 PCI 回應解出 VIN`() {
        assertEquals("1G1Y4UU2429100198", ObdDecoder.vin("49 02 01 31 47 31 59 34 55 55 32 34 32 39 31 30 30 31 39 38"))
    }

    @Test
    fun `vin 多幀含 padding 回應解出 VIN`() {
        val response = """
            10 14 49 02 01 31 47 31
            21 59 34 55 55 32 34 32
            22 39 31 30 30 31 39 38 00 00
        """.trimIndent()
        assertEquals("1G1Y4UU2429100198", ObdDecoder.vin(response))
    }

    @Test
    fun `calibrationId 多幀回應解出`() {
        // 校正 ID 8 字元：4D 4F 54 4F 44 49 41 47 = "MOTODIAG"
        val response = """
            10 0B 49 0A 08 4D 4F 54
            21 4F 44 49 41 47
        """.trimIndent()
        assertEquals("MOTODIAG", ObdDecoder.calibrationId(response))
    }

    // ===== UDS（ISO 14229）回應 normalize =====

    @Test
    fun `normalizeUdsResponse mode01 回應轉 41`() {
        // 22F40C 回應 62 F4 0C 0B B8 → 41 0C 0B B8
        assertEquals(
            "41 0C 0B B8",
            ObdDecoder.normalizeUdsResponse("62 F4 0C 0B B8", targetMode = 0x41),
        )
    }

    @Test
    fun `normalizeUdsResponse mode09 回應轉 49`() {
        // 22F802 回應 62 F8 02 01 31 … → 49 02 01 31 …
        assertEquals(
            "49 02 01 31 47 31 59",
            ObdDecoder.normalizeUdsResponse("62 F8 02 01 31 47 31 59", targetMode = 0x49),
        )
    }

    @Test
    fun `normalizeUdsResponse 多行逐行轉換`() {
        val input = "62 F4 0C 0B B8\n62 F4 0D 00 41"
        val expected = "41 0C 0B B8\n41 0D 00 41"
        assertEquals(expected, ObdDecoder.normalizeUdsResponse(input, targetMode = 0x41))
    }

    @Test
    fun `normalizeUdsResponse 非 UDS 行與非 Fx DID 保留原樣`() {
        val input = "7F 22 12\n62 01 02 03"
        assertEquals(input, ObdDecoder.normalizeUdsResponse(input, targetMode = 0x41))
    }

    @Test
    fun `normalizeUdsResponse 經 assembleIsoTp 後可解出 VIN`() {
        // UDS 多幀：首幀 10 … 資料為 62 F8 02 01 31 47 …
        val response = """
            10 15 62 F8 02 01 31 47
            21 31 59 34 55 55 32 34
            22 32 39 31 30 30 31 39 38
        """.trimIndent()
        val assembled = ObdDecoder.assembleIsoTp(response)
        val normalized = ObdDecoder.normalizeUdsResponse(
            assembled!!.joinToString(" ") { "%02X".format(it) },
            targetMode = 0x49,
        )
        assertEquals("1G1Y4UU2429100198", ObdDecoder.vin(normalized))
    }

    // ===== DTC 狀態位元三制式解讀（UDS/KWP/GM） =====

    @Test
    fun `dtcRecords 2-byte 制式狀態依模式推斷`() {
        assertEquals(
            listOf(ObdDecoder.DtcStatus.CONFIRMED, ObdDecoder.DtcStatus.CONFIRMED),
            ObdDecoder.dtcRecords("43 01 03 00 02").map { it.status },
        )
        assertEquals(
            listOf(ObdDecoder.DtcStatus.PENDING),
            ObdDecoder.dtcRecords("47 01 03", modeByte = 0x47).map { it.status },
        )
        assertEquals(
            listOf(ObdDecoder.DtcStatus.PERMANENT),
            ObdDecoder.dtcRecords("4A 01 03", modeByte = 0x4A).map { it.status },
        )
    }

    @Test
    fun `dtcRecords 2-byte 制式 statusRaw 為 null`() {
        assertEquals(null, ObdDecoder.dtcRecords("43 01 03 00 02").first().statusRaw)
    }

    @Test
    fun `dtcRecords KWP 3-byte 制式 0xFF status 為 UNKNOWN`() {
        val records = ObdDecoder.dtcRecords(
            "43 01 03 FF 00 02 FF 00 11 FF",
            protocolNumber = 5, // KWP fast
        )
        assertEquals(listOf("P0103", "P0002", "P0011"), records.map { it.code })
        assertEquals(listOf(0xFF, 0xFF, 0xFF), records.map { it.statusRaw })
        assertEquals(
            listOf(
                ObdDecoder.DtcStatus.UNKNOWN,
                ObdDecoder.DtcStatus.UNKNOWN,
                ObdDecoder.DtcStatus.UNKNOWN,
            ),
            records.map { it.status },
        )
    }

    @Test
    fun `dtcRecords KWP 3-byte 制式 status 位元解讀`() {
        // bit0 = 目前故障、bit1 = 上次清除後曾發生
        val records = ObdDecoder.dtcRecords(
            "43 01 03 01 00 02 02 00 03 03",
            protocolNumber = 4, // KWP 5-baud
        )
        assertEquals(listOf("P0103", "P0002", "P0003"), records.map { it.code })
        assertEquals(
            listOf(
                ObdDecoder.DtcStatus.CURRENT,
                ObdDecoder.DtcStatus.HISTORY,
                ObdDecoder.DtcStatus.CONFIRMED,
            ),
            records.map { it.status },
        )
    }

    @Test
    fun `dtcRecords UDS 3-byte 制式 status mask 解讀`() {
        // bit0 = test failed、bit2 = pending、bit3 = confirmed
        val records = ObdDecoder.dtcRecords(
            "43 01 03 01 00 02 04 00 03 08",
            protocolNumber = 6, // CAN
        )
        assertEquals(listOf("P0103", "P0002", "P0003"), records.map { it.code })
        assertEquals(
            listOf(
                ObdDecoder.DtcStatus.CURRENT,
                ObdDecoder.DtcStatus.PENDING,
                ObdDecoder.DtcStatus.CONFIRMED,
            ),
            records.map { it.status },
        )
    }

    @Test
    fun `dtcRecords 6 bytes 倍數依協定判 2或3 byte 制式`() {
        // 協定未知 → 標準 2-byte
        assertEquals(listOf("P0103", "P0002"), ObdDecoder.dtcList("43 01 03 00 02"))
        // KWP 串列 → 3-byte（FF 為 status）
        assertEquals(
            listOf("P0103", "P0002"),
            ObdDecoder.dtcList("43 01 03 FF 00 02 FF", protocolNumber = 5),
        )
    }

    @Test
    fun `dtcList 相容 KWP header 剝離`() {
        assertEquals(
            listOf("P0103", "P0002", "P0011"),
            ObdDecoder.dtcList("48 6B 10 43 01 03 FF 00 02 FF 00 11 FF", protocolNumber = 5),
        )
    }

    // ===== UDS 負回應（NRC）解讀 =====

    @Test
    fun `nrc 帶 CAN header 解析`() {
        assertEquals(0x31, ObdDecoder.negativeResponseCode("7E8 03 7F 22 31"))
        assertEquals(0x31, ObdDecoder.negativeResponseCode("03 7F 22 31"))
    }

    @Test
    fun `nrc 指定 service 過濾`() {
        assertEquals(0x31, ObdDecoder.negativeResponseCode("7F 22 31", service = 0x22))
        assertNull(ObdDecoder.negativeResponseCode("7F 10 31", service = 0x22))
    }

    @Test
    fun `nrc 多行回應取第一個`() {
        assertEquals(0x12, ObdDecoder.negativeResponseCode("41 0C 1A F8\n7F 01 12"))
    }

    @Test
    fun `nrc 找不到回傳 null`() {
        assertNull(ObdDecoder.negativeResponseCode("41 0C 1A F8"))
        assertNull(ObdDecoder.negativeResponseCode(""))
        assertNull(ObdDecoder.negativeResponseCode("62 F4 0C 1A F8"))
    }

    @Test
    fun `nrc 含義對照`() {
        assertEquals("請求超出範圍", ObdDecoder.negativeResponseMessage(0x31))
        assertEquals("安全存取被拒絕", ObdDecoder.negativeResponseMessage(0x33))
        assertEquals("服務不支援", ObdDecoder.negativeResponseMessage(0x11))
        assertNull(ObdDecoder.negativeResponseMessage(0x99))
    }

    @Test
    fun `isNoDataOrNegativeResponse 判定`() {
        assertEquals(true, ObdDecoder.isNoDataOrNegativeResponse(null))
        assertEquals(true, ObdDecoder.isNoDataOrNegativeResponse(""))
        assertEquals(true, ObdDecoder.isNoDataOrNegativeResponse("NO DATA"))
        assertEquals(true, ObdDecoder.isNoDataOrNegativeResponse("7F 22 31"))
        assertEquals(false, ObdDecoder.isNoDataOrNegativeResponse("41 0C 1A F8"))
        // 指定 service 時，其他 service 的負回應不視為本次失敗
        assertEquals(false, ObdDecoder.isNoDataOrNegativeResponse("7F 10 31", service = 0x22))
    }
}

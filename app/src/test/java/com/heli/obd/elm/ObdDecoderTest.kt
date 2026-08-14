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
}

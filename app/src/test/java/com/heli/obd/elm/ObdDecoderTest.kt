/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDecoderTest {

    // ===== 多 PID 合併指令批次回應 =====

    @Test
    fun `parseMode01Batch 拆解多行回應`() {
        val raw = "41 0C 1A F8\n41 0D 00 5A\n41 05 82"
        val map = ObdDecoder.parseMode01Batch(raw)
        assertEquals(3, map.size)
        assertEquals("41 0C 1A F8", map["0C"])
        assertEquals("41 0D 00 5A", map["0D"])
        assertEquals("41 05 82", map["05"])
    }

    @Test
    fun `parseMode01Batch 忽略雜訊與空行`() {
        val raw = "\nSEARCHING...\nBUS INIT\n41 0C 1A F8\nNO DATA\n"
        val map = ObdDecoder.parseMode01Batch(raw)
        assertEquals(mapOf("0C" to "41 0C 1A F8"), map)
    }

    @Test
    fun `parseMode01Batch 空字串回傳空表`() {
        assertTrue(ObdDecoder.parseMode01Batch("").isEmpty())
        assertTrue(ObdDecoder.parseMode01Batch("SEARCHING...").isEmpty())
    }

    @Test
    fun `parseMode01Batch 大小寫 PID 統一為大寫`() {
        val map = ObdDecoder.parseMode01Batch("41 0c 1A F8")
        assertEquals("41 0c 1A F8", map["0C"])
    }

    @Test
    fun `parseMode01Batch 支援凍結框 mode 02 首字節`() {
        val raw = "42 05 82\n42 0C 1A F8"
        val map = ObdDecoder.parseMode01Batch(raw, modeByte = 0x42)
        assertEquals(2, map.size)
        assertEquals("42 05 82", map["05"])
        assertEquals("42 0C 1A F8", map["0C"])
    }

    @Test
    fun `parseMode01Batch 不同首字節不互相混入`() {
        val raw = "41 0C 1A F8\n42 05 82"
        assertEquals(1, ObdDecoder.parseMode01Batch(raw).size)
        assertEquals(1, ObdDecoder.parseMode01Batch(raw, modeByte = 0x42).size)
    }

    // ===== 新增常用 PID 解碼 =====

    @Test
    fun `manifoldPressure PID 0B`() {
        assertEquals(101, ObdDecoder.manifoldPressure("41 0B 65")?.toInt())
    }

    @Test
    fun `timingAdvance PID 0E`() {
        // A/2 - 64；A=0xA0(160) → 160/2-64 = 16
        assertEquals(16f, ObdDecoder.timingAdvance("41 0E A0"))
        assertEquals(10f, ObdDecoder.timingAdvance("41 0E 94"))
    }

    @Test
    fun `throttlePosition PID 11`() {
        // A*100/255；A=0x80(128) → 128*100/255 = 50
        assertEquals(50, ObdDecoder.throttlePosition("41 11 80"))
        assertEquals(100, ObdDecoder.throttlePosition("41 11 FF"))
    }

    @Test
    fun `fuelLevel PID 2F`() {
        assertEquals(50, ObdDecoder.fuelLevel("41 2F 80"))
    }

    @Test
    fun `moduleVoltage PID 42`() {
        // (A*256+B)/1000；0x15 0xDC = 5596 → 5.596V
        assertEquals(5.596f, ObdDecoder.moduleVoltage("41 42 15 DC") ?: 0f, 1e-4f)
    }

    @Test
    fun `新 PID 解碼對格式錯誤回傳 null`() {
        assertNull(ObdDecoder.manifoldPressure(""))
        assertNull(ObdDecoder.manifoldPressure("41 0B"))
        assertNull(ObdDecoder.moduleVoltage("41 42 15"))
        assertNull(ObdDecoder.throttlePosition("xx"))
    }

    // ===== Mode 09 車輛資訊 =====

    @Test
    fun `ecuName mode 09 PID 0D 解碼多幀 ASCII`() {
        // 49 0D 01 + ISO-TP 首幀 10 0B + 資料 + 續幀標記 21
        val raw = "49 0D 01 10 0B 4D 4F 54 4F 44 49 41 47 21 45 43 55"
        assertEquals("MOTODIAGECU", ObdDecoder.ecuName(raw))
    }

    @Test
    fun `ecuName 對無前綴或空白回傳 null`() {
        assertNull(ObdDecoder.ecuName("49 02 01 41 42"))
        assertNull(ObdDecoder.ecuName(""))
        assertNull(ObdDecoder.ecuName("49 0D 01"))
    }

    @Test
    fun `calibrationId 共用解析仍正常`() {
        val raw = "49 0A 01 10 0A 43 41 4C 49 44 31 32 33 34"
        assertEquals("CALID1234", ObdDecoder.calibrationId(raw))
    }
}

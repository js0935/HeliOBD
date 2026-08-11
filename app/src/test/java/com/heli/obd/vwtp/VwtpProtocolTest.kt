/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 *
 * VW TP 2.0 協定層測試：預期值以 VwTp20Worker.cs（CarScanner 反編譯）手算對照。
 */
package com.heli.obd.vwtp

import org.junit.Assert.assertEquals
import org.junit.Test

class VwtpProtocolTest {

    private fun b(value: Int): Byte = (value and 0xFF).toByte()

    // ===== CAN identifier =====

    @Test
    fun `frameId 結合 target 高 8 位與 source 低 8 位並加 0x800 基底`() {
        // (0x01 << 8) | 0x00 | 0x800 = 0x0100 | 0x0800 = 0x0900
        assertEquals(0x0900, VwtpProtocol.frameId(0x01, 0x00))
        // (0x01 << 8) | 0x02 | 0x800 = 0x0102 | 0x0800 = 0x0902
        assertEquals(0x0902, VwtpProtocol.frameId(0x01, 0x02))
    }

    // ===== 累加和 =====

    @Test
    fun `checksum 為 8-bit 累加和`() {
        assertEquals(0x06, VwtpProtocol.checksum(byteArrayOf(b(0x01), b(0x02), b(0x03))))
        assertEquals(0x00, VwtpProtocol.checksum(byteArrayOf(b(0xFF), b(0x01))))
        assertEquals(0x34, VwtpProtocol.checksum(byteArrayOf(b(0x01), b(0x01), b(0x01), b(0x01), b(0x20), b(0x10))))
    }

    // ===== 資料區塊組裝 =====

    @Test
    fun `buildBlock 無 CRC 時 header 佈局正確`() {
        val block = VwtpProtocol.buildBlock(0x12, 0x34, 0x56, byteArrayOf(b(0x01), b(0x02)), addCrc = false)
        assertEquals(7, block.size)
        assertEquals(b(0x01), block[0]) // marker
        assertEquals(b(0x02), block[1]) // 資料長度
        assertEquals(b(0x12), block[2]) // 資料索引
        assertEquals(b(0x34), block[3]) // 幀索引
        assertEquals(b(0x56), block[4]) // 指令
        assertEquals(b(0x01), block[5]) // 資料
        assertEquals(b(0x02), block[6])
    }

    @Test
    fun `buildBlock 長度欄為資料長度而非區塊長度`() {
        val block = VwtpProtocol.buildBlock(0, 0, 0, byteArrayOf(b(1), b(2), b(3)))
        assertEquals(b(3), block[1])
    }

    @Test
    fun `buildBlock 附加 CRC 為前段累加和的高低位元組`() {
        // header+data = 01 01 01 01 20 10，sum = 0x34
        val block = VwtpProtocol.buildBlock(1, 1, 0x20, byteArrayOf(b(0x10)))
        assertEquals(8, block.size)
        assertEquals(b(0x00), block[6]) // sum / 256
        assertEquals(b(0x34), block[7]) // sum % 256
    }

    @Test
    fun `buildBlock addCrc 為 false 時不附加 CRC`() {
        val block = VwtpProtocol.buildBlock(0, 0, 0, byteArrayOf(b(1)), addCrc = false)
        assertEquals(6, block.size)
    }

    @Test
    fun `buildBlock 值超過 255 時以低位元組截斷`() {
        val block = VwtpProtocol.buildBlock(0x1FF, 0x100, 0x2FF, byteArrayOf(), addCrc = false)
        assertEquals(b(0xFF), block[2])
        assertEquals(b(0x00), block[3])
        assertEquals(b(0xFF), block[4])
    }
}

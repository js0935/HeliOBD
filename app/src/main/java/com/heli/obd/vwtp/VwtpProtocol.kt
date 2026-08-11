/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.vwtp

/**
 * VW TP 2.0（VAG 專有傳輸層）協定常數與純邏輯。
 *
 * 移植自 CarScanner 反編譯的 VwTp20Worker.cs（domnulvlad 系列）中確定無歧義的部分：
 * - [buildBlock]：資料區塊組裝（marker / 資料長度 / 資料索引 / 幀索引 / 指令 / 資料 / CRC）
 * - [checksum]：8-bit 累加和（VAG TP2.0 的 Summensicherung）
 * - [frameId]：CAN identifier = (target << 8) | source | 0x800
 *
 * 反編譯來源中 FrameBuild / FrameParse 的多幀分段邏輯含反編譯錯誤（if/else 分支相同、
 * 0x82 覆寫長度欄），不可作為移植依據，故不納入；多幀傳輸留待有硬體時以實測補齊。
 */
object VwtpProtocol {

    const val PACKET_MARKER = 0x01
    const val HEADER_LEN = 5
    const val CRC_LEN = 2
    const val CAN_BASE_ID = 0x800

    /** CAN identifier：target 高 8 位、source 低 8 位、加 0x800 基底。 */
    fun frameId(target: Int, source: Int): Int =
        (target shl 8) or (source and 0xFF) or CAN_BASE_ID

    /** 8-bit 累加和（VAG TP2.0 的 Summensicherung，溢位截斷）。 */
    fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) {
            sum = (sum + (b.toInt() and 0xFF)) and 0xFF
        }
        return sum
    }

    /**
     * 組裝 TP2.0 資料區塊：
     * [0] marker 0x01、[1] 資料長度、[2] 資料索引、[3] 幀索引、[4] 指令，
     * 其後為資料；addCrc 時尾端附加 2 bytes（sum/256、sum%256，sum 為前段累加和）。
     */
    fun buildBlock(
        dataIndex: Int,
        frameIndex: Int,
        cmd: Int,
        data: ByteArray,
        addCrc: Boolean = true,
    ): ByteArray {
        val len = HEADER_LEN + data.size + if (addCrc) CRC_LEN else 0
        val block = ByteArray(len)
        block[0] = PACKET_MARKER.toByte()
        block[1] = data.size.toByte()
        block[2] = (dataIndex and 0xFF).toByte()
        block[3] = (frameIndex and 0xFF).toByte()
        block[4] = (cmd and 0xFF).toByte()
        data.copyInto(block, HEADER_LEN)
        if (addCrc) {
            val sum = checksum(block.copyOf(len - CRC_LEN))
            block[len - 2] = (sum / 256).toByte()
            block[len - 1] = (sum % 256).toByte()
        }
        return block
    }
}

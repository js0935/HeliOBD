/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 *
 * VW TP 2.0 會話層測試：預期值以 VWTPECU.cs / VWTPManager.cs 反編譯內容對照。
 */
package com.heli.obd.vwtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VwtpSessionTest {

    // ===== VwtpTiming（VWTPECU.ConvertCanTimingToMsec）=====

    @Test
    fun `decodeCanTiming 指數 0 為係數除以 1000`() {
        assertEquals(0, VwtpTiming.decodeCanTiming(0x00))
        assertEquals(0, VwtpTiming.decodeCanTiming(0x01)) // 1/1000 整數除法 = 0
    }

    @Test
    fun `decodeCanTiming 指數 1 為係數毫秒`() {
        assertEquals(1, VwtpTiming.decodeCanTiming(0x41))
        assertEquals(63, VwtpTiming.decodeCanTiming(0x7F)) // 0b0111_1111
    }

    @Test
    fun `decodeCanTiming 指數 2 為係數乘 10`() {
        assertEquals(20, VwtpTiming.decodeCanTiming(0x82)) // 0b1000_0010
    }

    @Test
    fun `decodeCanTiming 指數 3 為係數乘 100`() {
        assertEquals(100, VwtpTiming.decodeCanTiming(0xC1))
        assertEquals(6300, VwtpTiming.decodeCanTiming(0xFF))
    }

    // ===== VwtpFrameClassifier（VWTPECU.IsDataFrameVWTP / IsEndOfMessage / IsAckRequiredForDataFrame）=====

    @Test
    fun `isDataFrame 高半位元組 0-3 為真`() {
        assertTrue(VwtpFrameClassifier.isDataFrame(0x00))
        assertTrue(VwtpFrameClassifier.isDataFrame(0x10))
        assertTrue(VwtpFrameClassifier.isDataFrame(0x20))
        assertTrue(VwtpFrameClassifier.isDataFrame(0x30))
    }

    @Test
    fun `isDataFrame 高半位元組 4 以上為假`() {
        assertEquals(false, VwtpFrameClassifier.isDataFrame(0x40))
        assertEquals(false, VwtpFrameClassifier.isDataFrame(0x80))
        assertEquals(false, VwtpFrameClassifier.isDataFrame(0xF0))
    }

    @Test
    fun `isEndOfMessage 僅 bit 0x10 置位為真`() {
        assertTrue(VwtpFrameClassifier.isEndOfMessage(0x10))
        assertTrue(VwtpFrameClassifier.isEndOfMessage(0x30))
        assertEquals(false, VwtpFrameClassifier.isEndOfMessage(0x00))
        assertEquals(false, VwtpFrameClassifier.isEndOfMessage(0x20))
    }

    @Test
    fun `isAckRequired bit 0x20 未置位為真`() {
        assertTrue(VwtpFrameClassifier.isAckRequired(0x00))
        assertTrue(VwtpFrameClassifier.isAckRequired(0x10))
        assertEquals(false, VwtpFrameClassifier.isAckRequired(0x20))
        assertEquals(false, VwtpFrameClassifier.isAckRequired(0x30))
    }

    // ===== VwtpRequestBuilder（VWTPECU.SendRequest 字串組裝）=====

    @Test
    fun `buildFrames 單幀 5 bytes 內`() {
        // "1" + counter(1 hex) + 長度(4 hex) + 指令
        assertEquals(listOf("1000022106"), VwtpRequestBuilder.buildFrames("2106", 0))
        assertEquals(listOf("1100022106"), VwtpRequestBuilder.buildFrames("2106", 1))
    }

    @Test
    fun `buildFrames 多幀 6 bytes 拆首幀 5 + 末幀 1`() {
        assertEquals(
            listOf("2000060102030405", "1106"),
            VwtpRequestBuilder.buildFrames("010203040506", 0),
        )
    }

    @Test
    fun `buildFrames 多幀 10 bytes 拆首幀 5 + 末幀 5`() {
        assertEquals(
            listOf("20000A0102030405", "11060708090A"),
            VwtpRequestBuilder.buildFrames("0102030405060708090A", 0),
        )
    }

    @Test
    fun `buildFrames 多幀 13 bytes 拆三幀含中間幀`() {
        assertEquals(
            listOf("20000D0102030405", "21060708090A0B0C", "120D"),
            VwtpRequestBuilder.buildFrames("0102030405060708090A0B0C0D", 0),
        )
    }

    @Test
    fun `nextPacketCounter 超過 15 歸零`() {
        assertEquals(15, VwtpRequestBuilder.nextPacketCounter(14))
        assertEquals(0, VwtpRequestBuilder.nextPacketCounter(15))
    }

    // ===== VwtpResponseParser（VWTPECU.ELMResponseToCANFrames）=====

    @Test
    fun `parse 空白或 NO DATA 回傳空清單`() {
        assertEquals(emptyList<VwtpCanFrame>(), VwtpResponseParser.parse(""))
        assertEquals(emptyList<VwtpCanFrame>(), VwtpResponseParser.parse("NO DATA"))
    }

    @Test
    fun `parse 多行回應轉為多幀`() {
        val frames = VwtpResponseParser.parse("7E8 07 00 01\r\n7E8 07 00 02")
        assertEquals(2, frames.size)
        assertEquals("7E8", frames[0].canIdHex)
        assertEquals(listOf(0x07, 0x00, 0x01), frames[0].data)
        assertEquals(listOf(0x07, 0x00, 0x02), frames[1].data)
    }

    @Test
    fun `parse 奇數長度資料行跳過`() {
        assertEquals(0, VwtpResponseParser.parse("003106").size) // payload 3 hex 奇數
        assertEquals(1, VwtpResponseParser.parse("0031062").size) // payload 4 hex 偶數
    }

    @Test
    fun `parse 混合幀與雜訊行`() {
        val frames = VwtpResponseParser.parse("1A8A1\nXYZ\n7E8070001")
        assertEquals(2, frames.size)
        assertEquals(listOf(0xA1), frames[0].data)
        assertEquals(listOf(0x07, 0x00, 0x01), frames[1].data)
    }

    // ===== VwtpAddressMap（VWTPManager.UnitToCANAddress / CANAddressToUnit / GetFreeCRAChannel）=====

    @Test
    fun `unitToCan 已知邏輯位址映射`() {
        assertEquals("01", VwtpAddressMap.unitToCan("01"))
        assertEquals("13", VwtpAddressMap.unitToCan("04"))
        assertEquals("31", VwtpAddressMap.unitToCan("05"))
        assertEquals("3A", VwtpAddressMap.unitToCan("08"))
        assertEquals("30", VwtpAddressMap.unitToCan("15"))
        assertEquals("18", VwtpAddressMap.unitToCan("21"))
        assertEquals("54", VwtpAddressMap.unitToCan("23"))
        assertEquals("49", VwtpAddressMap.unitToCan("24"))
        assertEquals("3A", VwtpAddressMap.unitToCan("29"))
    }

    @Test
    fun `unitToCan 未知或空白回傳 null`() {
        assertNull(VwtpAddressMap.unitToCan("ZZ"))
        assertNull(VwtpAddressMap.unitToCan(""))
    }

    @Test
    fun `canToUnit 已知 CAN 位址反向映射`() {
        assertEquals("04", VwtpAddressMap.canToUnit("13"))
        assertEquals("23", VwtpAddressMap.canToUnit("54"))
        assertEquals("21", VwtpAddressMap.canToUnit("18"))
        assertEquals("08", VwtpAddressMap.canToUnit("3A"))
        assertNull(VwtpAddressMap.canToUnit("99"))
    }

    @Test
    fun `映射往返一致`() {
        for (unit in listOf("01", "04", "05", "08", "15", "21", "23", "24")) {
            val can = VwtpAddressMap.unitToCan(unit)
            assertEquals(unit, VwtpAddressMap.canToUnit(can!!))
        }
    }

    @Test
    fun `findFreeChannel 掃描 768 起跳`() {
        assertEquals(768, VwtpAddressMap.findFreeChannel(emptySet()))
        assertEquals(769, VwtpAddressMap.findFreeChannel(setOf("300")))
        assertEquals(768, VwtpAddressMap.findFreeChannel(setOf("301")))
    }

    @Test
    fun `findFreeChannel 全滿回傳 1023`() {
        val all = (768 until 1023).map { it.toString(16).uppercase().padStart(3, '0') }.toSet()
        assertEquals(1023, VwtpAddressMap.findFreeChannel(all))
    }

    // ===== VwtpEcuStateResolver（VWTPECU.CurrentState getter）=====

    @Test
    fun `resolve WaitingForIncomingConnection 逾時 T1 乘 2 轉 Disconnected`() {
        val state = VwtpEcuStateResolver.EcuState.WaitingForIncomingConnection
        assertEquals(state, VwtpEcuStateResolver.resolve(state, elapsedMs = 199, t1Ms = 200))
        assertEquals(
            VwtpEcuStateResolver.EcuState.Disconnected,
            VwtpEcuStateResolver.resolve(state, elapsedMs = 400, t1Ms = 200),
        )
    }

    @Test
    fun `resolve Connected 依活動與被動逾時三階段轉換`() {
        val state = VwtpEcuStateResolver.EcuState.ConnectedWaitingForData
        assertEquals(state, VwtpEcuStateResolver.resolve(state, elapsedMs = 600))
        assertEquals(
            VwtpEcuStateResolver.EcuState.ConnectedWaitingForConnectionTest,
            VwtpEcuStateResolver.resolve(state, elapsedMs = 601),
        )
        assertEquals(
            VwtpEcuStateResolver.EcuState.ConnectedWaitingForConnectionTest,
            VwtpEcuStateResolver.resolve(state, elapsedMs = 1650),
        )
        assertEquals(
            VwtpEcuStateResolver.EcuState.Disconnected,
            VwtpEcuStateResolver.resolve(state, elapsedMs = 1651),
        )
    }

    // ===== VwtpSession 整合（VWTPECU.SendCommand 流程）=====

    private fun sessionWithScript(
        reads: MutableList<String>,
        sends: MutableList<String>,
        nowMs: Long = 1_000_000L,
    ): VwtpSession = VwtpSession(
        send = { sends += it },
        read = { if (reads.isEmpty()) "" else reads.removeAt(0) },
        clock = { nowMs },
    )

    @Test
    fun `sendCommand 完整流程：channel 開通、連線、確認、請求、ACK`() {
        val reads = mutableListOf(
            "OK", "OK", "OK",
            "1A8 00 D0 03 00 01 00 00", // channel 回應：Data[1]=0xD0 → resp=003, req=001
            "1A8 A1 07 41 00 41 00",     // open 回應：Data[0]=0xA1 → block=7, t1=1ms, t3=1ms
            "1A8 A1 00 00 00 00 00",     // A3 確認回應
            "003 10 62 06 01 02",        // request 回應：資料幀 [10,62,06,01,02]
            "",                          // EOM 額外讀
        )
        val sends = mutableListOf<String>()
        val session = sessionWithScript(reads, sends)

        assertEquals("62060102", session.sendCommand("VWTP:02:2106"))
        assertEquals(
            listOf(
                "ATSP6", "ATSH200", "ATST0A",
                "02C000100003011", "A00F8AFF32FF",
                "A3", "1000022106", "B1",
            ),
            sends,
        )
    }

    @Test
    fun `sendCommand 第二次連線保留狀態且 packet counter 遞增`() {
        val reads = mutableListOf(
            "OK", "OK", "OK",
            "1A8 00 D0 03 00 01 00 00",
            "1A8 A1 07 41 00 41 00",
            "1A8 A1 00 00 00 00 00",
            "003 10 62 06 01 02",
            "",
            "003 10 62 06 03 04",
            "",
        )
        val sends = mutableListOf<String>()
        val session = sessionWithScript(reads, sends)

        assertEquals("62060102", session.sendCommand("VWTP:02:2106"))
        assertEquals("62060304", session.sendCommand("VWTP:02:2106"))

        // 第二次不再 channel 開通，直接請求：counter=1 → "1100022106"
        assertEquals(10, sends.size)
        assertEquals("1100022106", sends[8])
        assertEquals("B1", sends[9])
    }

    @Test
    fun `sendCommand 格式不符回傳 NO DATA 且不送任何指令`() {
        val reads = mutableListOf<String>()
        val sends = mutableListOf<String>()
        val session = sessionWithScript(reads, sends)

        assertEquals(VwtpSession.NO_DATA, session.sendCommand("BAD"))
        assertEquals(VwtpSession.NO_DATA, session.sendCommand("OBD:02:2106"))
        assertEquals(VwtpSession.NO_DATA, session.sendCommand("VWTP:02"))
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `channel 開通無回應幀回傳 NO DATA`() {
        val reads = mutableListOf("OK", "OK", "OK", "OK")
        val sends = mutableListOf<String>()
        val session = sessionWithScript(reads, sends)

        assertEquals(VwtpSession.NO_DATA, session.sendCommand("VWTP:02:2106"))
        assertEquals(4, sends.size) // ATSP6 / ATSH200 / ATST0A / channel 請求
    }

    @Test
    fun `currentState 反映連線與超時`() {
        val reads = mutableListOf(
            "OK", "OK", "OK",
            "1A8 00 D0 03 00 01 00 00",
            "1A8 A1 07 41 00 41 00",
            "1A8 A1 00 00 00 00 00",
            "003 10 62 06 01 02",
            "",
        )
        val sends = mutableListOf<String>()
        val session = sessionWithScript(reads, sends)

        assertEquals(VwtpEcuStateResolver.EcuState.Disconnected, session.currentState("02"))
        session.sendCommand("VWTP:02:2106")
        assertEquals(
            VwtpEcuStateResolver.EcuState.ConnectedWaitingForData,
            session.currentState("02", 1_000_000L),
        )
        assertEquals(
            VwtpEcuStateResolver.EcuState.Disconnected,
            session.currentState("02", 1_000_000L + 2000),
        )
    }

    @Test
    fun `getFreeCraChannel 初始回傳 768`() {
        val session = sessionWithScript(mutableListOf(), mutableListOf())
        assertEquals(768, session.getFreeCraChannel())
    }
}

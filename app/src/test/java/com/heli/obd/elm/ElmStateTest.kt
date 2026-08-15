package com.heli.obd.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmStateTest {

    @Test
    fun `ATSP 更新協定編號`() {
        val s = ElmState()
        s.update("ATSP5")
        assertEquals(5, s.protocol)
        s.update("ATSPA0")
        assertEquals(0, s.protocol)
        s.update("ATSP 3")
        assertEquals(3, s.protocol)
    }

    @Test
    fun `ATST 十六進位與毫秒寫法`() {
        val s = ElmState()
        s.update("ATST FF")
        assertEquals(1020, s.atstMs)
        s.update("ATST1000")
        assertEquals(1000, s.atstMs)
        s.update("ATST0A")
        assertEquals(40, s.atstMs)
    }

    @Test
    fun `ATSH 與 ATH 更新`() {
        val s = ElmState()
        s.update("ATSH7E0")
        assertEquals("7E0", s.header)
        s.update("ATH1")
        assertTrue(s.displayHeaders)
        s.update("ATH0")
        assertFalse(s.displayHeaders)
    }

    @Test
    fun `回應格式設定更新`() {
        val s = ElmState()
        s.update("ATE0")
        assertFalse(s.displayEcho)
        s.update("ATL1")
        assertTrue(s.lineFeeds)
        s.update("ATS0")
        assertFalse(s.insertSpaces)
        s.update("ATAL")
        assertTrue(s.allowLongMessages)
    }

    @Test
    fun `CAN 相關設定更新`() {
        val s = ElmState()
        s.update("ATCAF0")
        assertFalse(s.canAutoFormat)
        s.update("ATCFC0")
        assertFalse(s.canFlowControl)
        s.update("ATFCSM1")
        assertEquals(1, s.flowControlMode)
        s.update("ATFCSH7E8")
        assertEquals("7E8", s.flowControlHeader)
    }

    @Test
    fun `ATZ 重設為預設`() {
        val s = ElmState()
        s.update("ATSP5")
        s.update("ATSH7E0")
        s.update("ATE0")
        s.update("ATCAF0")
        s.update("ATCFC0")
        s.update("ATZ")
        assertEquals(0, s.protocol)
        assertEquals("", s.header)
        assertTrue(s.displayEcho)
        assertTrue(s.canAutoFormat)
        assertTrue(s.canFlowControl)
        assertEquals(128, s.atstMs)
    }

    @Test
    fun `非 AT 指令不影響狀態`() {
        val s = ElmState()
        s.update("010C")
        s.update("ATRV")
        assertEquals(0, s.protocol)
        assertEquals(128, s.atstMs)
    }

    @Test
    fun `shouldSkip 依狀態跳過已達成指令`() {
        val s = ElmState()
        assertTrue(s.shouldSkip("ATCFC1")) // 預設已是開
        assertFalse(s.shouldSkip("ATCFC0"))
        s.update("ATCFC0")
        assertTrue(s.shouldSkip("ATCFC0"))
        assertFalse(s.shouldSkip("ATCFC1"))
        assertTrue(s.shouldSkip("ATFCSM0")) // 預設 Auto
        s.update("ATFCSM2")
        assertTrue(s.shouldSkip("ATFCSM2"))
        assertFalse(s.shouldSkip("ATFCSM0"))
        assertTrue(s.shouldSkip("ATL1"))
        assertTrue(s.shouldSkip("ATS1"))
        assertTrue(s.shouldSkip("ATH0"))
        assertFalse(s.shouldSkip("ATE0")) // 預設 echo 開著，尚未達成關閉
        s.update("ATE0")
        assertTrue(s.shouldSkip("ATE0"))
    }

    @Test
    fun `shouldSkip ATSP 與 ATSH`() {
        val s = ElmState()
        assertTrue(s.shouldSkip("ATSP0")) // 預設自動
        s.update("ATSP6")
        assertTrue(s.shouldSkip("ATSP6"))
        assertFalse(s.shouldSkip("ATSP5"))
        s.update("ATSH7E0")
        assertTrue(s.shouldSkip("ATSH7E0"))
        assertFalse(s.shouldSkip("ATSH7E1"))
    }

    @Test
    fun `shouldSkip 非去重指令回 false`() {
        val s = ElmState()
        assertFalse(s.shouldSkip("ATRV"))
        assertFalse(s.shouldSkip("010C"))
        assertFalse(s.shouldSkip("ATSH")) // 無參數的 ATSH 不回跳過
    }
}

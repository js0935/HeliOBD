package com.heli.obd.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdConstantsTest {

    @Test
    fun `KWP response pending 判定`() {
        assertTrue(ObdConstants.isKwpResponsePending("7F 03 78"))
        assertTrue(ObdConstants.isKwpResponsePending("7F 07 78"))
        assertTrue(ObdConstants.isKwpResponsePending("7F 0A 78"))
        assertTrue(ObdConstants.isKwpResponsePending(" 7f 03 78 "))
        assertFalse(ObdConstants.isKwpResponsePending("43 01 00 00 00"))
        assertFalse(ObdConstants.isKwpResponsePending("7F 03 10")) // busy repeating，非 pending
        assertFalse(ObdConstants.isKwpResponsePending("7F 03"))    // 長度不足
        assertFalse(ObdConstants.isKwpResponsePending("NO DATA"))
        assertFalse(ObdConstants.isKwpResponsePending(""))
    }

    @Test
    fun `KWP 預設配方非空且皆為合法 AT 指令`() {
        assertTrue(ObdConstants.KWP_INIT_PRESETS.isNotEmpty())
        for (preset in ObdConstants.KWP_INIT_PRESETS) {
            assertTrue("預設 ${preset.label} 無指令", preset.commands.isNotEmpty())
            for (cmd in preset.commands) {
                assertTrue("預設 ${preset.label} 含非 AT 指令 $cmd", cmd.startsWith("AT"))
                assertEquals("預設 ${preset.label} 指令含空格 $cmd", cmd, cmd.replace(" ", ""))
            }
        }
    }

    @Test
    fun `KWP 配方涵蓋各鮑率與定址變體`() {
        val all = ObdConstants.KWP_INIT_PRESETS.joinToString("\n") { it.commands.joinToString(",") }
        assertTrue("缺少 fast init (ATSP5)", all.contains("ATSP5"))
        assertTrue("缺少 5-baud init (ATSP4)", all.contains("ATSP4"))
        assertTrue("缺少 ISO9141 (ATSP3)", all.contains("ATSP3"))
        assertTrue("缺少 10400 baud (ATIB10)", all.contains("ATIB10"))
        assertTrue("缺少 9600 baud (ATIB96)", all.contains("ATIB96"))
        assertTrue("缺少 4800 baud (ATIB48)", all.contains("ATIB48"))
        assertTrue("缺少 init 定址 (ATIIA13)", all.contains("ATIIA13"))
    }
}

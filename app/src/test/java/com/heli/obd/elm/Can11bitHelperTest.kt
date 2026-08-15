package com.heli.obd.elm

import org.junit.Assert.assertEquals
import org.junit.Test

class Can11bitHelperTest {

    @Test
    fun `標準七E零至七E七偏移加八`() {
        assertEquals("7E8", Can11bitHelper.predictResponseHeader("7E0"))
        assertEquals("7E9", Can11bitHelper.predictResponseHeader("7E1"))
        assertEquals("7EF", Can11bitHelper.predictResponseHeader("7E7"))
    }

    @Test
    fun `標準偏移優先於品牌偏移`() {
        assertEquals("7E8", Can11bitHelper.predictResponseHeader("7E0", "Renault"))
        assertEquals("7E8", Can11bitHelper.predictResponseHeader("7E0", "Volkswagen"))
        assertEquals("7E8", Can11bitHelper.predictResponseHeader("7E0", "Peugeot"))
    }

    @Test
    fun `雷諾集團短距與長距偏移`() {
        assertEquals("7A8", Can11bitHelper.predictResponseHeader("7A0", "Renault"))
        assertEquals("7D0", Can11bitHelper.predictResponseHeader("7B0", "Nissan"))
        assertEquals("7D0", Can11bitHelper.predictResponseHeader("7B0", "Dacia"))
    }

    @Test
    fun `三菱偏移加一`() {
        assertEquals("7A1", Can11bitHelper.predictResponseHeader("7A0", "Mitsubishi"))
    }

    @Test
    fun `福斯集團偏移一百零六`() {
        assertEquals("80A", Can11bitHelper.predictResponseHeader("7A0", "Volkswagen"))
        assertEquals("80A", Can11bitHelper.predictResponseHeader("7A0", "Audi"))
        assertEquals("80A", Can11bitHelper.predictResponseHeader("7A0", "Seat"))
    }

    @Test
    fun `標緻雪鐵龍負偏移`() {
        assertEquals("780", Can11bitHelper.predictResponseHeader("7A0", "Peugeot"))
        assertEquals("780", Can11bitHelper.predictResponseHeader("7A0", "Citroen"))
    }

    @Test
    fun `鈴木特殊替換`() {
        assertEquals("6A0", Can11bitHelper.predictResponseHeader("2A0", "Suzuki"))
        assertEquals("7A8", Can11bitHelper.predictResponseHeader("7A0", "Suzuki"))
    }

    @Test
    fun `長城與東風品牌偏移`() {
        assertEquals("7E0", Can11bitHelper.predictResponseHeader("7A0", "Haval"))
        assertEquals("820", Can11bitHelper.predictResponseHeader("7A0", "Isuzu"))
    }

    @Test
    fun `無品牌與品牌大小寫不敏感`() {
        assertEquals("7A8", Can11bitHelper.predictResponseHeader("7A0"))
        assertEquals("7A8", Can11bitHelper.predictResponseHeader("7a0", " toyota "))
    }

    @Test
    fun `非法輸入回傳空字串`() {
        assertEquals("", Can11bitHelper.predictResponseHeader("XYZ"))
        assertEquals("", Can11bitHelper.predictResponseHeader(""))
    }
}

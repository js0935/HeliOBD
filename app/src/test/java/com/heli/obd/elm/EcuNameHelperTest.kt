package com.heli.obd.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EcuNameHelperTest {

    @Test
    fun `通用映射七E八引擎七E九變速箱`() {
        assertEquals("引擎", EcuNameHelper.ecuNameFor("7E8"))
        assertEquals("變速箱", EcuNameHelper.ecuNameFor("7E9"))
    }

    @Test
    fun `通用映射優先於品牌`() {
        assertEquals("引擎", EcuNameHelper.ecuNameFor("7E8", "Volkswagen"))
        assertEquals("變速箱", EcuNameHelper.ecuNameFor("7E9", "Nissan"))
    }

    @Test
    fun `無品牌非通用 header 回 null`() {
        assertNull(EcuNameHelper.ecuNameFor("7F1"))
        assertNull(EcuNameHelper.ecuNameFor("7EF"))
    }

    @Test
    fun `福斯集團對照`() {
        assertEquals("ABS", EcuNameHelper.ecuNameFor("77D", "Volkswagen"))
        assertEquals("安全氣囊", EcuNameHelper.ecuNameFor("77F", "Audi"))
        assertEquals("儀表板", EcuNameHelper.ecuNameFor("77E", "Seat"))
        assertEquals("空調", EcuNameHelper.ecuNameFor("7B0", "Skoda"))
    }

    @Test
    fun `寶馬以回應 header 後兩碼對照`() {
        assertEquals("引擎", EcuNameHelper.ecuNameFor("70B", "BMW"))
        assertEquals("變速箱", EcuNameHelper.ecuNameFor("718", "Mini"))
        assertEquals("安全氣囊", EcuNameHelper.ecuNameFor("701", "BMW"))
        assertNull(EcuNameHelper.ecuNameFor("7C0", "BMW"))
    }

    @Test
    fun `現代與起亞對照`() {
        assertEquals("四輪驅動", EcuNameHelper.ecuNameFor("7ED", "Hyundai"))
        assertEquals("TPMS", EcuNameHelper.ecuNameFor("7A8", "Kia"))
        assertEquals("安全氣囊", EcuNameHelper.ecuNameFor("7DA", "Genesis"))
    }

    @Test
    fun `豐田與凌志對照`() {
        assertEquals("ABS", EcuNameHelper.ecuNameFor("7B8", "Toyota"))
        assertEquals("雷達巡航控制", EcuNameHelper.ecuNameFor("799", "Lexus"))
    }

    @Test
    fun `日產雷諾對照`() {
        assertEquals("混合動力電池", EcuNameHelper.ecuNameFor("7ED", "Nissan"))
        assertEquals("IPDM", EcuNameHelper.ecuNameFor("76D", "Renault"))
        assertEquals("儀表板", EcuNameHelper.ecuNameFor("763", "Infiniti"))
    }

    @Test
    fun `福特與馬自達對照`() {
        assertEquals("ABS/ESP", EcuNameHelper.ecuNameFor("768", "Ford"))
        assertEquals("儀表板", EcuNameHelper.ecuNameFor("728", "Mazda"))
    }

    @Test
    fun `標緻與沃爾沃對照`() {
        assertEquals("引擎", EcuNameHelper.ecuNameFor("688", "Peugeot"))
        assertEquals("變速箱", EcuNameHelper.ecuNameFor("689", "Citroen"))
        assertEquals("CEM", EcuNameHelper.ecuNameFor("72E", "Volvo"))
    }

    @Test
    fun `速霸陸三菱與路虎對照`() {
        assertEquals("ABS", EcuNameHelper.ecuNameFor("7B8", "Subaru"))
        assertEquals("AWC 四輪驅動", EcuNameHelper.ecuNameFor("7B7", "Mitsubishi"))
        assertEquals("地形反應", EcuNameHelper.ecuNameFor("79A", "Land Rover"))
    }

    @Test
    fun `品牌大小寫與空白不敏感`() {
        assertEquals("ABS", EcuNameHelper.ecuNameFor("77d", " volkswagen "))
        assertEquals("ABS", EcuNameHelper.ecuNameFor("7EB", "Lada"))
    }
}

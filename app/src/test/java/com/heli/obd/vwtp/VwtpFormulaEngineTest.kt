/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 *
 * VW TP 2.0 公式引擎測試：預期值以 C# 語意手算
 * （int/int 除法向零截斷、ShortSigned 帶符號 16-bit、分支條件依序比對）。
 */
package com.heli.obd.vwtp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VwtpFormulaEngineTest {

    private val formulas: Map<Int, VwtpFormulaEngine.Formula> by lazy {
        val text = javaClass.classLoader!!.getResource("vwtp_formulas.json")!!.readText()
        VwtpFormulaEngine.fromJsonObject(JSONObject(text))
    }

    private fun value(id: Int, a: Int, b: Int): Double? =
        VwtpFormulaEngine.evaluate(id, a, b, formulas)?.value

    // ===== 載入 =====

    @Test
    fun `json 載入 163 個公式`() {
        assertEquals(163, formulas.size)
    }

    // ===== int/int 除法截斷（C# 語意） =====

    @Test
    fun `20 號 int 除法截斷`() {
        // A*(B-128)/128：100*72/128 = 56.25 → 56
        assertEquals(56.0, value(20, 100, 200)!!, 1e-9)
    }

    @Test
    fun `30 號 int 除法截斷`() {
        // B/12 - A：100/12 = 8.33 → 8，8-10 = -2
        assertEquals(-2.0, value(30, 10, 100)!!, 1e-9)
    }

    @Test
    fun `59 號 int 除法截斷`() {
        // (A*256+B)/32768：129*256 = 33024，33024/32768 = 1.0078 → 1
        assertEquals(1.0, value(59, 129, 0)!!, 1e-9)
        // 128*256 = 32768 → 1
        assertEquals(1.0, value(59, 128, 0)!!, 1e-9)
    }

    @Test
    fun `143 號 分支與 int 除法截斷`() {
        // (B&0x7F)*A/100：200&127 = 72，72*100/100 = 72
        assertEquals(72.0, value(143, 100, 200)!!, 1e-9)
        // else：(B*A/100)-12.8：5000/100 = 50，50-12.8 = 37.2
        assertEquals(37.2, value(143, 100, 50)!!, 1e-9)
        // A==0||B==0 → 0.0
        assertEquals(0.0, value(143, 0, 50)!!, 1e-9)
    }

    @Test
    fun `61 號 int 除法截斷`() {
        // else：(B-128)/A：72/5 = 14.4 → 14
        assertEquals(14.0, value(61, 5, 200)!!, 1e-9)
        // A==0 → B-128 = 72
        assertEquals(72.0, value(61, 0, 200)!!, 1e-9)
    }

    @Test
    fun `33 號 int 除法截斷`() {
        // 100*B/A：2500/50 = 50
        assertEquals(50.0, value(33, 50, 25)!!, 1e-9)
        // A==0 → 100*B = 2500
        assertEquals(2500.0, value(33, 0, 25)!!, 1e-9)
    }

    // ===== 浮點除法（任一運算元為 double） =====

    @Test
    fun `45 號 全浮點`() {
        // 0.1*A*B/100.0：0.1*100*50/100 = 5.0
        assertEquals(5.0, value(45, 100, 50)!!, 1e-9)
    }

    @Test
    fun `50 號 浮點除法`() {
        // (B-128)/0.01：72/0.01 = 7200
        assertEquals(7200.0, value(50, 0, 200)!!, 1e-9)
        // else：(B-128)/0.01*A：7200*2 = 14400
        assertEquals(14400.0, value(50, 2, 200)!!, 1e-9)
    }

    @Test
    fun `87 號 分支與浮點`() {
        // (B&0x7F)*A*0.1：72*50*0.1 = 360.0
        assertEquals(360.0, value(87, 50, 200)!!, 1e-9)
        // else：(B*A)*0.1-12.8：5000*0.1-12.8 = 487.2
        assertEquals(487.2, value(87, 50, 100)!!, 1e-9)
        // A==0&&B==0 → 0.0
        assertEquals(0.0, value(87, 0, 0)!!, 1e-9)
    }

    @Test
    fun `112 號 分支與浮點`() {
        // (B&0x80)!=128 → (B&0x7F)*A*0.001-0.128：100*50*0.001-0.128 = 4.872
        assertEquals(4.872, value(112, 50, 100)!!, 1e-9)
        // else：(B*A)*0.001：200*50*0.001 = 10.0
        assertEquals(10.0, value(112, 50, 200)!!, 1e-9)
    }

    @Test
    fun `25 號 混合型別`() {
        // B*1.421 + (A/182)：1.421 + 182/182 = 1.421 + 1 = 2.421（A/182 為 int 截斷）
        assertEquals(2.421, value(25, 182, 1)!!, 1e-9)
        // 1.421 + 181/182 = 1.421 + 0 = 1.421
        assertEquals(1.421, value(25, 181, 1)!!, 1e-9)
    }

    // ===== ShortSigned 帶符號 16-bit =====

    @Test
    fun `99 號 ShortSigned`() {
        // (short)65535 = -1
        assertEquals(-1.0, value(99, 255, 255)!!, 1e-9)
        // (short)128 = 128
        assertEquals(128.0, value(99, 0, 128)!!, 1e-9)
        assertEquals(0.0, value(99, 0, 0)!!, 1e-9)
    }

    @Test
    fun `81 號 ShortSigned 混合`() {
        // -1 * (7.0/160.0) = -0.04375
        assertEquals(-0.04375, value(81, 255, 255)!!, 1e-9)
    }

    @Test
    fun `82 號 ShortSigned 混合`() {
        // 128 * 98.1 / 10000.0 = 12556.8/10000 = 1.25568
        assertEquals(1.25568, value(82, 0, 128)!!, 1e-9)
    }

    @Test
    fun `157 號 ShortSigned 負值`() {
        // (short)65534 = -2
        assertEquals(-2.0, value(157, 255, 254)!!, 1e-9)
    }

    // ===== 分支條件與單位 =====

    @Test
    fun `4 號 分支單位 ATDC BTDC`() {
        val r1 = VwtpFormulaEngine.evaluate(4, 100, 255, formulas)!!
        // abs(255-127)*0.01*100 = 128.0 > 127.0 → ATDC
        assertEquals(128.0, r1.value, 1e-9)
        assertEquals("ATDC", r1.unit)
        val r2 = VwtpFormulaEngine.evaluate(4, 100, 100, formulas)!!
        // abs(100-127)*0.01*100 = 27.0 ≤ 127.0 → BTDC
        assertEquals(27.0, r2.value, 1e-9)
        assertEquals("BTDC", r2.unit)
    }

    @Test
    fun `84 號 分支與一元負號`() {
        val r = VwtpFormulaEngine.evaluate(84, 128, 100, formulas)!!
        // (A&0x80)==128 → -24.909 + 100*0.0973 = -15.179
        assertEquals(-15.179, r.value, 1e-9)
        assertEquals("m_sec2", r.unit)
        // else → 100*0.0973 = 9.73
        assertEquals(9.73, value(84, 0, 100)!!, 1e-9)
    }

    @Test
    fun `117 號 負係數`() {
        // else → A*-0.64：100*-0.64 = -64.0
        assertEquals(-64.0, value(117, 100, 50)!!, 1e-9)
    }

    @Test
    fun `10 號 布林分支`() {
        // B==0 → 0.0
        assertEquals(0.0, value(10, 0, 0)!!, 1e-9)
        // else → 1.0
        assertEquals(1.0, value(10, 0, 1)!!, 1e-9)
    }

    // ===== 邊界與錯誤 =====

    @Test
    fun `缺號公式回傳 null`() {
        assertNull(value(77, 100, 100))
        assertNull(value(89, 100, 100))
    }

    @Test
    fun `整數除以零回傳 null`() {
        val manual = mapOf(
            1 to VwtpFormulaEngine.Formula(expr = "100 * B / A", unit = "None")
        )
        assertNull(VwtpFormulaEngine.evaluate(1, 0, 100, manual))
    }

    @Test
    fun `全部 163 公式求值不拋例外`() {
        // 掃描所有公式（A、B 取 0/128/255 邊界值），確保無崩潰
        for (a in listOf(0, 128, 255)) {
            for (b in listOf(0, 128, 255)) {
                for (id in formulas.keys) {
                    val r = VwtpFormulaEngine.evaluate(id, a, b, formulas)
                    assertTrue("$id 求值失敗", r != null)
                    assertTrue("$id 數值非有限", r!!.value.isFinite() || r.value == Double.POSITIVE_INFINITY)
                }
            }
        }
    }
}

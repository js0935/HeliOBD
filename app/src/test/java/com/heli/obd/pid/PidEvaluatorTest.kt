package com.heli.obd.pid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PidEvaluatorTest {

    private val delta = 1e-6

    @Test
    fun `基本四則運算 A B 變數`() {
        assertEquals(4660.0 / 4.0, PidEvaluator.evaluate("(A*256+B)/4", intArrayOf(0x12, 0x34))!!, delta)
    }

    @Test
    fun `小數與除法`() {
        assertEquals(100.0, PidEvaluator.evaluate("A+B/255*100", intArrayOf(0, 255))!!, delta)
    }

    @Test
    fun `A 到 H 全部變數可用`() {
        val raw = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(8.0, PidEvaluator.evaluate("H", raw)!!, delta)
        assertEquals(16.0, PidEvaluator.evaluate("A+H+C+D", raw)!!, delta)
    }

    @Test
    fun `GetBit 與 BIT 取位元`() {
        val raw = intArrayOf(0b0100_0010) // 位元 1 與 6
        assertEquals(1.0, PidEvaluator.evaluate("GetBit(A,6)", raw)!!, delta)
        assertEquals(1.0, PidEvaluator.evaluate("BIT(A,1)", raw)!!, delta)
        assertEquals(0.0, PidEvaluator.evaluate("GetBit(A,3)", raw)!!, delta)
    }

    @Test
    fun `SIGNED 8-bit 有號解讀`() {
        assertEquals(-128.0, PidEvaluator.evaluate("SIGNED(A)", intArrayOf(0x80))!!, delta)
        assertEquals(127.0, PidEvaluator.evaluate("signed(A)", intArrayOf(0x7F))!!, delta)
        assertEquals(-1.0, PidEvaluator.evaluate("Signed(A)", intArrayOf(0xFF))!!, delta)
    }

    @Test
    fun `ShortSigned 16-bit 有號解讀`() {
        assertEquals(-256.0, PidEvaluator.evaluate("ShortSigned(A,B)", intArrayOf(0xFF, 0x00))!!, delta)
        assertEquals(4660.0, PidEvaluator.evaluate("ShortSigned(A,B)", intArrayOf(0x12, 0x34))!!, delta)
    }

    @Test
    fun `INT16 INT24 INT32 組合`() {
        assertEquals(4660.0, PidEvaluator.evaluate("INT16(A,B)", intArrayOf(0x12, 0x34))!!, delta)
        assertEquals(0x123456.toDouble(), PidEvaluator.evaluate("int24(A,B,C)", intArrayOf(0x12, 0x34, 0x56))!!, delta)
        assertEquals(0x12345678.toDouble(), PidEvaluator.evaluate("INT32(A,B,C,D)", intArrayOf(0x12, 0x34, 0x56, 0x78))!!, delta)
    }

    @Test
    fun `FLOAT32 與 FLOAT64 組合`() {
        assertEquals(1.0, PidEvaluator.evaluate("FLOAT32(A,B,C,D)", intArrayOf(0x3F, 0x80, 0x00, 0x00))!!, delta)
        assertEquals(-2.0, PidEvaluator.evaluate("float32(A,B,C,D)", intArrayOf(0xC0, 0x00, 0x00, 0x00))!!, delta)
        assertEquals(
            1.0,
            PidEvaluator.evaluate("FLOAT64(A,B,C,D,E,F,G,H)", intArrayOf(0x3F, 0xF0, 0, 0, 0, 0, 0, 0))!!,
            delta,
        )
    }

    @Test
    fun `位元運算 And Or Xor Not Shl Shr`() {
        assertEquals(48.0, PidEvaluator.evaluate("And(A,B)", intArrayOf(0xF0, 0x30))!!, delta)
        assertEquals(255.0, PidEvaluator.evaluate("Or(A,B)", intArrayOf(0xF0, 0x0F))!!, delta)
        assertEquals(240.0, PidEvaluator.evaluate("Xor(A,B)", intArrayOf(0xFF, 0x0F))!!, delta)
        assertEquals(-1.0, PidEvaluator.evaluate("Not(A)", intArrayOf(0x00))!!, delta)
        assertEquals(-6.0, PidEvaluator.evaluate("Not(A)", intArrayOf(0x05))!!, delta)
        assertEquals(12.0, PidEvaluator.evaluate("Shl(A,B)", intArrayOf(3, 2))!!, delta)
        assertEquals(15.0, PidEvaluator.evaluate("Shr(A,B)", intArrayOf(0xFF, 4))!!, delta)
    }

    @Test
    fun `MAX MIN ABS`() {
        assertEquals(5.0, PidEvaluator.evaluate("MAX(A,B)", intArrayOf(1, 5))!!, delta)
        assertEquals(1.0, PidEvaluator.evaluate("MIN(A,B)", intArrayOf(1, 5))!!, delta)
        assertEquals(3.5, PidEvaluator.evaluate("ABS(-3.5)", intArrayOf(0))!!, delta)
    }

    @Test
    fun `IF 條件以比較運算子判定`() {
        assertEquals(12.0, PidEvaluator.evaluate("IF(A==1, 12, 0)", intArrayOf(1))!!, delta)
        assertEquals(0.0, PidEvaluator.evaluate("IF(A==1, 12, 0)", intArrayOf(2))!!, delta)
        assertEquals(10.0, PidEvaluator.evaluate("IF(GetBit(A,4)==1, 10, 20)", intArrayOf(0x10))!!, delta)
        assertEquals(20.0, PidEvaluator.evaluate("IF(GetBit(A,4)==1, 10, 20)", intArrayOf(0x01))!!, delta)
    }

    @Test
    fun `比較運算子回傳 1 或 0`() {
        assertEquals(1.0, PidEvaluator.evaluate("A>=10", intArrayOf(10))!!, delta)
        assertEquals(0.0, PidEvaluator.evaluate("A>10", intArrayOf(10))!!, delta)
        assertEquals(1.0, PidEvaluator.evaluate("A<=10", intArrayOf(9))!!, delta)
        assertEquals(0.0, PidEvaluator.evaluate("A!=10", intArrayOf(10))!!, delta)
        assertEquals(1.0, PidEvaluator.evaluate("A==B", intArrayOf(5, 5))!!, delta)
    }

    @Test
    fun `邏輯運算 && 與或`() {
        assertEquals(1.0, PidEvaluator.evaluate("A>100 && B<50", intArrayOf(150, 10))!!, delta)
        assertEquals(0.0, PidEvaluator.evaluate("A>100 && B<50", intArrayOf(150, 60))!!, delta)
        assertEquals(1.0, PidEvaluator.evaluate("A>100 || B>50", intArrayOf(10, 60))!!, delta)
        assertEquals(1.0, PidEvaluator.evaluate("!(A==1)", intArrayOf(2))!!, delta)
        assertEquals(0.0, PidEvaluator.evaluate("!(A==1)", intArrayOf(1))!!, delta)
    }

    @Test
    fun `負數與一元運算子`() {
        assertEquals(-255.0, PidEvaluator.evaluate("-A-255", intArrayOf(0))!!, delta)
        assertEquals(5.0, PidEvaluator.evaluate("ABS(-A)", intArrayOf(5))!!, delta)
    }

    @Test
    fun `錯誤輸入回傳 null`() {
        assertNull(PidEvaluator.evaluate("A/0", intArrayOf(5)))
        assertNull(PidEvaluator.evaluate("I", intArrayOf(1)))
        assertNull(PidEvaluator.evaluate("X", intArrayOf(0)))
        assertNull(PidEvaluator.evaluate("(A+1", intArrayOf(1)))
        assertNull(PidEvaluator.evaluate("A+", intArrayOf(1)))
        assertNull(PidEvaluator.evaluate("Unknown(A)", intArrayOf(1)))
        assertNull(PidEvaluator.evaluate("IF(A, 1)", intArrayOf(1)))
        assertNull(PidEvaluator.evaluate("", intArrayOf(1)))
        assertNull(PidEvaluator.evaluate("A=1", intArrayOf(1)))
    }

    @Test
    fun `缺位元組回傳 null`() {
        assertNull(PidEvaluator.evaluate("C", intArrayOf(1, 2)))
        assertNull(PidEvaluator.evaluate("D", intArrayOf(1, 2, 3)))
    }
}

/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.pid

/**
 * 自訂 PID 公式求值器：支援變數 A-H（raw 位元組 0-255）、數字、小數點與 + - * / ( ) 運算，
 * 以及比較（== != < <= > >=）、邏輯（&& || !）、逗號參數與常用函式。使用遞迴下降剖析。
 *
 * 函式（名稱不分大小寫，與 Car Scanner 語法相容）：
 *  - GetBit(x, n) / BIT(x, n)：取 x 第 n 位元（0 或 1）
 *  - SIGNED(x)：x 以 8-bit 有號解讀（-128..127）
 *  - ShortSigned(a, b)：a*256+b 以 16-bit 有號解讀
 *  - INT16(a, b)、INT24(a, b, c)、INT32(a, b, c, d)：大端組合為無號整數
 *  - FLOAT32(a, b, c, d)、FLOAT64(a..h)：大端組合為 IEEE 754 浮點數
 *  - And(a, b)、Or(a, b)、Xor(a, b)、Not(a)：位元運算
 *  - Shl(a, n)、Shr(a, n)：左移／右移
 *  - MAX(a, b)、MIN(a, b)、ABS(x)
 *  - IF(cond, a, b)：cond 非零 → a，否則 b（cond 可使用比較／邏輯運算子）
 *
 * 範例："(A*256+B)/4"、"SIGNED(A)/2"、"IF(GetBit(A,4)==1, 12, 0)"
 */
object PidEvaluator {

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun parse(): Double {
            val v = expr()
            if (pos < tokens.size) throw IllegalArgumentException("意外的符號")
            return v
        }

        private fun expr(): Double {
            var v = comparison()
            while (pos < tokens.size) {
                when (tokens[pos].type) {
                    TokenType.LOGIC_AND -> {
                        pos++
                        v = if (v != 0.0 && comparison() != 0.0) 1.0 else 0.0
                    }
                    TokenType.LOGIC_OR -> {
                        pos++
                        v = if (v != 0.0 || comparison() != 0.0) 1.0 else 0.0
                    }
                    else -> break
                }
            }
            return v
        }

        private fun comparison(): Double {
            var v = additive()
            while (pos < tokens.size) {
                val op = tokens[pos].type
                val ok = when (op) {
                    TokenType.EQ, TokenType.NE, TokenType.LT,
                    TokenType.LE, TokenType.GT, TokenType.GE -> true
                    else -> false
                }
                if (!ok) break
                pos++
                val r = additive()
                v = when (op) {
                    TokenType.EQ -> if (v == r) 1.0 else 0.0
                    TokenType.NE -> if (v != r) 1.0 else 0.0
                    TokenType.LT -> if (v < r) 1.0 else 0.0
                    TokenType.LE -> if (v <= r) 1.0 else 0.0
                    TokenType.GT -> if (v > r) 1.0 else 0.0
                    TokenType.GE -> if (v >= r) 1.0 else 0.0
                    else -> v
                }
            }
            return v
        }

        private fun additive(): Double {
            var v = multiplicative()
            while (pos < tokens.size) {
                when (tokens[pos].type) {
                    TokenType.PLUS -> { pos++; v += multiplicative() }
                    TokenType.MINUS -> { pos++; v -= multiplicative() }
                    else -> break
                }
            }
            return v
        }

        private fun multiplicative(): Double {
            var v = unary()
            while (pos < tokens.size) {
                when (tokens[pos].type) {
                    TokenType.MUL -> { pos++; v *= unary() }
                    TokenType.DIV -> {
                        pos++
                        val d = unary()
                        if (d == 0.0) throw IllegalArgumentException("除以零")
                        v /= d
                    }
                    else -> break
                }
            }
            return v
        }

        private fun unary(): Double {
            if (pos >= tokens.size) throw IllegalArgumentException("意外的符號")
            return when (tokens[pos].type) {
                TokenType.MINUS -> { pos++; -unary() }
                TokenType.PLUS -> { pos++; unary() }
                TokenType.LOGIC_NOT -> {
                    pos++
                    if (unary() != 0.0) 0.0 else 1.0
                }
                else -> atom()
            }
        }

        private fun atom(): Double {
            val t = tokens[pos]
            return when (t.type) {
                TokenType.NUMBER, TokenType.VARIABLE -> {
                    pos++
                    t.value!!
                }
                TokenType.IDENT -> {
                    pos++
                    callFunction(t.text!!)
                }
                TokenType.LPAREN -> {
                    pos++
                    val v = expr()
                    if (pos >= tokens.size || tokens[pos].type != TokenType.RPAREN) {
                        throw IllegalArgumentException("缺少右括號")
                    }
                    pos++
                    v
                }
                else -> throw IllegalArgumentException("意外的符號")
            }
        }

        private fun callFunction(name: String): Double {
            if (pos >= tokens.size || tokens[pos].type != TokenType.LPAREN) {
                throw IllegalArgumentException("函式 $name 缺少左括號")
            }
            pos++
            val args = mutableListOf<Double>()
            if (pos < tokens.size && tokens[pos].type != TokenType.RPAREN) {
                while (true) {
                    args.add(expr())
                    if (pos >= tokens.size) throw IllegalArgumentException("函式 $name 參數不完整")
                    when (tokens[pos].type) {
                        TokenType.COMMA -> { pos++; continue }
                        TokenType.RPAREN -> { pos++; break }
                        else -> throw IllegalArgumentException("函式 $name 參數分隔錯誤")
                    }
                }
            } else {
                pos++
            }
            return applyFunction(name.uppercase(), args)
        }

        private fun applyFunction(name: String, a: List<Double>): Double {
            val arg = { i: Int -> a[i].toLong() }
            return when (name) {
                "GETBIT", "BIT" -> {
                    requireCount(name, a, 2)
                    if (((arg(0).toInt() shr a[1].toInt()) and 1) != 0) 1.0 else 0.0
                }
                "SIGNED" -> {
                    requireCount(name, a, 1)
                    toSigned(arg(0), 8).toDouble()
                }
                "SHORTSIGNED" -> {
                    requireCount(name, a, 2)
                    toSigned((arg(0) shl 8) or arg(1), 16).toDouble()
                }
                "INT16" -> {
                    requireCount(name, a, 2)
                    ((arg(0) shl 8) or arg(1)).toDouble()
                }
                "INT24" -> {
                    requireCount(name, a, 3)
                    ((arg(0) shl 16) or (arg(1) shl 8) or arg(2)).toDouble()
                }
                "INT32" -> {
                    requireCount(name, a, 4)
                    ((arg(0) shl 24) or (arg(1) shl 16) or (arg(2) shl 8) or arg(3)).toDouble()
                }
                "FLOAT32" -> {
                    requireCount(name, a, 4)
                    val bits = ((arg(0) shl 24) or (arg(1) shl 16) or (arg(2) shl 8) or arg(3)).toInt()
                    Float.fromBits(bits).toDouble()
                }
                "FLOAT64" -> {
                    requireCount(name, a, 8)
                    var bits = 0L
                    for (i in 0 until 8) bits = (bits shl 8) or arg(i)
                    Double.fromBits(bits)
                }
                "AND" -> {
                    requireCount(name, a, 2)
                    (arg(0).toInt() and arg(1).toInt()).toDouble()
                }
                "OR" -> {
                    requireCount(name, a, 2)
                    (arg(0).toInt() or arg(1).toInt()).toDouble()
                }
                "XOR" -> {
                    requireCount(name, a, 2)
                    (arg(0).toInt() xor arg(1).toInt()).toDouble()
                }
                "NOT" -> {
                    requireCount(name, a, 1)
                    (arg(0).toInt().inv()).toDouble()
                }
                "SHL" -> {
                    requireCount(name, a, 2)
                    (arg(0).toInt() shl a[1].toInt()).toDouble()
                }
                "SHR" -> {
                    requireCount(name, a, 2)
                    (arg(0).toInt() ushr a[1].toInt()).toDouble()
                }
                "MAX" -> {
                    requireCount(name, a, 2)
                    maxOf(a[0], a[1])
                }
                "MIN" -> {
                    requireCount(name, a, 2)
                    minOf(a[0], a[1])
                }
                "ABS" -> {
                    requireCount(name, a, 1)
                    kotlin.math.abs(a[0])
                }
                "IF" -> {
                    requireCount(name, a, 3)
                    if (a[0] != 0.0) a[1] else a[2]
                }
                else -> throw IllegalArgumentException("不支援的函式：$name")
            }
        }

        private fun requireCount(name: String, a: List<Double>, expect: Int) {
            if (a.size != expect) throw IllegalArgumentException("函式 $name 需要 $expect 個參數")
        }

        private fun toSigned(v: Long, bits: Int): Long {
            val mask = (1L shl bits) - 1
            val sign = 1L shl (bits - 1)
            val masked = v and mask
            return if ((masked and sign) != 0L) masked - (1L shl bits) else masked
        }
    }

    private enum class TokenType {
        NUMBER, VARIABLE, IDENT,
        PLUS, MINUS, MUL, DIV, LPAREN, RPAREN, COMMA,
        EQ, NE, LT, LE, GT, GE, LOGIC_AND, LOGIC_OR, LOGIC_NOT,
    }

    private class Token(val type: TokenType, val value: Double? = null, val text: String? = null)

    /**
     * 計算公式結果。raw 為回應資料位元組（A=raw[0]、B=raw[1]…，最多 H）。
     * 公式語法錯誤、位元組不足或除以零時回傳 null。
     */
    fun evaluate(formula: String, raw: IntArray): Double? {
        val trimmed = formula.trim()
        if (trimmed.isEmpty()) return null
        return try {
            Parser(tokenize(trimmed, raw)).parse()
        } catch (_: Exception) {
            null
        }
    }

    private fun tokenize(text: String, raw: IntArray): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val sb = StringBuilder()
                    while (i < text.length && (text[i].isDigit() || text[i] == '.')) {
                        sb.append(text[i])
                        i++
                    }
                    tokens.add(Token(TokenType.NUMBER, sb.toString().toDouble()))
                }
                c.isLetter() -> {
                    val sb = StringBuilder()
                    while (i < text.length && (text[i].isLetter() || text[i].isDigit())) {
                        sb.append(text[i])
                        i++
                    }
                    val ident = sb.toString()
                    var j = i
                    while (j < text.length && text[j].isWhitespace()) j++
                    if (j < text.length && text[j] == '(') {
                        tokens.add(Token(TokenType.IDENT, text = ident))
                    } else {
                        if (ident.length != 1 || ident[0] !in 'A'..'H') {
                            throw IllegalArgumentException("不支援的識別字：$ident")
                        }
                        val idx = ident[0] - 'A'
                        if (idx >= raw.size) throw IllegalArgumentException("缺少位元組")
                        tokens.add(Token(TokenType.VARIABLE, raw[idx].toDouble()))
                    }
                }
                c == '+' -> { tokens.add(Token(TokenType.PLUS)); i++ }
                c == '-' -> { tokens.add(Token(TokenType.MINUS)); i++ }
                c == '*' -> { tokens.add(Token(TokenType.MUL)); i++ }
                c == '/' -> { tokens.add(Token(TokenType.DIV)); i++ }
                c == '(' -> { tokens.add(Token(TokenType.LPAREN)); i++ }
                c == ')' -> { tokens.add(Token(TokenType.RPAREN)); i++ }
                c == ',' -> { tokens.add(Token(TokenType.COMMA)); i++ }
                c == '=' -> {
                    if (i + 1 < text.length && text[i + 1] == '=') {
                        tokens.add(Token(TokenType.EQ)); i += 2
                    } else throw IllegalArgumentException("單一 '=' 不是有效運算子")
                }
                c == '!' -> {
                    if (i + 1 < text.length && text[i + 1] == '=') {
                        tokens.add(Token(TokenType.NE)); i += 2
                    } else {
                        tokens.add(Token(TokenType.LOGIC_NOT)); i++
                    }
                }
                c == '<' -> {
                    if (i + 1 < text.length && text[i + 1] == '=') {
                        tokens.add(Token(TokenType.LE)); i += 2
                    } else {
                        tokens.add(Token(TokenType.LT)); i++
                    }
                }
                c == '>' -> {
                    if (i + 1 < text.length && text[i + 1] == '=') {
                        tokens.add(Token(TokenType.GE)); i += 2
                    } else {
                        tokens.add(Token(TokenType.GT)); i++
                    }
                }
                c == '&' -> {
                    if (i + 1 < text.length && text[i + 1] == '&') {
                        tokens.add(Token(TokenType.LOGIC_AND)); i += 2
                    } else throw IllegalArgumentException("無法識別的字元：$c")
                }
                c == '|' -> {
                    if (i + 1 < text.length && text[i + 1] == '|') {
                        tokens.add(Token(TokenType.LOGIC_OR)); i += 2
                    } else throw IllegalArgumentException("無法識別的字元：$c")
                }
                else -> throw IllegalArgumentException("無法識別的字元：$c")
            }
        }
        return tokens
    }
}

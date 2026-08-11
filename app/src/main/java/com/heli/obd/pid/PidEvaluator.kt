/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.pid

/**
 * 自訂 PID 公式求值器：支援變數 A/B/C/D（raw 位元組 0-255）、
 * 數字、小數點與 + - * / ( ) 運算。使用遞迴下降剖析。
 *
 * 範例："(A*256+B)/4"、"A+B/255*100"
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
            var v = term()
            while (pos < tokens.size) {
                when (tokens[pos].type) {
                    TokenType.PLUS -> { pos++; v += term() }
                    TokenType.MINUS -> { pos++; v -= term() }
                    else -> break
                }
            }
            return v
        }

        private fun term(): Double {
            var v = factor()
            while (pos < tokens.size) {
                when (tokens[pos].type) {
                    TokenType.MUL -> { pos++; v *= factor() }
                    TokenType.DIV -> {
                        pos++
                        val d = factor()
                        if (d == 0.0) throw IllegalArgumentException("除以零")
                        v /= d
                    }
                    else -> break
                }
            }
            return v
        }

        private fun factor(): Double {
            val t = tokens[pos]
            return when (t.type) {
                TokenType.NUMBER -> { pos++; t.value!! }
                TokenType.VARIABLE -> {
                    pos++
                    t.value!!
                }
                TokenType.LPAREN -> {
                    pos++
                    val v = expr()
                    if (tokens[pos].type != TokenType.RPAREN) throw IllegalArgumentException("缺少右括號")
                    pos++
                    v
                }
                TokenType.MINUS -> { pos++; -factor() }
                else -> throw IllegalArgumentException("意外的符號")
            }
        }
    }

    private enum class TokenType { NUMBER, VARIABLE, PLUS, MINUS, MUL, DIV, LPAREN, RPAREN }

    private class Token(val type: TokenType, val value: Double? = null)

    /**
     * 計算公式結果。raw 為回應資料位元組（A=raw[0]、B=raw[1]…）。
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
                c in "ABCD" -> {
                    val idx = c - 'A'
                    if (idx >= raw.size) throw IllegalArgumentException("缺少位元組")
                    tokens.add(Token(TokenType.VARIABLE, raw[idx].toDouble()))
                    i++
                }
                c == '+' -> { tokens.add(Token(TokenType.PLUS)); i++ }
                c == '-' -> { tokens.add(Token(TokenType.MINUS)); i++ }
                c == '*' -> { tokens.add(Token(TokenType.MUL)); i++ }
                c == '/' -> { tokens.add(Token(TokenType.DIV)); i++ }
                c == '(' -> { tokens.add(Token(TokenType.LPAREN)); i++ }
                c == ')' -> { tokens.add(Token(TokenType.RPAREN)); i++ }
                else -> throw IllegalArgumentException("無法識別的字元：$c")
            }
        }
        return tokens
    }
}

/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.vwtp

import org.json.JSONObject

/**
 * VW TP 2.0（VAG）感測器公式引擎。
 *
 * 公式來源：MotoDiag VWTPFormulaManager.cs 公式表（181 個 case，其中 163 個有定義，
 * 其餘缺號原始 C# 即無 case）。語意完全仿 C#：
 *  - A、B 為 raw 位元組（0-255），一律以 int 參與運算；
 *  - int / int 除法向零截斷（truncate），任一運算元為 double 才做浮點除法；
 *  - & 為位元 AND；abs 保留引數型別；bin16(A,B)=A*256+B；bin8(B)=B；
 *  - ShortSigned(A,B)=(short)(A*256+B)（帶符號 16-bit），以 double 回傳；
 *  - 運算子優先級（高→低）：一元負號 > * / > + - > < > > == != > & > && > ||；
 *  - 分支公式依序比對條件，最後一個 else 為兜底（與 C# if/else 相同）。
 */
object VwtpFormulaEngine {

    /** 單一分支：條件、公式、單位（部分公式各分支單位不同，如 4 號 ATDC/BTDC）。 */
    data class Branch(val cond: String, val expr: String, val unit: String)

    /** 一條感測器公式：直接公式（expr）或分支公式（branches）擇一。 */
    data class Formula(
        val expr: String? = null,
        val unit: String? = null,
        val branches: List<Branch>? = null,
    )

    /** 求值結果：數值（一律 double，與 C# return double 一致）與單位。 */
    data class Result(val value: Double, val unit: String)

    /** 型別追蹤值：仿 C# 的 int / double / bool 三種運算型別。 */
    private sealed class Val {
        class I(val v: Int) : Val()
        class D(val v: Double) : Val()
        class B(val v: Boolean) : Val()
    }

    // ===== 求值入口 =====

    /**
     * 求值單一感測器公式。
     *
     * @param id       公式編號（1-181，缺號原始 C# 即無定義）
     * @param a        第一個 raw 位元組（0-255）
     * @param b        第二個 raw 位元組（0-255）
     * @param formulas 公式表（見 [fromJsonObject]）
     * @return 數值與單位；公式不存在、語法錯誤或整數除以零時回傳 null。
     */
    fun evaluate(id: Int, a: Int, b: Int, formulas: Map<Int, Formula>): Result? {
        val f = formulas[id] ?: return null
        return try {
            when {
                f.expr != null -> Result(evalValue(f.expr, a, b), f.unit ?: "None")
                f.branches != null -> evalBranches(f.branches, a, b)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 依序比對分支條件；「else」分支為兜底（保持 C# if/else 順序）。 */
    private fun evalBranches(branches: List<Branch>, a: Int, b: Int): Result {
        var fallback: Branch? = null
        for (br in branches) {
            if (br.cond == "else") {
                if (fallback == null) fallback = br
                continue
            }
            if (evalCond(br.cond, a, b)) {
                return Result(evalValue(br.expr, a, b), br.unit)
            }
        }
        val els = fallback ?: throw IllegalArgumentException("分支缺少 else")
        return Result(evalValue(els.expr, a, b), els.unit)
    }

    private fun evalValue(expr: String, a: Int, b: Int): Double {
        val v = Parser(tokenize(expr), a, b).parseValue()
        return when (v) {
            is Val.I -> v.v.toDouble()
            is Val.D -> v.v
            is Val.B -> throw IllegalArgumentException("公式不得為布林")
        }
    }

    private fun evalCond(cond: String, a: Int, b: Int): Boolean {
        val v = Parser(tokenize(cond), a, b).parseCond()
        return (v as? Val.B)?.v ?: throw IllegalArgumentException("條件必須為布林")
    }

    // ===== JSON 載入 =====

    /** 將 vwtp_formulas.json 根物件轉為公式表（key 為公式編號）。 */
    fun fromJsonObject(root: JSONObject): Map<Int, Formula> {
        val out = HashMap<Int, Formula>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val id = key.toIntOrNull() ?: continue
            val j = root.optJSONObject(key) ?: continue
            when {
                j.has("expr") -> out[id] = Formula(
                    expr = j.getString("expr"),
                    unit = j.optString("unit", "None"),
                )
                j.has("branches") -> {
                    val arr = j.getJSONArray("branches")
                    val branches = (0 until arr.length()).map { i ->
                        val br = arr.getJSONObject(i)
                        Branch(br.getString("cond"), br.getString("expr"), br.getString("unit"))
                    }
                    out[id] = Formula(branches = branches)
                }
            }
        }
        return out
    }

    // ===== 剖析器 =====

    private enum class TokenType {
        NUMBER, VAR_A, VAR_B, FUNC,
        PLUS, MINUS, MUL, DIV, BAND,
        LPAREN, RPAREN, COMMA,
        EQ, NE, LT, GT, AND_AND, OR_OR, EOF
    }

    private class Token(
        val type: TokenType,
        val text: String = "",
        val numI: Int = 0,
        val numD: Double = 0.0,
    )

    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() -> {
                    // 十六進位（0x7F 等）
                    if (c == '0' && i + 1 < text.length && (text[i + 1] == 'x' || text[i + 1] == 'X')) {
                        var j = i + 2
                        while (j < text.length && (text[j].isDigit() ||
                            text[j] in 'a'..'f' || text[j] in 'A'..'F')) j++
                        tokens.add(Token(TokenType.NUMBER, text.substring(i, j),
                            numI = text.substring(i + 2, j).toInt(16)))
                        i = j
                    } else {
                        var j = i
                        var isDouble = false
                        while (j < text.length && (text[j].isDigit() || text[j] == '.')) {
                            if (text[j] == '.') isDouble = true
                            j++
                        }
                        val literal = text.substring(i, j)
                        if (isDouble) {
                            tokens.add(Token(TokenType.NUMBER, literal, numD = literal.toDouble()))
                        } else {
                            tokens.add(Token(TokenType.NUMBER, literal, numI = literal.toInt()))
                        }
                        i = j
                    }
                }
                c == 'A' || c == 'B' -> {
                    // 單字元變數；若後接字母則為函式名（如 abs 不會以大寫開頭，但保留判斷）
                    if (i + 1 >= text.length || !text[i + 1].isLetter()) {
                        tokens.add(Token(if (c == 'A') TokenType.VAR_A else TokenType.VAR_B, c.toString()))
                        i++
                    } else {
                        var j = i
                        while (j < text.length && text[j].isLetterOrDigit()) j++
                        tokens.add(Token(TokenType.FUNC, text.substring(i, j)))
                        i = j
                    }
                }
                c.isLetter() -> {
                    var j = i
                    while (j < text.length && text[j].isLetterOrDigit()) j++
                    tokens.add(Token(TokenType.FUNC, text.substring(i, j)))
                    i = j
                }
                c == '+' -> { tokens.add(Token(TokenType.PLUS)); i++ }
                c == '-' -> { tokens.add(Token(TokenType.MINUS)); i++ }
                c == '*' -> { tokens.add(Token(TokenType.MUL)); i++ }
                c == '/' -> { tokens.add(Token(TokenType.DIV)); i++ }
                c == '&' -> {
                    if (i + 1 < text.length && text[i + 1] == '&') {
                        tokens.add(Token(TokenType.AND_AND)); i += 2
                    } else {
                        tokens.add(Token(TokenType.BAND)); i++
                    }
                }
                c == '|' -> {
                    if (i + 1 < text.length && text[i + 1] == '|') {
                        tokens.add(Token(TokenType.OR_OR)); i += 2
                    } else throw IllegalArgumentException("無法識別的字元：$c")
                }
                c == '=' -> {
                    if (i + 1 < text.length && text[i + 1] == '=') {
                        tokens.add(Token(TokenType.EQ)); i += 2
                    } else throw IllegalArgumentException("無法識別的字元：$c")
                }
                c == '!' -> {
                    if (i + 1 < text.length && text[i + 1] == '=') {
                        tokens.add(Token(TokenType.NE)); i += 2
                    } else throw IllegalArgumentException("無法識別的字元：$c")
                }
                c == '<' -> { tokens.add(Token(TokenType.LT)); i++ }
                c == '>' -> { tokens.add(Token(TokenType.GT)); i++ }
                c == '(' -> { tokens.add(Token(TokenType.LPAREN)); i++ }
                c == ')' -> { tokens.add(Token(TokenType.RPAREN)); i++ }
                c == ',' -> { tokens.add(Token(TokenType.COMMA)); i++ }
                else -> throw IllegalArgumentException("無法識別的字元：$c")
            }
        }
        tokens.add(Token(TokenType.EOF))
        return tokens
    }

    private class Parser(private val tokens: List<Token>, private val a: Int, private val b: Int) {
        private var pos = 0

        /** 條件入口：整串必須為布林。 */
        fun parseCond(): Val {
            val v = parseOr()
            expectEnd()
            return v
        }

        /** 數值入口：整串必須為數值（允許位元 &）。 */
        fun parseValue(): Val {
            val v = parseBitwise()
            expectEnd()
            return v
        }

        private fun expectEnd() {
            if (tokens[pos].type != TokenType.EOF) throw IllegalArgumentException("意外的符號")
        }

        private fun parseOr(): Val {
            var v = parseAnd()
            while (tokens[pos].type == TokenType.OR_OR) {
                pos++
                val r = parseAnd()
                v = Val.B((v as Val.B).v || (r as Val.B).v)
            }
            return v
        }

        private fun parseAnd(): Val {
            var v = parseBitwise()
            while (tokens[pos].type == TokenType.AND_AND) {
                pos++
                val r = parseBitwise()
                v = Val.B((v as Val.B).v && (r as Val.B).v)
            }
            return v
        }

        private fun parseBitwise(): Val {
            var v = parseEquality()
            while (tokens[pos].type == TokenType.BAND) {
                pos++
                val r = parseEquality()
                v = Val.I((v as Val.I).v and (r as Val.I).v)
            }
            return v
        }

        private fun parseEquality(): Val {
            var v = parseRelational()
            while (tokens[pos].type == TokenType.EQ || tokens[pos].type == TokenType.NE) {
                val eq = tokens[pos].type == TokenType.EQ
                pos++
                val r = parseRelational()
                v = Val.B(if (eq) compareVal(v, r) == 0 else compareVal(v, r) != 0)
            }
            return v
        }

        private fun parseRelational(): Val {
            var v = parseAdditive()
            while (tokens[pos].type == TokenType.LT || tokens[pos].type == TokenType.GT) {
                val lt = tokens[pos].type == TokenType.LT
                pos++
                val r = parseAdditive()
                v = Val.B(if (lt) compareVal(v, r) < 0 else compareVal(v, r) > 0)
            }
            return v
        }

        private fun compareVal(x: Val, y: Val): Int {
            val dx = toD(x)
            val dy = toD(y)
            return when {
                dx < dy -> -1
                dx > dy -> 1
                else -> 0
            }
        }

        private fun toD(v: Val): Double = when (v) {
            is Val.I -> v.v.toDouble()
            is Val.D -> v.v
            is Val.B -> throw IllegalArgumentException("布林不能做數值比較")
        }

        private fun parseAdditive(): Val {
            var v = parseTerm()
            while (tokens[pos].type == TokenType.PLUS || tokens[pos].type == TokenType.MINUS) {
                val plus = tokens[pos].type == TokenType.PLUS
                pos++
                val r = parseTerm()
                v = if (plus) addVal(v, r) else subVal(v, r)
            }
            return v
        }

        private fun parseTerm(): Val {
            var v = parseUnary()
            while (tokens[pos].type == TokenType.MUL || tokens[pos].type == TokenType.DIV) {
                val mul = tokens[pos].type == TokenType.MUL
                pos++
                val r = parseUnary()
                v = if (mul) mulVal(v, r) else divVal(v, r)
            }
            return v
        }

        /** C# 語意：int op int 得 int，任一 double 則提升為 double。 */
        private fun addVal(x: Val, y: Val): Val = when {
            x is Val.I && y is Val.I -> Val.I(x.v + y.v)
            else -> Val.D(toD(x) + toD(y))
        }

        private fun subVal(x: Val, y: Val): Val = when {
            x is Val.I && y is Val.I -> Val.I(x.v - y.v)
            else -> Val.D(toD(x) - toD(y))
        }

        private fun mulVal(x: Val, y: Val): Val = when {
            x is Val.I && y is Val.I -> Val.I(x.v * y.v)
            else -> Val.D(toD(x) * toD(y))
        }

        /** C# 語意：int/int 除法向零截斷；int 除以零拋例外；double 除零得 Infinity。 */
        private fun divVal(x: Val, y: Val): Val = when {
            x is Val.I && y is Val.I -> {
                if (y.v == 0) throw IllegalArgumentException("除以零")
                Val.I(x.v / y.v)
            }
            else -> Val.D(toD(x) / toD(y))
        }

        private fun negVal(x: Val): Val = when (x) {
            is Val.I -> Val.I(-x.v)
            is Val.D -> Val.D(-x.v)
            is Val.B -> throw IllegalArgumentException("布林不能取負")
        }

        private fun parseUnary(): Val {
            if (tokens[pos].type == TokenType.MINUS) {
                pos++
                return negVal(parseUnary())
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Val {
            val t = tokens[pos]
            return when (t.type) {
                TokenType.NUMBER -> {
                    pos++
                    if (t.text.contains('.')) Val.D(t.numD) else Val.I(t.numI)
                }
                TokenType.VAR_A -> { pos++; Val.I(a) }
                TokenType.VAR_B -> { pos++; Val.I(b) }
                TokenType.FUNC -> { pos++; callFunc(t.text) }
                TokenType.LPAREN -> {
                    pos++
                    val v = parseOr()
                    if (tokens[pos].type != TokenType.RPAREN) throw IllegalArgumentException("缺少右括號")
                    pos++
                    v
                }
                else -> throw IllegalArgumentException("意外的符號")
            }
        }

        /** 函式：abs / bin16 / bin8 / ShortSigned（參數以逗號分隔）。 */
        private fun callFunc(name: String): Val {
            if (tokens[pos].type != TokenType.LPAREN) throw IllegalArgumentException("函式缺少左括號")
            pos++
            val args = mutableListOf<Val>()
            if (tokens[pos].type != TokenType.RPAREN) {
                args.add(parseOr())
                while (tokens[pos].type == TokenType.COMMA) {
                    pos++
                    args.add(parseOr())
                }
            }
            if (tokens[pos].type != TokenType.RPAREN) throw IllegalArgumentException("函式缺少右括號")
            pos++
            return when (name) {
                "abs" -> {
                    val x = args.single()
                    when (x) {
                        is Val.I -> Val.I(kotlin.math.abs(x.v))
                        is Val.D -> Val.D(kotlin.math.abs(x.v))
                        is Val.B -> throw IllegalArgumentException("abs 需要數值")
                    }
                }
                "bin16" -> {
                    val (x, y) = twoArgs(args, name)
                    Val.I((x as Val.I).v * 256 + (y as Val.I).v)
                }
                "bin8" -> Val.I((args.single() as Val.I).v)
                "ShortSigned" -> {
                    val (x, y) = twoArgs(args, name)
                    Val.D(((x as Val.I).v * 256 + (y as Val.I).v).toShort().toDouble())
                }
                else -> throw IllegalArgumentException("未知函式：$name")
            }
        }

        private fun twoArgs(args: List<Val>, name: String): Pair<Val, Val> {
            if (args.size != 2) throw IllegalArgumentException("$name 需要兩個參數")
            return args[0] to args[1]
        }
    }
}

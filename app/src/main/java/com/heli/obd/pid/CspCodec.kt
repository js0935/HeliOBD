/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.pid

/**
 * .csp（Custom Sensor Profile）編解碼器：純 JVM，可在單元測試。
 *
 * 格式與 Torque 的自訂 PID CSV 相容：
 * ```
 * name,mode,pid,equation,min,max,unit
 * ```
 * - 首行為註解檔頭（以 # 開頭），其餘 # 開頭行一律略過。
 * - 欄位含逗號時以雙引號包裹（CSV 標準）。
 * - min / max 可為空白（未設定）。
 */
object CspCodec {

    const val HEADER = "# HeliOBD Custom Sensor Profile (.csp)"

    data class CspPid(
        val name: String,
        val mode: String,
        val pid: String,
        val equation: String,
        val min: Double?,
        val max: Double?,
        val unit: String,
    )

    fun export(pids: List<CspPid>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        sb.append("# name,mode,pid,equation,min,max,unit").append('\n')
        pids.forEach { pid ->
            sb.append(joinRow(pid)).append('\n')
        }
        return sb.toString()
    }

    /** 解析 .csp 內容；格式錯誤或欄位不足的列會被略過。 */
    fun parse(text: String): List<CspPid> {
        val result = mutableListOf<CspPid>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val fields = splitRow(line)
            if (fields.size < 5) return@forEach
            val name = fields[0].trim()
            val mode = fields[1].trim()
            val pid = fields[2].trim()
            val equation = fields[3].trim()
            if (name.isEmpty() || mode.isEmpty() || pid.isEmpty() || equation.isEmpty()) {
                return@forEach
            }
            val min = fields.getOrNull(4)?.trim()?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
            val max = fields.getOrNull(5)?.trim()?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
            val unit = fields.getOrNull(6)?.trim().orEmpty()
            result.add(CspPid(name, mode, pid, equation, min, max, unit))
        }
        return result
    }

    private fun joinRow(pid: CspPid): String = listOf(
        pid.name, pid.mode, pid.pid, pid.equation,
        pid.min?.let { trimNum(it) }.orEmpty(),
        pid.max?.let { trimNum(it) }.orEmpty(),
        pid.unit,
    ).joinToString(",") { escape(it) }

    private fun escape(field: String): String =
        if (field.contains(',') || field.contains('"')) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    private fun splitRow(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') {
                            current.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        current.append(c)
                    }
                }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.3f".format(v)
}

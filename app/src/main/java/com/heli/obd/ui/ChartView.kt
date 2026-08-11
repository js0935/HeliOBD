/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 捲動多線曲線圖（自繪，無外部依賴）。
 *
 * 每系列以固定範圍正規化（避免 RPM 壓扁其他訊號），顯示最近 [windowSize] 個樣本。
 */
class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Series(val label: String, val unit: String, val maxValue: Float, val color: Int)

    private val windowSize = 60
    private val samples = ArrayDeque<Map<String, Float>>()

    private var series: List<Series> = emptyList()
    private var seriesMap = emptyMap<String, Series>()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 58, 74)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(148, 163, 184)
        textSize = 22f
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(148, 163, 184)
        textSize = 18f
    }

    fun setSeries(list: List<Series>) {
        series = list
        seriesMap = list.associateBy { it.label }
        invalidate()
    }

    fun addSample(values: Map<String, Float>) {
        if (values.isEmpty()) return
        val prev = samples.lastOrNull()
        // 斷線補點（carry-forward）：低頻訊號缺值時沿用前一筆，曲線不中斷
        val merged = if (prev != null) {
            val result = LinkedHashMap<String, Float>()
            for (key in seriesMap.keys) {
                val v = values[key] ?: prev[key] ?: continue
                result[key] = v
            }
            result
        } else {
            values
        }
        if (merged.isEmpty()) return
        samples.addLast(merged)
        while (samples.size > windowSize) samples.removeFirst()
        invalidate()
    }

    fun hasData(): Boolean = samples.isNotEmpty()

    fun clear() {
        samples.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padLeft = dp(46f)
        val padBottom = dp(44f)
        val plotW = w - padLeft - dp(10f)
        val plotH = h - padBottom - dp(10f)
        if (plotW <= 0 || plotH <= 0) return

        drawGrid(canvas, padLeft, plotW, plotH, padBottom)
        drawSeriesLines(canvas, padLeft, plotW, plotH, padBottom)
        drawLegend(canvas, padLeft, plotW, padBottom)
        drawStats(canvas, padLeft, plotW, padBottom)
    }

    private fun drawGrid(canvas: Canvas, padLeft: Float, plotW: Float, plotH: Float, padBottom: Float) {
        val lines = 4
        for (i in 0..lines) {
            val y = dp(10f) + plotH * i / lines
            canvas.drawLine(padLeft, y, padLeft + plotW, y, gridPaint)
            canvas.drawText("${(100 - i * 25)}%", dp(4f), y + dp(6f), labelPaint)
        }
    }

    private fun drawSeriesLines(
        canvas: Canvas,
        padLeft: Float,
        plotW: Float,
        plotH: Float,
        padBottom: Float,
    ) {
        val n = samples.size
        if (n < 2) return
        val stepX = plotW / windowSize
        val top = dp(10f)

        for (s in series) {
            linePaint.color = s.color
            val path = Path()
            var started = false
            for (i in 0 until n) {
                val v = samples[i][s.label] ?: continue
                val x = padLeft + plotW - (n - 1 - i) * stepX
                val ratio = (v / s.maxValue).coerceIn(0f, 1f)
                val y = top + plotH * (1f - ratio)
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
            }
            canvas.drawPath(path, linePaint)
        }
    }

    private fun drawLegend(canvas: Canvas, padLeft: Float, plotW: Float, padBottom: Float) {
        val latest = samples.lastOrNull() ?: return
        var x = padLeft
        val baseline = height.toFloat() - dp(6f)
        for (s in series) {
            val value = latest[s.label]
            val text = "${s.label} ${value?.let { String.format("%.0f%s", it, s.unit) } ?: "—"}"
            badgePaint.color = s.color
            val tw = badgePaint.measureText(text)
            if (x + tw > padLeft + plotW) break
            canvas.drawText(text, x, baseline, badgePaint)
            x += tw + dp(14f)
        }
    }

    private fun drawStats(canvas: Canvas, padLeft: Float, plotW: Float, padBottom: Float) {
        if (samples.size < 2) return
        var x = padLeft
        val baseline = height.toFloat() - dp(24f)
        for (s in series) {
            val pts = samples.mapNotNull { it[s.label] }
            if (pts.size < 2) continue
            val max = pts.max()
            val min = pts.min()
            val avg = pts.average().toFloat()
            val text = "${s.label} MAX ${fmt(max)} MIN ${fmt(min)} AVG ${fmt(avg)}"
            badgePaint.color = s.color
            val tw = badgePaint.measureText(text)
            if (x + tw > padLeft + plotW) break
            canvas.drawText(text, x, baseline, badgePaint)
            x += tw + dp(14f)
        }
    }

    private fun fmt(value: Float): String =
        String.format(if (value >= 100f) "%.0f" else "%.1f", value)

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}

package com.heli.obd.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 歷史行程靜態多序列曲線圖：每序列可自訂數值範圍（支援負值，如 Fuel Trim），
 * 樣本 x 軸均勻分佈，圖例顯示各序列最新值。
 */
class TripChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Series(val label: String, val minValue: Float, val maxValue: Float, val color: Int)

    private var series: List<Series> = emptyList()
    private var samples: List<Map<String, Float>> = emptyList()

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
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(148, 163, 184)
        textSize = 18f
    }

    fun setSeries(list: List<Series>) {
        series = list
        invalidate()
    }

    fun setSamples(list: List<Map<String, Float>>) {
        samples = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padLeft = dp(10f)
        val padTop = dp(10f)
        val padRight = dp(10f)
        val padBottom = dp(26f)
        val plotW = width.toFloat() - padLeft - padRight
        val plotH = height.toFloat() - padTop - padBottom
        if (plotW <= 0 || plotH <= 0) return

        drawGrid(canvas, padLeft, padTop, plotW, plotH)
        drawSeriesLines(canvas, padLeft, padTop, plotW, plotH)
        drawLegend(canvas, padLeft, plotW)
    }

    private fun drawGrid(canvas: Canvas, padLeft: Float, padTop: Float, plotW: Float, plotH: Float) {
        val lines = 4
        for (i in 0..lines) {
            val y = padTop + plotH * i / lines
            canvas.drawLine(padLeft, y, padLeft + plotW, y, gridPaint)
        }
    }

    private fun drawSeriesLines(
        canvas: Canvas, padLeft: Float, padTop: Float, plotW: Float, plotH: Float,
    ) {
        val n = samples.size
        if (n < 2) return
        for (s in series) {
            linePaint.color = s.color
            val path = Path()
            var started = false
            for (i in 0 until n) {
                val v = samples[i][s.label] ?: continue
                val x = padLeft + plotW * i / (n - 1)
                val ratio = ((v - s.minValue) / (s.maxValue - s.minValue)).coerceIn(0f, 1f)
                val y = padTop + plotH * (1f - ratio)
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

    private fun drawLegend(canvas: Canvas, padLeft: Float, plotW: Float) {
        val latest = samples.lastOrNull() ?: return
        var x = padLeft
        val baseline = height.toFloat() - dp(6f)
        for (s in series) {
            val v = latest[s.label]
            val valueText = v?.let {
                if (it == it.toInt().toFloat()) it.toInt().toString() else "%.1f".format(it)
            } ?: "—"
            val text = "${s.label} $valueText"
            legendPaint.color = s.color
            val tw = legendPaint.measureText(text)
            if (x + tw > padLeft + plotW) break
            canvas.drawText(text, x, baseline, legendPaint)
            x += tw + dp(14f)
        }
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}

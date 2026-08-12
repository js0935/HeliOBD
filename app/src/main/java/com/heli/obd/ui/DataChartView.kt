/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 歷史數據重疊圖：多系列各自自動縮放（比較趨勢形狀），
 * 頂部圖例顯示每系列目前值與範圍，底部為繪圖區。
 */
class DataChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Series(val name: String, val color: Int, val points: List<Float>)

    private val density get() = resources.displayMetrics.density

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33E8EDF2
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A99A8.toInt()
        textSize = 12f * resources.displayMetrics.density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x668A99A8
        strokeWidth = 1f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    private var seriesList: List<Series> = emptyList()
    private var selectedIndex = -1

    private val gestureDetector by lazy {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                selectIndexAt(e.x)
            }
        })
    }

    fun setSeries(series: List<Series>) {
        seriesList = series
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selectedIndex >= 0) {
                    selectedIndex = -1
                    invalidate()
                }
            }
        }
        return true
    }

    private fun selectIndexAt(x: Float) {
        val chartLeft = (10f * density).toFloat()
        val chartRight = (width - 52f * density).toFloat()
        if (x < chartLeft || x > chartRight) return
        val maxSize = seriesList.maxOfOrNull { it.points.size } ?: 1
        if (maxSize < 2) return
        val ratio = ((x - chartLeft) / (chartRight - chartLeft)).coerceIn(0f, 1f)
        selectedIndex = (ratio * (maxSize - 1)).roundToInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = (10f * density).toInt()
        val padRight = (52f * density).toInt()
        val legendHeight = seriesList.size * (18f * density).toInt()
        val chartTop = (pad + legendHeight).toFloat()
        val chartBottom = (height - pad).toFloat()
        val chartLeft = pad.toFloat()
        val chartRight = (width - padRight).toFloat()

        var ly = (14f * density).toFloat()
        for (s in seriesList) {
            dotPaint.color = s.color
            canvas.drawCircle(chartLeft + 6f * density, ly - 5f * density, 5f * density, dotPaint)
            val range = if (s.points.size > 1) {
                val min = s.points.minOrNull() ?: 0f
                val max = s.points.maxOrNull() ?: 0f
                if (min == max) String.format(Locale.US, "%.1f", min) else String.format(Locale.US, "%.1f~%.1f", min, max)
            } else {
                "—"
            }
            val current = s.points.lastOrNull()
            val label = if (current != null) {
                val avg = if (s.points.size > 1) s.points.average().toFloat() else current
                String.format(Locale.US, "%s %.1f  [%s]  AVG %.1f", s.name, current, range, avg)
            } else {
                s.name
            }
            canvas.drawText(label, chartLeft + 16f * density, ly, textPaint)
            ly += 18f * density
        }

        val plotH = chartBottom - chartTop
        if (plotH <= 0f) return
        for (i in 0..GRID_COUNT) {
            val gy = chartTop + plotH * i / GRID_COUNT
            canvas.drawLine(chartLeft, gy, chartRight, gy, gridPaint)
        }

        for (s in seriesList) {
            if (s.points.size < 2) continue
            val min = s.points.minOrNull() ?: 0f
            val max = s.points.maxOrNull() ?: 0f
            val span = (max - min).takeIf { it > 0f } ?: 1f
            val path = Path()
            s.points.forEachIndexed { i, v ->
                val x = chartLeft + (chartRight - chartLeft) * i / (s.points.size - 1)
                val py = chartBottom - (v - min) / span * plotH
                if (i == 0) path.moveTo(x, py) else path.lineTo(x, py)
            }
            linePaint.color = s.color
            canvas.drawPath(path, linePaint)
        }

        drawAxisLabels(canvas, chartRight)
        drawCrosshair(canvas, chartLeft, chartRight, chartTop, chartBottom, plotH)
    }

    private fun drawAxisLabels(canvas: Canvas, chartRight: Float) {
        var ry = (14f * density).toFloat()
        for (s in seriesList) {
            if (s.points.size >= 2) {
                val min = s.points.minOrNull() ?: 0f
                val max = s.points.maxOrNull() ?: 0f
                textPaint.color = s.color
                canvas.drawText("${fmt(max)}/${fmt(min)}", chartRight + 6f * density, ry, textPaint)
            }
            ry += 18f * density
        }
    }

    private fun drawCrosshair(
        canvas: Canvas,
        chartLeft: Float,
        chartRight: Float,
        chartTop: Float,
        chartBottom: Float,
        plotH: Float,
    ) {
        if (selectedIndex < 0) return
        val maxSize = seriesList.maxOfOrNull { it.points.size } ?: 0
        if (maxSize < 2) return
        val cx = chartLeft + (chartRight - chartLeft) * selectedIndex / (maxSize - 1)
        canvas.drawLine(cx, chartTop, cx, chartBottom, crosshairPaint)
        for (s in seriesList) {
            if (s.points.isEmpty()) continue
            val idx = (selectedIndex.toFloat() * (s.points.size - 1) / (maxSize - 1))
                .roundToInt().coerceIn(0, s.points.size - 1)
            val v = s.points[idx]
            val min = s.points.minOrNull() ?: 0f
            val max = s.points.maxOrNull() ?: 0f
            val span = (max - min).takeIf { it > 0f } ?: 1f
            val py = chartBottom - (v - min) / span * plotH
            dotPaint.color = s.color
            canvas.drawCircle(cx, py, 4f * density, dotPaint)
            textPaint.color = s.color
            val text = "${s.name} ${String.format(Locale.US, "%.1f", v)}"
            val textW = textPaint.measureText(text)
            val tx = if (cx + 8f * density + textW > width.toFloat()) {
                cx - 8f * density - textW
            } else {
                cx + 8f * density
            }
            canvas.drawText(text, tx, py - 8f * density, textPaint)
        }
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.0f", value)

    companion object {
        const val GRID_COUNT = 4
        val PALETTE = intArrayOf(
            0xFF2ECC71.toInt(),  // 綠
            0xFFF1C40F.toInt(),  // 黃
            0xFFE74C3C.toInt(),  // 紅
            0xFF3498DB.toInt(),  // 藍
            0xFF9B59B6.toInt(),  // 紫
            0xFF1ABC9C.toInt(),  // 青
            0xFFE67E22.toInt(),  // 橙
        )
    }
}

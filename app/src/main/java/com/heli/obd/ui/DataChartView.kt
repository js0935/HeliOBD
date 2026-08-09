package com.heli.obd.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

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

    private var seriesList: List<Series> = emptyList()

    fun setSeries(series: List<Series>) {
        seriesList = series
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = (10f * density).toInt()
        val legendHeight = seriesList.size * (18f * density).toInt()
        val chartTop = (pad + legendHeight).toFloat()
        val chartBottom = (height - pad).toFloat()
        val chartLeft = pad.toFloat()
        val chartRight = (width - pad).toFloat()

        var ly = (14f * density).toFloat()
        for (s in seriesList) {
            dotPaint.color = s.color
            canvas.drawCircle(chartLeft + 6f * density, ly - 5f * density, 5f * density, dotPaint)
            val range = if (s.points.size > 1) {
                val min = s.points.minOrNull() ?: 0f
                val max = s.points.maxOrNull() ?: 0f
                if (min == max) String.format("%.1f", min) else String.format("%.1f~%.1f", min, max)
            } else {
                "—"
            }
            val current = s.points.lastOrNull()
            val label = if (current != null) {
                String.format("%s %.1f  [%s]", s.name, current, range)
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
    }

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

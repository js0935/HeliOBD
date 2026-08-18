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
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.heli.obd.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 捲動多線曲線圖（自繪，無外部依賴）。
 *
 * 每系列以固定範圍正規化（避免 RPM 壓扁其他訊號），顯示最近 [windowSize] 個樣本。
 * 支援縮放（[setWindowSize]）、觸控游標讀值（[onCursorMoved]）與直方圖模式（[histogramMode]）。
 */
class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Series(val label: String, val unit: String, val maxValue: Float, val color: Int)

    private var windowSize = 60
    private val samples = ArrayDeque<Map<String, Float>>()

    /** 游標所在樣本索引（-1 表示無游標），由觸控更新 */
    var cursorIndex = -1
        private set

    /** 游標移動回呼：回傳該樣本的所有系列值，無游標時為 null */
    var onCursorMoved: ((Map<String, Float>?) -> Unit)? = null

    /** 直方圖模式：以最左側（最早）樣本為基準，顯示最近樣本的數值分布 */
    var histogramMode = false
        set(value) {
            field = value
            invalidate()
        }

    private var series: List<Series> = emptyList()
    private var seriesMap = emptyMap<String, Series>()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        alpha = 0x5A
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
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 22f
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 18f
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val reusablePath = Path()

    fun setSeries(list: List<Series>) {
        series = list
        seriesMap = list.associateBy { it.label }
        invalidate()
    }

    /** 調整顯示視窗大小（縮放）：值越小圖形放大，顯示越少樣本 */
    fun setWindowSize(size: Int) {
        windowSize = size.coerceIn(10, 600)
        while (samples.size > windowSize) samples.removeFirst()
        invalidate()
    }

    fun getWindowSize(): Int = windowSize

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
        cursorIndex = -1
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (samples.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateCursor(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                cursorIndex = -1
                onCursorMoved?.invoke(null)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateCursor(touchX: Float) {
        val padLeft = dp(46f)
        val plotW = width.toFloat() - padLeft - dp(10f)
        if (plotW <= 0f) return
        val n = samples.size
        if (n < 1) return
        val stepX = plotW / windowSize
        // 與 drawSeriesLines 相同的座標映射：x = padLeft + plotW - (n-1-i)*stepX
        val raw = (padLeft + plotW - touchX) / stepX
        val i = (n - 1 - raw).roundToInt().coerceIn(0, n - 1)
        cursorIndex = i
        onCursorMoved?.invoke(samples[i])
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
        if (histogramMode) {
            drawHistogram(canvas, padLeft, plotW, plotH, padBottom)
        } else {
            drawSeriesLines(canvas, padLeft, plotW, plotH, padBottom)
        }
        drawLegend(canvas, padLeft, plotW, padBottom)
        drawStats(canvas, padLeft, plotW, padBottom)
        drawCursor(canvas, padLeft, plotW, plotH, padBottom)
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
            reusablePath.reset()
            var started = false
            for (i in 0 until n) {
                val v = samples[i][s.label] ?: continue
                val x = padLeft + plotW - (n - 1 - i) * stepX
                val ratio = (v / s.maxValue).coerceIn(0f, 1f)
                val y = top + plotH * (1f - ratio)
                if (!started) {
                    reusablePath.moveTo(x, y)
                    started = true
                } else {
                    reusablePath.lineTo(x, y)
                }
            }
            canvas.drawPath(reusablePath, linePaint)
        }
    }

    /** 直方圖：將各系列數值分 bin 統計，繪製出現次數條狀圖 */
    private fun drawHistogram(
        canvas: Canvas,
        padLeft: Float,
        plotW: Float,
        plotH: Float,
        padBottom: Float,
    ) {
        val n = samples.size
        if (n < 2) return
        val top = dp(10f)
        val base = top + plotH
        val binCount = 10
        val barW = plotW / binCount
        for (s in series) {
            val pts = samples.mapNotNull { it[s.label] }
            if (pts.size < 2) continue
            val min = pts.min()
            val max = pts.max()
            val range = (max - min).coerceAtLeast(1e-6f)
            val bins = IntArray(binCount)
            for (v in pts) {
                val idx = (((v - min) / range) * binCount).toInt().coerceIn(0, binCount - 1)
                bins[idx]++
            }
            val maxCount = bins.max().coerceAtLeast(1)
            linePaint.color = s.color
            linePaint.strokeWidth = (barW * 0.7f).coerceAtLeast(1f)
            for (b in 0 until binCount) {
                val h = plotH * (bins[b].toFloat() / maxCount)
                val cx = padLeft + barW * (b + 0.5f)
                canvas.drawLine(cx, base, cx, base - h, linePaint)
            }
            linePaint.strokeWidth = 3f
        }
    }

    private fun drawCursor(
        canvas: Canvas,
        padLeft: Float,
        plotW: Float,
        plotH: Float,
        padBottom: Float,
    ) {
        if (cursorIndex < 0 || cursorIndex >= samples.size) return
        val n = samples.size
        val stepX = plotW / windowSize
        val x = padLeft + plotW - (n - 1 - cursorIndex) * stepX
        val top = dp(10f)
        canvas.drawLine(x, top, x, top + plotH, cursorPaint)
    }

    private fun drawLegend(canvas: Canvas, padLeft: Float, plotW: Float, padBottom: Float) {
        val latest = samples.lastOrNull() ?: return
        var x = padLeft
        val baseline = height.toFloat() - dp(6f)
        for (s in series) {
            val value = latest[s.label]
            val text = "${s.label} ${value?.let { String.format(Locale.US, "%.0f%s", it, s.unit) } ?: "—"}"
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

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
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.heli.obd.R

/**
 * 歷史行程多序列曲線圖：支援靜態顯示與動態回放。
 * 每序列可自訂數值範圍（支援負值，如 Fuel Trim），樣本 x 軸均勻分佈，圖例顯示各序列最新值。
 */
class TripChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Series(val label: String, val minValue: Float, val maxValue: Float, val color: Int)

    private var series: List<Series> = emptyList()
    private var samples: List<Map<String, Float>> = emptyList()

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
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 18f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 12f
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val reusablePath = Path()

    private var playbackIndex = -1
    private var isPlaying = false
    private var playbackSpeed = 1
    private val playbackHandler = Handler(Looper.getMainLooper())
    private val playbackRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            playbackIndex += playbackSpeed
            if (playbackIndex >= samples.size) {
                playbackIndex = samples.size - 1
                isPlaying = false
                onPlaybackFinished?.invoke()
                invalidate()
                return
            }
            invalidate()
            playbackHandler.postDelayed(this, PLAYBACK_INTERVAL_MS)
        }
    }

    var onPlaybackFinished: (() -> Unit)? = null
    var onPlaybackProgress: ((index: Int, total: Int) -> Unit)? = null

    fun setSeries(list: List<Series>) {
        series = list
        invalidate()
    }

    fun setSamples(list: List<Map<String, Float>>) {
        stopPlayback()
        samples = list
        playbackIndex = -1
        invalidate()
    }

    fun startPlayback(speed: Int = 1) {
        if (samples.size < 2) return
        playbackSpeed = speed
        playbackIndex = 0
        isPlaying = true
        playbackHandler.removeCallbacks(playbackRunnable)
        playbackHandler.postDelayed(playbackRunnable, PLAYBACK_INTERVAL_MS)
        invalidate()
    }

    fun stopPlayback() {
        isPlaying = false
        playbackHandler.removeCallbacks(playbackRunnable)
    }

    fun pausePlayback() {
        isPlaying = false
        playbackHandler.removeCallbacks(playbackRunnable)
    }

    fun resumePlayback() {
        if (playbackIndex < 0 || playbackIndex >= samples.size - 1) return
        isPlaying = true
        playbackHandler.removeCallbacks(playbackRunnable)
        playbackHandler.postDelayed(playbackRunnable, PLAYBACK_INTERVAL_MS)
    }

    fun isPlaybackPlaying(): Boolean = isPlaying

    fun setPlaybackSpeed(speed: Int) {
        playbackSpeed = speed.coerceIn(1, 10)
    }

    fun seekPlayback(index: Int) {
        playbackIndex = index.coerceIn(0, samples.size - 1)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopPlayback()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padLeft = dp(10f)
        val padTop = dp(10f)
        val padRight = dp(64f)
        val padBottom = dp(26f)
        val plotW = width.toFloat() - padLeft - padRight
        val plotH = height.toFloat() - padTop - padBottom
        if (plotW <= 0 || plotH <= 0) return

        drawGrid(canvas, padLeft, padTop, plotW, plotH)
        drawSeriesLines(canvas, padLeft, padTop, plotW, plotH)
        if (playbackIndex >= 0 && playbackIndex < samples.size) {
            drawPlaybackCursor(canvas, padLeft, padTop, plotW, plotH)
        }
        drawAxisLabels(canvas, padLeft, plotW)
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
        val drawUpTo = if (playbackIndex >= 0) (playbackIndex + 1).coerceAtMost(n) else n
        if (drawUpTo < 2) return
        for (s in series) {
            linePaint.color = s.color
            reusablePath.reset()
            var started = false
            for (i in 0 until drawUpTo) {
                val v = samples[i][s.label] ?: continue
                val x = padLeft + plotW * i / (n - 1)
                val ratio = ((v - s.minValue) / (s.maxValue - s.minValue)).coerceIn(0f, 1f)
                val y = padTop + plotH * (1f - ratio)
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

    private fun drawPlaybackCursor(
        canvas: Canvas, padLeft: Float, padTop: Float, plotW: Float, plotH: Float,
    ) {
        val n = samples.size
        if (n < 2 || playbackIndex < 0 || playbackIndex >= n) return
        val x = padLeft + plotW * playbackIndex / (n - 1)
        canvas.drawLine(x, padTop, x, padTop + plotH, cursorPaint)
        for (s in series) {
            val v = samples[playbackIndex][s.label] ?: continue
            val ratio = ((v - s.minValue) / (s.maxValue - s.minValue)).coerceIn(0f, 1f)
            val y = padTop + plotH * (1f - ratio)
            dotPaint.color = s.color
            canvas.drawCircle(x, y, dp(5f), dotPaint)
        }
        onPlaybackProgress?.invoke(playbackIndex, n)
    }

    private fun drawAxisLabels(canvas: Canvas, padLeft: Float, plotW: Float) {
        var ry = dp(24f)
        for (s in series) {
            val vals = samples.mapNotNull { it[s.label] }
            if (vals.size >= 2) {
                val max = vals.max()
                val min = vals.min()
                axisPaint.color = s.color
                canvas.drawText("${fmt(max)}/${fmt(min)}", padLeft + plotW + dp(4f), ry, axisPaint)
            }
            ry += dp(18f)
        }
    }

    private fun drawLegend(canvas: Canvas, padLeft: Float, plotW: Float) {
        val idx = if (playbackIndex >= 0) playbackIndex else samples.lastIndex
        if (idx < 0) return
        val latest = samples[idx]
        var x = padLeft
        val baseline = height.toFloat() - dp(6f)
        for (s in series) {
            val v = latest[s.label]
            val valueText = v?.let { fmt(it) } ?: "—"
            val avgText = samples.mapNotNull { it[s.label] }
                .takeIf { it.size >= 2 }
                ?.let { fmt(it.average().toFloat()) }
                ?: ""
            val text = "${s.label} $valueText${if (avgText.isNotEmpty()) " AVG $avgText" else ""}"
            legendPaint.color = s.color
            val tw = legendPaint.measureText(text)
            if (x + tw > padLeft + plotW) break
            canvas.drawText(text, x, baseline, legendPaint)
            x += tw + dp(14f)
        }
    }

    private fun fmt(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    companion object {
        private const val PLAYBACK_INTERVAL_MS = 100L
    }
}

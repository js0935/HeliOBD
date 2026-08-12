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
import android.view.View
import com.heli.obd.trip.TripRecorder

/**
 * 行程軌跡縮略圖：以等距投影將 (lat, lng) 映射到 View 範圍，繪製軌跡線、
 * 起點（綠）與終點（紅）。
 */
class TripTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var samples: List<TripRecorder.Sample> = emptyList()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00B4D8.toInt()
        strokeWidth = dp(3f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2ECC71.toInt() }
    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE74C3C.toInt() }
    private val trackPath = Path()

    fun setSamples(list: List<TripRecorder.Sample>) {
        samples = list.filter { it.lat != 0.0 || it.lng != 0.0 }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2) return

        val minLat = samples.minOf { it.lat }
        val maxLat = samples.maxOf { it.lat }
        val minLng = samples.minOf { it.lng }
        val maxLng = samples.maxOf { it.lng }
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-6)
        val lngSpan = (maxLng - minLng).coerceAtLeast(1e-6)

        val padding = dp(16f)
        val w = width - padding * 2
        val h = height - padding * 2
        val scale = minOf(w / lngSpan, h / latSpan).coerceAtLeast(0.0)

        fun project(s: TripRecorder.Sample): Pair<Float, Float> {
            val x = padding + ((s.lng - minLng) * scale).toFloat()
            val y = padding + ((maxLat - s.lat) * scale).toFloat()
            return x to y
        }

        val points = samples.map { project(it) }
        trackPath.reset()
        points.forEachIndexed { index, (x, y) ->
            if (index == 0) trackPath.moveTo(x, y) else trackPath.lineTo(x, y)
        }
        canvas.drawPath(trackPath, trackPaint)

        val (sx, sy) = points.first()
        val (ex, ey) = points.last()
        canvas.drawCircle(sx, sy, dp(5f), startPaint)
        canvas.drawCircle(ex, ey, dp(5f), endPaint)
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}

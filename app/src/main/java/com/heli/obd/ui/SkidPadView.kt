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
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 甩尾圓環軌跡圖：中心為 0G 原點，X 軸為橫向 G、Y 軸為縱向 G，
 * 繪製同心圓格線（1G/2G）與最近的加速度軌跡點。
 */
class SkidPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val trackPoints = ArrayDeque<Pair<Float, Float>>()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x8A, 0x99, 0xA8)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xFF, 0x6B, 0x35)
        style = Paint.Style.FILL
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x00, 0xB4, 0xD8)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x8A, 0x99, 0xA8)
        textSize = 26f
    }

    fun update(x: Float, y: Float) {
        trackPoints.addLast(x to y)
        if (trackPoints.size > MAX_POINTS) trackPoints.removeFirst()
        invalidate()
    }

    fun clear() {
        trackPoints.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 4f
        val pxPerG = min(width, height) / 2f / MAX_G
        if (pxPerG <= 0f) return

        gridPaint.style = Paint.Style.FILL
        gridPaint.color = Color.rgb(0x1A, 0x22, 0x2B)
        canvas.drawCircle(cx, cy, radius, gridPaint)
        gridPaint.style = Paint.Style.STROKE

        gridPaint.color = Color.rgb(0x2E, 0x3B, 0x48)
        canvas.drawCircle(cx, cy, pxPerG, gridPaint)
        canvas.drawCircle(cx, cy, 2f * pxPerG, gridPaint)

        canvas.drawLine(cx - radius, cy, cx + radius, cy, axisPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, axisPaint)

        for ((gx, gy) in trackPoints) {
            canvas.drawCircle(cx + gx * pxPerG, cy - gy * pxPerG, 3.5f, pointPaint)
        }

        canvas.drawCircle(cx, cy, 5f, centerPaint)

        canvas.drawText("1G", cx + pxPerG + 6f, cy - pxPerG - 6f, textPaint)
        canvas.drawText("2G", cx + 2f * pxPerG + 6f, cy - 2f * pxPerG - 6f, textPaint)
    }

    private companion object {
        const val MAX_POINTS = 400
        const val MAX_G = 2.5f
    }
}

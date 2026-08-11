/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.heli.obd.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * 圓形儀表（gauge）：270° 開口朝下的弧環 + 指針 + 紅區警示 + 中央數值。
 * 用於即時數據畫面：RPM 大錶與車速/水溫/電壓小錶。
 * 可設定範圍、紅區上限（超過變紅）、低值警示（低於變紅）。
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var unit = ""
    private var max = 100f
    private var redFrom = Float.MAX_VALUE
    private var redBelow = 0f
    private var value = 0f
    private var hasValue = false
    private var customColor: Int? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val redZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val startAngle = 135f
    private val sweepAngle = 270f

    fun setUnit(text: String) {
        unit = text
        invalidate()
    }

    fun setRange(maxValue: Float, redFromValue: Float = Float.MAX_VALUE, redBelowValue: Float = 0f) {
        max = maxValue
        redFrom = redFromValue
        redBelow = redBelowValue
        invalidate()
    }

    fun setValue(v: Float) {
        val clamped = v.coerceIn(0f, max)
        hasValue = true
        if (clamped != value) {
            value = clamped
            invalidate()
        }
    }

    /** 設定單色模式：弧環/指針/數值使用指定顏色（取代彩虹漸層）。傳 null 恢復漸層。 */
    fun setColor(color: Int?) {
        customColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        val stroke = if (isLarge()) dp(14f) else dp(10f)
        val radius = minOf(width, height) / 2f - stroke / 2f - dp(4f)

        val primary = customColor ?: colorRes(R.color.primary)
        val danger = colorRes(R.color.danger)
        val textPrimary = colorRes(R.color.text_primary)
        val textSecondary = colorRes(R.color.text_secondary)

        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        redZonePaint.strokeWidth = stroke
        pointerPaint.strokeWidth = dp(4f)
        majorTickPaint.strokeWidth = dp(2f)
        minorTickPaint.strokeWidth = dp(1f)

        trackPaint.color = colorRes(R.color.surface_alt)
        pointerPaint.color = customColor ?: textPrimary
        valuePaint.color = customColor ?: textPrimary
        unitPaint.color = textSecondary
        majorTickPaint.color = textSecondary
        minorTickPaint.color = textSecondary
        minorTickPaint.alpha = 0x66

        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawArc(rect, startAngle, sweepAngle, false, trackPaint)

        drawTicks(canvas, cx, cy, radius, stroke)

        val progress = (value / max).coerceIn(0f, 1f)
        val redProgress = (redFrom / max).coerceIn(0f, 1f)
        if (redProgress > 0f && redProgress < 1f) {
            redZonePaint.color = danger
            redZonePaint.alpha = 0x66
            canvas.drawArc(
                rect, startAngle + sweepAngle * redProgress,
                sweepAngle * (1f - redProgress), false, redZonePaint
            )
        }

        val alert = (redFrom <= max && value >= redFrom) || (redBelow > 0f && value <= redBelow)
        if (alert) {
            pointerPaint.color = danger
            valuePaint.color = danger
        }
        if (progress > 0f) {
            if (alert) {
                arcPaint.shader = null
                arcPaint.color = danger
            } else if (customColor != null) {
                arcPaint.shader = null
                arcPaint.color = customColor!!
            } else {
                arcPaint.color = primary
                arcPaint.shader = SweepGradient(
                    cx, cy,
                    intArrayOf(primary, primary, colorRes(R.color.amber), danger),
                    floatArrayOf(0f, 0.375f, 0.75f, 1f)
                )
            }
            canvas.drawArc(rect, startAngle, sweepAngle * progress, false, arcPaint)
        }

        if (progress > 0f) {
            val angleRad = Math.toRadians((startAngle + sweepAngle * progress).toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()
            // 指針從中央文字區外緣畫到弧內緣，避免與數值文字重疊
            val innerLen = radius * 0.45f
            val outerLen = radius - stroke
            canvas.drawLine(
                cx + cosA * innerLen, cy + sinA * innerLen,
                cx + cosA * outerLen, cy + sinA * outerLen,
                pointerPaint
            )
        }

        drawTexts(canvas, cx, cy, radius)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float, stroke: Float) {
        val inner = radius - stroke - dp(2f)
        val majorLen = dp(8f)
        val minorLen = dp(4f)
        val minorEvery = 10f
        val majorEvery = 30f
        val count = (sweepAngle / minorEvery).toInt()
        for (i in 0..count) {
            val angle = startAngle + minorEvery * i
            val isMajor = i % (majorEvery / minorEvery).toInt() == 0
            val len = if (isMajor) majorLen else minorLen
            val rad = Math.toRadians(angle.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            canvas.drawLine(
                cx + cosA * (inner - len), cy + sinA * (inner - len),
                cx + cosA * inner, cy + sinA * inner,
                if (isMajor) majorTickPaint else minorTickPaint
            )
        }
    }

    private fun drawTexts(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val big = isLarge()
        val valueText = if (!hasValue) "--" else formatValue()
        if (big) {
            valuePaint.textSize = sp(44f)
            unitPaint.textSize = sp(13f)
            canvas.drawText(valueText, cx, cy - radius * 0.03f, valuePaint)
            if (unit.isNotEmpty()) {
                canvas.drawText(unit, cx, cy + radius * 0.26f, unitPaint)
            }
        } else {
            valuePaint.textSize = sp(24f)
            unitPaint.textSize = sp(11f)
            canvas.drawText(valueText, cx, cy - radius * 0.08f, valuePaint)
            if (unit.isNotEmpty()) {
                canvas.drawText(unit, cx, cy + radius * 0.30f, unitPaint)
            }
        }
    }

    private fun formatValue(): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

    private fun isLarge(): Boolean = width >= dp(240f)

    private fun colorRes(id: Int): Int = ContextCompat.getColor(context, id)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
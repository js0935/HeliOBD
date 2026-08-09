package com.heli.obd.scoring

/**
 * 駕駛評分引擎（純邏輯，無 Android 依賴）。
 *
 * 規則：滿分 100。急加速/急煞各扣 3 分，高轉速時間比例扣 20 分，
 * 超速比例扣 20 分，怠速時間比例扣 10 分（下限 0）。
 */
class DrivingScoreEngine(
    private val redlineRpm: Int = 8000,
    private val speedLimitKmh: Int = 110,
) {

    private data class Sample(val timeMs: Long, val rpm: Int?, val speed: Int?)

    private val samples = mutableListOf<Sample>()

    fun reset() {
        samples.clear()
    }

    fun addSample(timeMs: Long, rpm: Int?, speed: Int?) {
        if (rpm == null && speed == null) return
        samples.add(Sample(timeMs, rpm, speed))
    }

    fun sampleCount(): Int = samples.size

    /** 平滑加速度（m/s²）：最近 3 個車速樣本取平均斜率 */
    private fun accelerationAt(index: Int): Float {
        if (index < 2) return 0f
        val s0 = samples[index - 2]
        val s1 = samples[index - 1]
        val s2 = samples[index]
        val v0 = s0.speed ?: return 0f
        val v1 = s1.speed ?: return 0f
        val v2 = s2.speed ?: return 0f
        val dt1 = ((s1.timeMs - s0.timeMs).coerceAtLeast(100L)) / 1000f
        val dt2 = ((s2.timeMs - s1.timeMs).coerceAtLeast(100L)) / 1000f
        val a1 = (v1 - v0) / 3.6f / dt1
        val a2 = (v2 - v1) / 3.6f / dt2
        return (a1 + a2) / 2f
    }

    fun hardAccelCount(): Int =
        (2 until samples.size).count { accelerationAt(it) > 3.0f }

    fun hardBrakeCount(): Int =
        (2 until samples.size).count { accelerationAt(it) < -3.0f }

    fun highRpmRatio(): Float {
        val withRpm = samples.filter { it.rpm != null }
        if (withRpm.isEmpty()) return 0f
        val high = withRpm.count { (it.rpm ?: 0) > redlineRpm * 0.8 }
        return high.toFloat() / withRpm.size
    }

    fun overspeedRatio(): Float {
        val moving = samples.filter { (it.speed ?: 0) > 0 }
        if (moving.isEmpty()) return 0f
        val over = moving.count { (it.speed ?: 0) > speedLimitKmh }
        return over.toFloat() / moving.size
    }

    fun idleRatio(): Float {
        if (samples.isEmpty()) return 0f
        val idle = samples.count { (it.speed ?: 0) == 0 }
        return idle.toFloat() / samples.size
    }

    /** 0–100 分；樣本不足 6 筆回傳 0（UI 以 sampleCount 判斷是否顯示） */
    fun score(): Int {
        if (samples.size < 6) return 0
        var s = 100f
        s -= 3f * hardAccelCount()
        s -= 3f * hardBrakeCount()
        s -= 20f * highRpmRatio()
        s -= 20f * overspeedRatio()
        s -= 10f * idleRatio()
        return s.toInt().coerceIn(0, 100)
    }

    /** 評等等級（與顯示文字解耦，由 UI 層映射到語言資源） */
    enum class Grade { EXCELLENT, GOOD, FAIR, POOR }

    fun grade(score: Int): Grade = when {
        score >= 85 -> Grade.EXCELLENT
        score >= 70 -> Grade.GOOD
        score >= 55 -> Grade.FAIR
        else -> Grade.POOR
    }
}

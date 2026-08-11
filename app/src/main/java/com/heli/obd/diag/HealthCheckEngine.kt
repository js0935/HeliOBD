/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.diag

import androidx.annotation.StringRes
import com.heli.obd.R
import com.heli.obd.elm.FreezeFrame
import com.heli.obd.elm.ObdConstants
import com.heli.obd.elm.ObdManager
import kotlin.math.abs

/**
 * 健康檢查引擎（離線規則，不需網路）。
 *
 * 輸入：即時數據快照 + 故障碼清單
 * 輸出：子系統階梯評分（100/85/50/20）→ 加權總分（含 DTC 懲罰）→ 綠/黃/紅等級
 * 附加：多感測器交叉診斷規則（參考 PyOBD-Dashboard 做法）＋ 信賴度百分比
 */
object HealthCheckEngine {

    enum class Subsystem(@StringRes val labelRes: Int, val weight: Float) {
        ENGINE(R.string.hc_sub_engine, 0.30f),
        FUEL(R.string.hc_sub_fuel, 0.20f),
        COOLING(R.string.hc_sub_cooling, 0.20f),
        ELECTRICAL(R.string.hc_sub_electrical, 0.15f),
        EMISSIONS(R.string.hc_sub_emissions, 0.15f),
    }

    enum class HealthLevel(@StringRes val labelRes: Int) {
        GREEN(R.string.hc_level_green),
        YELLOW(R.string.hc_level_yellow),
        RED(R.string.hc_level_red),
    }

    data class RangeThreshold(
        val normalLow: Float, val normalHigh: Float,
        val warningLow: Float, val warningHigh: Float,
        val criticalLow: Float, val criticalHigh: Float,
    )

    data class LiveRule(
        @StringRes val titleRes: Int,
        @StringRes val adviceRes: Int,
        val severity: DiagnosisEngine.Severity,
        val confidence: Float,
        val test: (ObdManager.LiveData) -> Boolean,
    )

    private const val SCORE_OK = 100
    private const val SCORE_WARN = 85
    private const val SCORE_CRIT = 50
    private const val SCORE_BAD = 20

    // 汽機車向閾值：水溫 / 電壓 / 負載 / 寬域 AFR（階梯由窄到寬，依序比對）
    val COOLANT_T = RangeThreshold(70f, 100f, 70f, 104f, 70f, 110f)
    val VOLTAGE_T = RangeThreshold(12.0f, 15.0f, 11.8f, 15.2f, 11.0f, 16.0f)
    val LOAD_T = RangeThreshold(0f, 70f, 0f, 85f, 0f, 95f)
    val AFR_T = RangeThreshold(13.5f, 15.5f, 12.5f, 16.5f, 11.5f, 17.5f)

    private fun scoreRange(value: Float, t: RangeThreshold): Int = when {
        value in t.normalLow..t.normalHigh -> SCORE_OK
        value in t.warningLow..t.warningHigh -> SCORE_WARN
        value in t.criticalLow..t.criticalHigh -> SCORE_CRIT
        else -> SCORE_BAD
    }

    private fun scoreAbs(value: Float, ok: Float, warn: Float, crit: Float): Int {
        val v = abs(value)
        return when {
            v <= ok -> SCORE_OK
            v <= warn -> SCORE_WARN
            v <= crit -> SCORE_CRIT
            else -> SCORE_BAD
        }
    }

    /** 各子系統評分；無數據的子系統視為健康（rune 精神） */
    fun scoreSubsystems(data: ObdManager.LiveData): Map<Subsystem, Int> = mapOf(
        Subsystem.ENGINE to (data.load?.let { scoreRange(it.toFloat(), LOAD_T) } ?: SCORE_OK),
        Subsystem.FUEL to (data.fuelTrim?.let { scoreAbs(it, 10f, 15f, 25f) } ?: SCORE_OK),
        Subsystem.COOLING to (data.coolant?.let { scoreRange(it.toFloat(), COOLANT_T) } ?: SCORE_OK),
        Subsystem.ELECTRICAL to (data.voltage?.let { scoreRange(it, VOLTAGE_T) } ?: SCORE_OK),
        Subsystem.EMISSIONS to (data.afr?.let { scoreRange(it, AFR_T) } ?: SCORE_OK),
    )

    /** 加權總分（0-100），並依故障碼嚴重度扣分 */
    fun overallScore(subscores: Map<Subsystem, Int>, dtcCodes: List<String>): Int {
        var weighted = 0f
        Subsystem.entries.forEach { weighted += it.weight * (subscores[it] ?: SCORE_OK) }
        var penalty = 0
        dtcCodes.map { it.trim().uppercase() }.distinct().forEach { code ->
            penalty += when (ObdConstants.dtcSeverity(code)) {
                ObdConstants.DtcSeverity.CRITICAL -> 30
                ObdConstants.DtcSeverity.WARNING -> 15
                ObdConstants.DtcSeverity.NORMAL -> 5
            }
        }
        return (weighted - penalty).toInt().coerceIn(0, 100)
    }

    fun level(score: Int): HealthLevel = when {
        score >= 80 -> HealthLevel.GREEN
        score >= 60 -> HealthLevel.YELLOW
        else -> HealthLevel.RED
    }

    /** 多感測器交叉診斷規則（汽機車向；信賴度為規則強度） */
    val LIVE_RULES = listOf(
        LiveRule(R.string.hc_rule_overheat, R.string.hc_advice_overheat,
            DiagnosisEngine.Severity.CRITICAL, 0.9f) { it.coolant != null && it.coolant > 110 },
        LiveRule(R.string.hc_rule_coolant_high, R.string.hc_advice_coolant_high,
            DiagnosisEngine.Severity.WARNING, 0.8f) { it.coolant != null && it.coolant > 100 },
        LiveRule(R.string.hc_rule_cold_high_rpm, R.string.hc_advice_cold_high_rpm,
            DiagnosisEngine.Severity.WARNING, 0.6f) {
            it.rpm != null && it.coolant != null && it.rpm > 3000 && it.coolant < 60
        },
        LiveRule(R.string.hc_rule_low_voltage, R.string.hc_advice_low_voltage,
            DiagnosisEngine.Severity.WARNING, 0.7f) {
            it.voltage != null && it.rpm != null && it.voltage < 13.0f && it.rpm > 500
        },
        LiveRule(R.string.hc_rule_voltage_reg, R.string.hc_advice_voltage_reg,
            DiagnosisEngine.Severity.CRITICAL, 0.85f) { it.voltage != null && it.voltage > 15.5f },
        LiveRule(R.string.hc_rule_fuel_trim, R.string.hc_advice_fuel_trim,
            DiagnosisEngine.Severity.WARNING, 0.75f) { it.fuelTrim != null && abs(it.fuelTrim) > 15f },
        LiveRule(R.string.hc_rule_maf_low, R.string.hc_advice_maf_low,
            DiagnosisEngine.Severity.CRITICAL, 0.85f) {
            it.load != null && it.maf != null && it.load > 80 && it.maf < 10f
        },
        LiveRule(R.string.hc_rule_idle_high_load, R.string.hc_advice_idle_high_load,
            DiagnosisEngine.Severity.WARNING, 0.7f) {
            it.speed != null && it.load != null && it.rpm != null &&
                it.speed == 0 && it.load > 50 && it.rpm < 1200
        },
        LiveRule(R.string.hc_rule_lean, R.string.hc_advice_lean,
            DiagnosisEngine.Severity.WARNING, 0.65f) { it.afr != null && it.afr > 16.5f },
        LiveRule(R.string.hc_rule_rich, R.string.hc_advice_rich,
            DiagnosisEngine.Severity.WARNING, 0.65f) { it.afr != null && it.afr < 12.0f },
        LiveRule(R.string.hc_rule_overrev, R.string.hc_advice_overrev,
            DiagnosisEngine.Severity.CRITICAL, 0.9f) { it.rpm != null && it.rpm > 9000 },
    )

    fun runLiveRules(data: ObdManager.LiveData): List<LiveRule> =
        LIVE_RULES.filter { it.test(data) }

    data class FreezeFrameRule(
        @StringRes val titleRes: Int,
        @StringRes val adviceRes: Int,
        val severity: DiagnosisEngine.Severity,
        val confidence: Float,
        val test: (FreezeFrame) -> Boolean,
    )

    /** 凍結框診斷規則（mode 02 快照，values 的 key 為 pid_name 字串資源） */
    val FREEZE_RULES = listOf(
        FreezeFrameRule(R.string.ff_rule_overheat, R.string.ff_advice_overheat,
            DiagnosisEngine.Severity.CRITICAL, 0.85f) {
            it.values[R.string.pid_name_coolant]?.let { v -> v > 110 } == true
        },
        FreezeFrameRule(R.string.ff_rule_coolant_high, R.string.ff_advice_coolant_high,
            DiagnosisEngine.Severity.WARNING, 0.75f) {
            it.values[R.string.pid_name_coolant]?.let { v -> v in 101..110 } == true
        },
        FreezeFrameRule(R.string.ff_rule_high_load, R.string.ff_advice_high_load,
            DiagnosisEngine.Severity.WARNING, 0.7f) {
            val load = it.values[R.string.pid_name_load]
            val speed = it.values[R.string.pid_name_speed]
            load != null && speed != null && load > 80 && speed < 30
        },
        FreezeFrameRule(R.string.ff_rule_overrev, R.string.ff_advice_overrev,
            DiagnosisEngine.Severity.CRITICAL, 0.9f) {
            it.values[R.string.pid_name_rpm]?.let { v -> v > 8500 } == true
        },
        FreezeFrameRule(R.string.ff_rule_cold_high_rpm, R.string.ff_advice_cold_high_rpm,
            DiagnosisEngine.Severity.WARNING, 0.6f) {
            val coolant = it.values[R.string.pid_name_coolant]
            val rpm = it.values[R.string.pid_name_rpm]
            coolant != null && rpm != null && coolant < 60 && rpm > 3000
        },
    )

    fun runFreezeFrameRules(frame: FreezeFrame): List<FreezeFrameRule> =
        FREEZE_RULES.filter { it.test(frame) }
}

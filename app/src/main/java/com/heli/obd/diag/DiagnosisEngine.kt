package com.heli.obd.diag

import androidx.annotation.StringRes
import com.heli.obd.R

/**
 * 診斷知識庫與規則引擎（離線，不需網路）。
 *
 * 輸入：勾選症狀 + 故障碼（DTC）清單
 * 評分：症狀命中 ×2、DTC 前綴命中 ×3；得分 > 0 的規則依分數排序輸出。
 */
object DiagnosisEngine {

    data class Symptom(val id: Int, @StringRes val labelRes: Int)

    enum class Severity { NORMAL, WARNING, CRITICAL }

    data class Rule(
        @StringRes val titleRes: Int,
        @StringRes val adviceRes: Int,
        val severity: Severity,
        val symptomIds: Set<Int>,
        val dtcPrefixes: List<String>,
    )

    data class Result(val rule: Rule, val confidence: Float)

    val SYMPTOMS = listOf(
        Symptom(1, R.string.diag_symptom_start_hard),
        Symptom(2, R.string.diag_symptom_idle_unstable),
        Symptom(3, R.string.diag_symptom_lack_power),
        Symptom(4, R.string.diag_symptom_fuel_up),
        Symptom(5, R.string.diag_symptom_exhaust_abnormal),
        Symptom(6, R.string.diag_symptom_vibration),
        Symptom(7, R.string.diag_symptom_overheating),
        Symptom(8, R.string.diag_symptom_noise),
        Symptom(9, R.string.diag_symptom_stall),
        Symptom(10, R.string.diag_symptom_mil),
    )

    private val RULES = listOf(
        Rule(
            titleRes = R.string.diag_rule_ignition_title,
            adviceRes = R.string.diag_rule_ignition_advice,
            severity = Severity.CRITICAL,
            symptomIds = setOf(1, 3, 6, 9),
            dtcPrefixes = listOf("P030"),
        ),
        Rule(
            titleRes = R.string.diag_rule_fuel_title,
            adviceRes = R.string.diag_rule_fuel_advice,
            severity = Severity.WARNING,
            symptomIds = setOf(2, 3, 4),
            dtcPrefixes = listOf("P017"),
        ),
        Rule(
            titleRes = R.string.diag_rule_cooling_title,
            adviceRes = R.string.diag_rule_cooling_advice,
            severity = Severity.CRITICAL,
            symptomIds = setOf(7),
            dtcPrefixes = listOf("P011", "P012"),
        ),
        Rule(
            titleRes = R.string.diag_rule_intake_title,
            adviceRes = R.string.diag_rule_intake_advice,
            severity = Severity.WARNING,
            symptomIds = setOf(1, 2),
            dtcPrefixes = listOf("P010"),
        ),
        Rule(
            titleRes = R.string.diag_rule_emission_title,
            adviceRes = R.string.diag_rule_emission_advice,
            severity = Severity.NORMAL,
            symptomIds = setOf(4, 5),
            dtcPrefixes = listOf("P013", "P042", "P043"),
        ),
        Rule(
            titleRes = R.string.diag_rule_battery_title,
            adviceRes = R.string.diag_rule_battery_advice,
            severity = Severity.WARNING,
            symptomIds = setOf(1),
            dtcPrefixes = listOf("P056"),
        ),
        Rule(
            titleRes = R.string.diag_rule_drivetrain_title,
            adviceRes = R.string.diag_rule_drivetrain_advice,
            severity = Severity.NORMAL,
            symptomIds = setOf(3, 8),
            dtcPrefixes = emptyList(),
        ),
        Rule(
            titleRes = R.string.diag_rule_brake_title,
            adviceRes = R.string.diag_rule_brake_advice,
            severity = Severity.CRITICAL,
            symptomIds = setOf(8),
            dtcPrefixes = emptyList(),
        ),
    )

    /** 確定性因子組合（AutoSleuth 方法論）：CF1 + CF2 × (1 - CF1) */
    private fun combineCf(a: Float, b: Float): Float = a + b * (1f - a)

    private fun ruleCf(severity: Severity): Float = when (severity) {
        Severity.CRITICAL -> 0.95f
        Severity.WARNING -> 0.85f
        Severity.NORMAL -> 0.7f
    }

    fun diagnose(selectedSymptoms: Set<Int>, dtcCodes: List<String>): List<Result> {
        val dtcUpper = dtcCodes.map { it.trim().uppercase() }
        return RULES.map { rule ->
            val symptomHits = rule.symptomIds.count { it in selectedSymptoms }
            val dtcHits = dtcUpper.count { code ->
                rule.dtcPrefixes.any { code.startsWith(it) }
            }
            if (symptomHits == 0 && dtcHits == 0) return@map null
            val symptomCf = minOf(1f, 0.5f + 0.15f * symptomHits)
            val dtcCf = minOf(1f, 0.4f + 0.25f * dtcHits)
            val evidence = when {
                symptomHits > 0 && dtcHits > 0 -> combineCf(symptomCf, dtcCf)
                dtcHits > 0 -> dtcCf
                else -> symptomCf
            }
            Result(rule, evidence * ruleCf(rule.severity))
        }
            .filterNotNull()
            .filter { it.confidence > 0.2f }
            .sortedByDescending { it.confidence }
    }
}

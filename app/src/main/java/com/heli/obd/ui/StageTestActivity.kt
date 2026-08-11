/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.ObdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 多階段測試助理：
 *  階段一 RPM 階梯測試（目標轉速穩定性）
 *  階段二 燃油修正測試（怠速/2500 RPM 燃油修正值）
 *  階段三 O2 響應測試（AFR 對油門變化響應）
 */
class StageTestActivity : BaseActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var phaseLabel: TextView
    private lateinit var phaseTitle: TextView
    private lateinit var phaseDesc: TextView
    private lateinit var currentText: TextView
    private lateinit var detailText: TextView
    private lateinit var resultText: TextView
    private lateinit var btnAction: Button

    private var phase = 0
    private var sampling = false

    /** 階段一目標轉速階梯 */
    private val rpmTargets = listOf(1500, 2500, 3500)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stage_test)

        phaseLabel = findViewById(R.id.stage_phase_label)
        phaseTitle = findViewById(R.id.stage_phase_title)
        phaseDesc = findViewById(R.id.stage_phase_desc)
        currentText = findViewById(R.id.stage_current)
        detailText = findViewById(R.id.stage_detail)
        resultText = findViewById(R.id.stage_result)
        btnAction = findViewById(R.id.btn_stage_action)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnAction.setOnClickListener {
            if (sampling) return@setOnClickListener
            if (phase < 3) startPhaseSampling() else finish()
        }
    }

    private fun startPhaseSampling() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        sampling = true
        btnAction.isEnabled = false
        resultText.visibility = View.GONE
        detailText.text = ""

        lifecycleScope.launch {
            when (phase) {
                0 -> sampleRpmStability()
                1 -> sampleFuelTrim()
                2 -> sampleO2Response()
            }
            sampling = false
            btnAction.isEnabled = true
            btnAction.setText(
                if (phase >= 3) R.string.stage_done else R.string.stage_next
            )
        }
    }

    /** 階段一：RPM 階梯 — 對每個目標轉速採樣，計算偏差與穩定度 */
    private suspend fun sampleRpmStability() {
        for (target in rpmTargets) {
            val samples = mutableListOf<Int>()
            repeat(10) {
                val data = withContext(Dispatchers.IO) { obd.requestLiveData() }
                data?.rpm?.let { samples.add(it) }
                currentText.text = getString(R.string.stage_rpm_current, data?.rpm ?: 0)
                delay(200)
            }
            if (samples.isEmpty()) continue
            val max = samples.maxOrNull() ?: 0
            val min = samples.minOrNull() ?: 0
            val deviation = max - min
            val stable = deviation <= 150
            detailText.text = getString(
                if (stable) R.string.stage_rpm_stable else R.string.stage_rpm_unstable,
                deviation,
            )
            if (!stable) {
                showResult(R.string.stage_result_warn, R.color.accent)
                return
            }
        }
        showResult(R.string.stage_result_pass, R.color.success)
    }

    /** 階段二：燃油修正 — 平均燃油修正值判定濃/稀 */
    private suspend fun sampleFuelTrim() {
        val samples = mutableListOf<Float>()
        repeat(15) {
            val data = withContext(Dispatchers.IO) { obd.requestLiveData() }
            data?.fuelTrim?.let { samples.add(it) }
            currentText.text = getString(
                R.string.stage_fuel_trim_value,
                data?.fuelTrim ?: 0f,
            )
            delay(200)
        }
        if (samples.isEmpty()) {
            showResult(R.string.stage_result_fail, R.color.danger)
            return
        }
        val avg = samples.average().toFloat()
        currentText.text = getString(R.string.stage_fuel_trim_value, avg)
        val (resRes, colorRes) = when {
            kotlin.math.abs(avg) <= 5f -> R.string.stage_fuel_trim_ok to R.color.success
            avg > 0f -> R.string.stage_fuel_trim_rich to R.color.accent
            else -> R.string.stage_fuel_trim_lean to R.color.accent
        }
        detailText.text = getString(resRes)
        showResult(
            if (kotlin.math.abs(avg) <= 5f) R.string.stage_result_pass
            else if (kotlin.math.abs(avg) <= 10f) R.string.stage_result_warn
            else R.string.stage_result_fail,
            colorRes,
        )
    }

    /** 階段三：O2 響應 — AFR 變化範圍判定響應速度 */
    private suspend fun sampleO2Response() {
        val samples = mutableListOf<Float>()
        repeat(15) {
            val data = withContext(Dispatchers.IO) { obd.requestLiveData() }
            data?.afr?.let { samples.add(it) }
            currentText.text = getString(R.string.stage_o2_afr, data?.afr ?: 0f)
            delay(200)
        }
        if (samples.isEmpty()) {
            showResult(R.string.stage_result_fail, R.color.danger)
            return
        }
        val max = samples.maxOrNull() ?: 0f
        val min = samples.minOrNull() ?: 0f
        val range = max - min
        val responsive = range >= 0.3f
        detailText.text = getString(
            if (responsive) R.string.stage_o2_response_ok else R.string.stage_o2_response_slow,
        )
        showResult(
            if (responsive) R.string.stage_result_pass else R.string.stage_result_warn,
            if (responsive) R.color.success else R.color.accent,
        )
    }

    private fun showResult(resultRes: Int, colorRes: Int) {
        resultText.text = getString(resultRes)
        resultText.setTextColor(getColor(colorRes))
        resultText.visibility = View.VISIBLE
        phase++
        if (phase < 3) {
            phaseLabel.text = getString(R.string.stage_phase_n, phase + 1)
            phaseTitle.setText(
                when (phase) {
                    1 -> R.string.stage_phase2_title
                    else -> R.string.stage_phase3_title
                },
            )
            phaseDesc.setText(
                when (phase) {
                    1 -> R.string.stage_phase2_desc
                    else -> R.string.stage_phase3_desc
                },
            )
            currentText.text = ""
        }
    }
}

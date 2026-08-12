/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.diag.DiagnosisEngine
import com.heli.obd.diag.HealthCheckEngine
import com.heli.obd.elm.FreezeFrame
import com.heli.obd.elm.ObdConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 凍結框（Mode 02）：故障觸發當下的關鍵感測器快照 + 診斷建議。
 */
class FreezeFrameActivity : BaseActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)
    private val unitSystem get() = UnitSystem.load(this)

    private lateinit var triggerText: TextView
    private lateinit var triggerDescText: TextView
    private lateinit var valueList: LinearLayout
    private lateinit var diagList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_freeze_frame)

        triggerText = findViewById(R.id.freeze_trigger)
        triggerDescText = findViewById(R.id.freeze_trigger_desc)
        valueList = findViewById(R.id.freeze_value_list)
        diagList = findViewById(R.id.freeze_diag_list)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_freeze_refresh).setOnClickListener { load() }
        load()
    }

    private fun load() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        findViewById<Button>(R.id.btn_freeze_refresh).isEnabled = false
        lifecycleScope.launch {
            val freeze = withContext(Dispatchers.IO) { obd.readFreezeFrame() }
            findViewById<Button>(R.id.btn_freeze_refresh).isEnabled = true
            if (freeze == null) {
                Toast.makeText(this@FreezeFrameActivity, R.string.diag_freeze_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            render(freeze)
        }
    }

    private fun render(freeze: FreezeFrame) {
        val trigger = freeze.triggerDtc
        if (trigger != null) {
            triggerText.text = trigger
            triggerDescText.text = runCatching {
                getString(ObdConstants.dtcDescriptionRes(trigger), trigger)
            }.getOrDefault(trigger)
        } else {
            triggerText.text = getString(R.string.pro_diag_none)
            triggerDescText.text = ""
        }

        valueList.removeAllViews()
        val units = UnitSystem.load(this)
        freeze.values.forEach { (labelRes, value) ->
            addValueRow(getString(labelRes), value, unitFor(labelRes, value, units))
        }
        freeze.floatValues.forEach { (labelRes, value) ->
            addValueRow(getString(labelRes), value, floatUnitFor(labelRes, value, units))
        }
        if (freeze.values.isEmpty() && freeze.floatValues.isEmpty()) {
            addValueRow(getString(R.string.diag_freeze_none), null, "")
        }

        renderDiag(HealthCheckEngine.runFreezeFrameRules(freeze))
    }

    private fun unitFor(labelRes: Int, value: Int?, units: UnitSystem): String {
        return when (labelRes) {
            R.string.pid_name_coolant -> units.temp(value?.toFloat() ?: return "—").format1() + " " + units.tempUnit()
            R.string.pid_name_rpm -> (value ?: return "—").toString() + " RPM"
            R.string.pid_name_speed -> units.speed(value?.toFloat() ?: return "—").format1() + " " + units.speedUnit()
            R.string.pid_name_load, R.string.pid_name_throttle,
            R.string.pid_name_fuel_level -> (value ?: return "—").toString() + " %"
            R.string.pid_name_intake -> units.temp(value?.toFloat() ?: return "—").format1() + " " + units.tempUnit()
            R.string.pid_name_map -> (value ?: return "—").toString() + " kPa"
            else -> value?.toString() ?: "—"
        }
    }

    private fun floatUnitFor(labelRes: Int, value: Float?, units: UnitSystem): String {
        return when (labelRes) {
            R.string.pid_name_maf -> units.maf(value ?: return "—").format2() + " " + units.mafUnit()
            R.string.pid_name_timing_advance -> (value ?: return "—").format1() + " °"
            R.string.pid_name_module_voltage -> (value ?: return "—").format2() + " V"
            R.string.pid_name_fuel_trim -> (value ?: return "—").format1() + " %"
            R.string.pid_name_afr -> (value ?: return "—").format2() + " AFR"
            else -> value?.toString() ?: "—"
        }
    }

    private fun Float.format1(): String = String.format(java.util.Locale.US, "%.1f", this)
    private fun Float.format2(): String = String.format(java.util.Locale.US, "%.2f", this)

    private fun addValueRow(name: String, value: Any?, display: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }

        row.addView(
            TextView(this).apply {
                text = name
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(this).apply {
                text = if (value == null) "—" else display
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        valueList.addView(row, params)
    }

    private fun renderDiag(rules: List<HealthCheckEngine.FreezeFrameRule>) {
        diagList.removeAllViews()
        if (rules.isEmpty()) {
            diagList.addView(
                TextView(this).apply {
                    text = getString(R.string.ff_normal)
                    textSize = 13f
                    setTextColor(getColor(R.color.success))
                    setPadding(0, dp(4), 0, 0)
                }
            )
            return
        }
        rules.forEach { rule ->
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(
                TextView(this).apply {
                    text = getString(rule.titleRes)
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(severityColorRes(rule.severity)))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            titleRow.addView(
                TextView(this).apply {
                    text = getString(R.string.hc_confidence, (rule.confidence * 100).toInt())
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                }
            )
            diagList.addView(titleRow)
            diagList.addView(
                TextView(this).apply {
                    text = getString(rule.adviceRes)
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, dp(2), 0, 0)
                }
            )
        }
    }

    private fun severityColorRes(severity: DiagnosisEngine.Severity): Int = when (severity) {
        DiagnosisEngine.Severity.CRITICAL -> R.color.danger
        DiagnosisEngine.Severity.WARNING -> R.color.amber
        DiagnosisEngine.Severity.NORMAL -> R.color.success
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

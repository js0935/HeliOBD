package com.heli.obd.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.diag.DiagnosisEngine

/**
 * AI 診斷：勾選症狀 + 故障碼，由離線規則引擎輸出診斷建議。
 */
class AiDiagnoseActivity : AppCompatActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var symptomGrid: GridLayout
    private lateinit var dtcInput: EditText
    private lateinit var dtcListText: TextView
    private lateinit var resultContainer: LinearLayout

    private val selectedSymptoms = mutableSetOf<Int>()
    private val dtcCodes = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_diagnose)

        symptomGrid = findViewById(R.id.symptom_grid)
        dtcInput = findViewById(R.id.dtc_input)
        dtcListText = findViewById(R.id.dtc_list)
        resultContainer = findViewById(R.id.result_container)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_read_dtc).setOnClickListener { readDtcFromObd() }
        findViewById<Button>(R.id.btn_add_dtc).setOnClickListener { addManualDtc() }
        findViewById<Button>(R.id.btn_diagnose).setOnClickListener { runDiagnosis() }

        buildSymptomChips()
    }

    private fun buildSymptomChips() {
        DiagnosisEngine.SYMPTOMS.forEach { symptom ->
            val chip = TextView(this)
            chip.text = getString(symptom.labelRes)
            chip.textSize = 14f
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(8), dp(10), dp(8), dp(10))
            chip.isClickable = true
            chip.isFocusable = true
            chip.setBackgroundResource(R.drawable.bg_card)
            chip.setTextColor(getColor(R.color.text_primary))

            val lp = GridLayout.LayoutParams()
            lp.width = 0
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            chip.layoutParams = lp

            chip.setOnClickListener {
                if (selectedSymptoms.add(symptom.id)) {
                    chip.setBackgroundResource(R.drawable.bg_button_accent)
                    chip.setTextColor(Color.WHITE)
                } else {
                    selectedSymptoms.remove(symptom.id)
                    chip.setBackgroundResource(R.drawable.bg_card)
                    chip.setTextColor(getColor(R.color.text_primary))
                }
            }
            symptomGrid.addView(chip)
        }
    }

    private fun readDtcFromObd() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_LONG).show()
            return
        }
        val codes = obd.readDtc()
        if (codes.isEmpty()) {
            Toast.makeText(this, R.string.ai_diag_no_dtc, Toast.LENGTH_LONG).show()
            return
        }
        codes.forEach {
            if (it !in dtcCodes) dtcCodes.add(it)
        }
        renderDtcList()
    }

    private fun addManualDtc() {
        val text = dtcInput.text.toString().trim().uppercase()
        if (text.isEmpty()) return
        // 接受單個或多個以逗號/空白分隔的 DTC
        text.split(Regex("[,\\s]+")).filter { it.isNotBlank() }.forEach {
            if (it !in dtcCodes) dtcCodes.add(it)
        }
        dtcInput.text.clear()
        renderDtcList()
    }

    private fun renderDtcList() {
        dtcListText.text = if (dtcCodes.isEmpty()) {
            getString(R.string.ai_diag_dtc_hint)
        } else {
            getString(R.string.ai_diag_selected_dtc) + "：" + dtcCodes.joinToString("、")
        }
    }

    private fun runDiagnosis() {
        if (selectedSymptoms.isEmpty() && dtcCodes.isEmpty()) {
            Toast.makeText(this, R.string.ai_diag_empty, Toast.LENGTH_LONG).show()
            return
        }
        resultContainer.removeAllViews()
        val results = DiagnosisEngine.diagnose(selectedSymptoms, dtcCodes)
        if (results.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.ai_diag_no_result)
            empty.textSize = 15f
            empty.setTextColor(getColor(R.color.success))
            empty.setPadding(0, dp(10), 0, 0)
            resultContainer.addView(empty)
            return
        }
        results.forEach { addResultCard(it.rule, it.rule.severity, it.confidence) }
    }

    private fun addResultCard(
        rule: DiagnosisEngine.Rule,
        severity: DiagnosisEngine.Severity,
        confidence: Float,
    ) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        card.setBackgroundResource(R.drawable.bg_card)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(8)
        card.layoutParams = lp

        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(this)
        title.text = getString(rule.titleRes)
        title.textSize = 16f
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setTextColor(
            when (severity) {
                DiagnosisEngine.Severity.CRITICAL -> getColor(R.color.danger)
                DiagnosisEngine.Severity.WARNING -> getColor(R.color.lock)
                DiagnosisEngine.Severity.NORMAL -> getColor(R.color.success)
            }
        )
        titleRow.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val confidenceText = TextView(this)
        confidenceText.text = getString(R.string.ai_diag_confidence, (confidence * 100).toInt())
        confidenceText.textSize = 13f
        confidenceText.setTextColor(getColor(R.color.text_secondary))
        titleRow.addView(confidenceText)

        card.addView(titleRow)

        val advice = TextView(this)
        advice.text = getString(rule.adviceRes)
        advice.textSize = 14f
        advice.setTextColor(getColor(R.color.text_secondary))
        advice.setPadding(0, dp(6), 0, 0)
        card.addView(advice)

        resultContainer.addView(card)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

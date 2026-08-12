/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
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
import androidx.lifecycle.lifecycleScope
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.diag.DiagnosisEngine
import com.heli.obd.llm.LlmClient
import com.heli.obd.llm.LlmStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 診斷：勾選症狀 + 故障碼，由離線規則引擎輸出診斷建議。
 */
class AiDiagnoseActivity : BaseActivity() {

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
        findViewById<Button>(R.id.btn_llm_diagnose).setOnClickListener { runLlmDiagnosis() }

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

    private fun runLlmDiagnosis() {
        if (selectedSymptoms.isEmpty() && dtcCodes.isEmpty()) {
            Toast.makeText(this, R.string.ai_diag_empty, Toast.LENGTH_LONG).show()
            return
        }
        if (!LlmStore.isConfigured(this)) {
            Toast.makeText(this, R.string.llm_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        val config = LlmStore.load(this)
        val symptomNames = DiagnosisEngine.SYMPTOMS
            .filter { it.id in selectedSymptoms }
            .map { getString(it.labelRes) }
        val codes = dtcCodes.toList()

        resultContainer.removeAllViews()
        val loading = TextView(this)
        loading.text = getString(R.string.llm_analyzing)
        loading.textSize = 15f
        loading.setTextColor(getColor(R.color.text_secondary))
        loading.setPadding(0, dp(10), 0, 0)
        resultContainer.addView(loading)

        lifecycleScope.launch {
            resultContainer.removeAllViews()
            try {
                val answer = withContext(Dispatchers.IO) {
                    val liveSummary = if (obd.isConnected()) {
                        obd.requestLiveData()?.let { data ->
                            "RPM=${data.rpm} 車速=${data.speed} 水溫=${data.coolant} 電壓=${data.voltage}"
                        }
                    } else null
                    LlmClient.chat(
                        config = config,
                        systemPrompt = LLM_SYSTEM_PROMPT,
                        userPrompt = LlmClient.buildDiagnosisPrompt(codes, symptomNames, liveSummary),
                    )
                }
                addLlmResultCard(answer)
            } catch (e: Exception) {
                showLlmError(e.message ?: "unknown")
            }
        }
    }

    private fun showLlmError(message: String) {
        val err = TextView(this)
        err.text = getString(R.string.llm_analyze_failed, message)
        err.textSize = 14f
        err.setTextColor(getColor(R.color.danger))
        err.setPadding(0, dp(10), 0, 0)
        resultContainer.addView(err)
    }

    private fun addLlmResultCard(answer: String) {
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

        val title = TextView(this)
        title.text = getString(R.string.llm_analyze_btn)
        title.textSize = 15f
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setTextColor(getColor(R.color.primary))
        card.addView(title)

        val body = TextView(this)
        body.text = answer
        body.textSize = 14f
        body.setTextColor(getColor(R.color.text_primary))
        body.setPadding(0, dp(8), 0, 0)
        card.addView(body)

        resultContainer.addView(card)
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

    companion object {
        private const val LLM_SYSTEM_PROMPT =
            "你是專業汽車維修技師。請用繁體中文、以車主能理解的白話解釋以下故障碼與症狀：" +
            "每個故障碼說明 1) 它的含義 2) 可能原因 3) 建議檢查與維修方向 4) 緊急程度。請條列式輸出，不要使用 Markdown 表格。"
    }
}

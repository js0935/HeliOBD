/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.heli.obd.BaseActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.diag.DiagnosisEngine
import com.heli.obd.diag.HealthCheckEngine
import com.heli.obd.elm.ObdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 健康檢查：即時數據 → 子系統階梯評分 → 綠/黃/紅總覽 + 交叉診斷規則。
 */
class HealthCheckActivity : BaseActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var statusText: TextView
    private lateinit var totalScoreText: TextView
    private lateinit var totalLevelText: TextView
    private lateinit var subsystemContainer: LinearLayout
    private lateinit var rulesContainer: LinearLayout
    private lateinit var refreshBtn: Button

    private var latestData: ObdManager.LiveData? = null
    private var dtcCodes: List<String> = emptyList()
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health_check)

        statusText = findViewById(R.id.hc_status_text)
        totalScoreText = findViewById(R.id.hc_total_score)
        totalLevelText = findViewById(R.id.hc_total_level)
        subsystemContainer = findViewById(R.id.hc_subsystems)
        rulesContainer = findViewById(R.id.hc_rules)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        refreshBtn = findViewById(R.id.btn_refresh)
        refreshBtn.setOnClickListener { refreshAll() }

        obd.addListener(this)
        refreshAll()
    }

    override fun onDestroy() {
        obd.removeListener(this)
        super.onDestroy()
    }

    override fun onStateChanged(state: ObdManager.State) {
        statusText.text = getString(
            if (state == ObdManager.State.Ready) R.string.obd_connected else R.string.obd_disconnected
        )
    }

    override fun onLiveData(data: ObdManager.LiveData) {
        latestData = data
        render()
    }

    private fun refreshAll() {
        if (loading) return
        loading = true
        val busy = BusyUi.mark(refreshBtn, getString(R.string.busy_reading))
        lifecycleScope.launch {
            try {
                val (data, codes) = withContext(Dispatchers.IO) {
                    val d = obd.requestLiveData()
                    val c = if (obd.isConnected()) obd.readDtc() else emptyList()
                    d to c
                }
                latestData = data
                dtcCodes = codes
                render()
                if (data == null) {
                    Toast.makeText(this@HealthCheckActivity, R.string.hc_no_data, Toast.LENGTH_LONG).show()
                }
            } finally {
                loading = false
                busy.done()
            }
        }
    }

    private fun render() {
        val data = latestData ?: return
        val subs = HealthCheckEngine.scoreSubsystems(data)
        val overall = HealthCheckEngine.overallScore(subs, dtcCodes)
        val level = HealthCheckEngine.level(overall)
        val levelColor = levelColorRes(level)

        totalScoreText.text = String.format(Locale.US, "%d", overall)
        totalScoreText.setTextColor(getColor(levelColor))
        totalLevelText.text = getString(level.labelRes)
        totalLevelText.setTextColor(getColor(levelColor))
        renderSubsystems(subs)
        renderRules(HealthCheckEngine.runLiveRules(data))
    }

    private fun renderSubsystems(subs: Map<HealthCheckEngine.Subsystem, Int>) {
        subsystemContainer.removeAllViews()
        HealthCheckEngine.Subsystem.entries.forEach { sub ->
            val score = subs[sub] ?: 0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(
                TextView(this).apply {
                    text = getString(sub.labelRes)
                    textSize = 14f
                    setTextColor(getColor(R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.55f)
                }
            )
            row.addView(
                ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = score
                    progressTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(this@HealthCheckActivity, scoreColorRes(score))
                    )
                    layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f).apply { marginStart = dp(10) }
                }
            )
            row.addView(
                TextView(this).apply {
                    text = String.format(Locale.US, "%d", score)
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(scoreColorRes(score)))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(10) }
                }
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (subsystemContainer.childCount > 0) lp.topMargin = dp(8)
            subsystemContainer.addView(row, lp)
        }
    }

    private fun renderRules(rules: List<HealthCheckEngine.LiveRule>) {
        rulesContainer.removeAllViews()
        if (rules.isEmpty()) {
            rulesContainer.addView(
                TextView(this).apply {
                    text = getString(R.string.hc_healthy)
                    textSize = 15f
                    setTextColor(getColor(R.color.success))
                    setPadding(0, dp(6), 0, 0)
                }
            )
            return
        }
        rules.forEach { rulesContainer.addView(buildRuleCard(it)) }
    }

    private fun buildRuleCard(rule: HealthCheckEngine.LiveRule): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_card)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        card.layoutParams = lp

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(this).apply {
                text = getString(rule.titleRes)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(severityColorRes(rule.severity)))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        titleRow.addView(
            TextView(this).apply {
                text = getString(R.string.hc_confidence, (rule.confidence * 100).toInt())
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
            }
        )
        card.addView(titleRow)
        card.addView(
            TextView(this).apply {
                text = getString(rule.adviceRes)
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(6), 0, 0)
            }
        )
        return card
    }

    private fun severityColorRes(severity: DiagnosisEngine.Severity): Int = when (severity) {
        DiagnosisEngine.Severity.CRITICAL -> R.color.danger
        DiagnosisEngine.Severity.WARNING -> R.color.amber
        DiagnosisEngine.Severity.NORMAL -> R.color.success
    }

    private fun scoreColorRes(score: Int): Int = when {
        score >= 85 -> R.color.success
        score >= 60 -> R.color.amber
        else -> R.color.danger
    }

    private fun levelColorRes(level: HealthCheckEngine.HealthLevel): Int = when (level) {
        HealthCheckEngine.HealthLevel.GREEN -> R.color.success
        HealthCheckEngine.HealthLevel.YELLOW -> R.color.amber
        HealthCheckEngine.HealthLevel.RED -> R.color.danger
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

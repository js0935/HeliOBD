/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.ImReadiness
import com.heli.obd.elm.ObdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 驗車準備：彙整 I/M 排放監測器就緒狀態與故障碼，
 * 預測是否可通過定期檢驗，並針對未就緒監測器提供驅動週期引導。
 */
class SmogCheckActivity : BaseActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var verdictText: TextView
    private lateinit var verdictDesc: TextView
    private lateinit var milText: TextView
    private lateinit var monitorCountText: TextView
    private lateinit var monitorContainer: LinearLayout
    private lateinit var driveContainer: LinearLayout
    private lateinit var driveHeader: TextView

    /** 與 ObdDecoder.imReadiness 的監測器順序對應的行駛建議 */
    private val driveHints = intArrayOf(
        R.string.smog_hint_misfire,
        R.string.smog_hint_fuel,
        R.string.smog_hint_components,
        R.string.smog_hint_catalyst,
        R.string.smog_hint_heated_catalyst,
        R.string.smog_hint_evap,
        R.string.smog_hint_secondary_air,
        R.string.smog_hint_ac,
        R.string.smog_hint_o2,
        R.string.smog_hint_o2_heater,
        R.string.smog_hint_egr,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smog_check)

        verdictText = findViewById(R.id.smog_verdict_text)
        verdictDesc = findViewById(R.id.smog_verdict_desc)
        milText = findViewById(R.id.smog_mil)
        monitorCountText = findViewById(R.id.smog_monitor_count)
        monitorContainer = findViewById(R.id.smog_monitors)
        driveContainer = findViewById(R.id.smog_drive_cycle)
        driveHeader = findViewById(R.id.smog_drive_header)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_smog_check).setOnClickListener { check() }
        check()
    }

    private fun check() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        val btn = findViewById<Button>(R.id.btn_smog_check)
        val busy = BusyUi.mark(btn, getString(R.string.busy_checking))
        lifecycleScope.launch {
            try {
                val (im, dtcCodes) = withContext(Dispatchers.IO) {
                    obd.readImReadiness() to obd.readDtc()
                }
                if (im == null) {
                    verdictText.text = getString(R.string.smog_no_data)
                    verdictText.setTextColor(getColor(R.color.text_secondary))
                    return@launch
                }
                render(im, dtcCodes)
            } finally {
                busy.done()
            }
        }
    }

    private fun render(im: ImReadiness, dtcCodes: List<String>) {
        milText.text = getString(
            R.string.smog_mil,
            getString(if (im.milOn) R.string.smog_mil_on else R.string.smog_mil_off),
        )
        monitorCountText.text = resources.getQuantityString(R.plurals.smog_ready_count, im.readyCount, im.readyCount, im.supportedCount)

        // 判決：MIL/故障碼 → 不可驗車；未就緒 > 2 → 需驅動週期；否則可驗車
        val notReady = im.tests.count { it.supported && !it.ready }
        when {
            im.milOn || dtcCodes.isNotEmpty() -> {
                verdictText.text = getString(R.string.smog_fail)
                verdictText.setTextColor(getColor(R.color.danger))
                verdictDesc.text = getString(R.string.smog_fail_desc)
            }
            notReady > 2 -> {
                verdictText.text = getString(R.string.smog_wait)
                verdictText.setTextColor(getColor(R.color.amber))
                verdictDesc.text = resources.getQuantityString(R.plurals.smog_wait_desc, notReady, notReady)
            }
            else -> {
                verdictText.text = getString(R.string.smog_pass)
                verdictText.setTextColor(getColor(R.color.success))
                verdictDesc.text = getString(R.string.smog_pass_desc)
            }
        }

        renderMonitors(im)
        renderDriveCycle(im)
    }

    private fun renderMonitors(im: ImReadiness) {
        monitorContainer.removeAllViews()
        im.tests.forEach { test ->
            val (label, colorRes) = when {
                !test.supported -> "${getString(test.nameRes)}：${getString(R.string.diag_im_unsupported)}" to R.color.text_secondary
                test.ready -> "${getString(test.nameRes)}：${getString(R.string.diag_im_ready)}" to R.color.success
                else -> "${getString(test.nameRes)}：${getString(R.string.diag_im_not_ready)}" to R.color.danger
            }
            monitorContainer.addView(
                TextView(this).apply {
                    text = label
                    setTextColor(getColor(colorRes))
                    textSize = 14f
                    setPadding(0, dp(2), 0, dp(2))
                }
            )
        }
    }

    /** 僅針對未就緒監測器列出驅動週期建議 */
    private fun renderDriveCycle(im: ImReadiness) {
        driveContainer.removeAllViews()
        val notReady = im.tests.filter { it.supported && !it.ready }
        if (notReady.isEmpty()) {
            driveHeader.visibility = View.GONE
            driveContainer.addView(
                TextView(this).apply {
                    text = getString(R.string.smog_drive_cycle_desc)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 13f
                }
            )
            return
        }
        driveHeader.visibility = View.VISIBLE
        driveContainer.addView(
            TextView(this).apply {
                text = getString(R.string.smog_drive_cycle_desc)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
            }
        )
        notReady.forEach { test ->
            val idx = im.tests.indexOf(test)
            val hintRes = if (idx in driveHints.indices) driveHints[idx] else R.string.smog_hint_misfire
            driveContainer.addView(
                TextView(this).apply {
                    text = String.format(Locale.US, "• %s：%s", getString(test.nameRes), getString(hintRes))
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 14f
                    setPadding(0, dp(6), 0, 0)
                }
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

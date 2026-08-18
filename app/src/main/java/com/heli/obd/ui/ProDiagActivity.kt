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
import com.heli.obd.elm.FreezeFrame
import com.heli.obd.elm.ImReadiness
import com.heli.obd.elm.MonitorTest
import com.heli.obd.elm.ObdDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 專業診斷：VIN / 排放就緒 / 凍結幀 / Mode 06 監控測試 / 故障碼三態對照。
 */
class ProDiagActivity : BaseActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private data class ProDiagData(
        val vin: String?,
        val calibrationId: String?,
        val cvn: String?,
        val readiness: ImReadiness?,
        val freezeFrame: FreezeFrame?,
        val monitorTests: List<MonitorTest>,
        val confirmed: List<String>,
        val pending: List<String>,
        val permanent: List<String>,
    )

    private lateinit var vinText: TextView
    private lateinit var calIdText: TextView
    private lateinit var cvnText: TextView
    private lateinit var milText: TextView
    private lateinit var dtcCountText: TextView
    private lateinit var readyProgressText: TextView
    private lateinit var freezeTriggerText: TextView
    private lateinit var readinessList: LinearLayout
    private lateinit var freezeList: LinearLayout
    private lateinit var monitorList: LinearLayout
    private lateinit var confirmedList: LinearLayout
    private lateinit var pendingList: LinearLayout
    private lateinit var permanentList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pro_diag)

        vinText = findViewById(R.id.pro_vin)
        calIdText = findViewById(R.id.pro_calibration)
        cvnText = findViewById(R.id.pro_cvn)
        milText = findViewById(R.id.pro_mil)
        dtcCountText = findViewById(R.id.pro_dtc_count)
        readyProgressText = findViewById(R.id.pro_ready_progress)
        freezeTriggerText = findViewById(R.id.pro_freeze_trigger)
        readinessList = findViewById(R.id.pro_readiness_list)
        freezeList = findViewById(R.id.pro_freeze_list)
        monitorList = findViewById(R.id.pro_monitor_list)
        confirmedList = findViewById(R.id.pro_dtc_confirmed)
        pendingList = findViewById(R.id.pro_dtc_pending)
        permanentList = findViewById(R.id.pro_dtc_permanent)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_pro_diag_refresh).setOnClickListener { load() }
        load()
    }

    private fun load() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        val btn = findViewById<Button>(R.id.btn_pro_diag_refresh)
        val busy = BusyUi.mark(btn, getString(R.string.busy_reading))
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    ProDiagData(
                        vin = obd.readVin(),
                        calibrationId = obd.readCalibrationId(),
                        cvn = obd.readCvn(),
                        readiness = obd.readImReadiness(),
                        freezeFrame = obd.readFreezeFrame(),
                        monitorTests = obd.readMonitorTests(),
                        confirmed = obd.readDtc(),
                        pending = obd.readPendingDtc(),
                        permanent = obd.readPermanentDtc(),
                    )
                }
                render(data)
            } finally {
                busy.done()
            }
        }
    }

    private fun render(data: ProDiagData) {
        vinText.text = data.vin ?: "—"
        calIdText.text = data.calibrationId ?: "—"
        cvnText.text = data.cvn ?: "—"
        freezeTriggerText.text = data.freezeFrame?.triggerDtc ?: getString(R.string.pro_diag_none)
        renderReadiness(data.readiness)
        renderFreeze(data.freezeFrame)
        renderMonitors(data.monitorTests)
        renderDtcColumn(confirmedList, data.confirmed)
        renderDtcColumn(pendingList, data.pending)
        renderDtcColumn(permanentList, data.permanent)
    }

    private fun renderReadiness(readiness: ImReadiness?) {
        milText.text = when {
            readiness == null -> "—"
            readiness.milOn -> getString(R.string.pro_diag_mil_on)
            else -> getString(R.string.pro_diag_mil_off)
        }
        milText.setTextColor(
            getColor(
                when {
                    readiness == null -> R.color.text_secondary
                    readiness.milOn -> R.color.danger
                    else -> R.color.success
                }
            )
        )
        dtcCountText.text = readiness?.dtcCount?.toString() ?: "—"
        readyProgressText.text = readiness?.let {
            getString(R.string.pro_diag_ready_progress, it.readyCount, it.supportedCount)
        } ?: "—"
        readinessList.removeAllViews()
        readiness?.tests?.forEach { test ->
            val (label, colorRes) = when {
                !test.supported -> getString(R.string.pro_diag_not_supported) to R.color.text_secondary
                test.ready -> getString(R.string.pro_diag_ready) to R.color.success
                else -> getString(R.string.pro_diag_not_ready) to R.color.amber
            }
            addRow(readinessList, getString(test.nameRes), label, colorRes)
        }
    }

    private fun renderFreeze(freeze: FreezeFrame?) {
        freezeList.removeAllViews()
        freeze?.values?.forEach { (nameRes, value) ->
            addRow(freezeList, getString(nameRes), value?.toString() ?: "—")
        }
    }

    private fun renderMonitors(tests: List<MonitorTest>) {
        monitorList.removeAllViews()
        tests.forEach { test ->
            val name = test.tidNameRes?.let { getString(it) + " " + (test.nameRes?.let { res -> getString(res) } ?: getString(R.string.pro_diag_tid, test.testId)) }
                ?: (test.nameRes?.let { getString(it) } ?: getString(R.string.pro_diag_tid, test.testId))
            val valueText = test.scaledValue?.let { ObdDecoder.formatScaled(it) } ?: test.value.toString()
            val unitText = test.unit.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
            val passText = test.passed?.let { if (it) getString(R.string.diag_monitor_pass) else getString(R.string.diag_monitor_fail) } ?: ""
            val suffix = test.cylinder?.let { getString(R.string.pro_diag_cylinder, it) + " " } ?: ""
            addRow(monitorList, name, "$suffix$valueText$unitText $passText".trim())
        }
    }

    private fun renderDtcColumn(container: LinearLayout, codes: List<String>) {
        container.removeAllViews()
        if (codes.isEmpty()) {
            addRow(container, "", getString(R.string.pro_diag_empty), R.color.text_secondary)
            return
        }
        codes.forEach { addRow(container, "", it) }
    }

    private fun addRow(
        container: LinearLayout,
        name: String,
        value: String,
        valueColorRes: Int = R.color.text_primary,
    ) {
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

        if (name.isNotEmpty()) {
            row.addView(
                TextView(this).apply {
                    text = name
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
        }
        row.addView(
            TextView(this).apply {
                text = value
                setTextColor(getColor(valueColorRes))
                textSize = 15f
            },
        )
        container.addView(row, params)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

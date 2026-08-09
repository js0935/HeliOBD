package com.heli.obd.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.diag.DiagnosisEngine
import com.heli.obd.diag.HealthCheckEngine
import com.heli.obd.elm.FreezeFrame
import com.heli.obd.elm.ImReadiness
import com.heli.obd.elm.ObdConstants
import com.heli.obd.elm.ObdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 故障碼畫面：DTC 讀取/清除 + 診斷三件套（凍結框 / I/M 就緒 / VIN）。
 */
class DtcActivity : AppCompatActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var statusText: TextView
    private lateinit var container: LinearLayout
    private lateinit var tabStored: TextView
    private lateinit var tabPending: TextView
    private lateinit var tabPermanent: TextView

    private var storedCodes: List<String> = emptyList()
    private var pendingCodes: List<String> = emptyList()
    private var permanentCodes: List<String> = emptyList()
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dtc)

        statusText = findViewById(R.id.dtc_status_text)
        container = findViewById(R.id.dtc_container)

        findViewById<Button>(R.id.btn_read_dtc).setOnClickListener { readAll() }
        findViewById<Button>(R.id.btn_clear_dtc).setOnClickListener { confirmClearDtc() }
        findViewById<Button>(R.id.btn_export_dtc).setOnClickListener { exportDtcCsv() }

        tabStored = findViewById(R.id.tab_stored)
        tabPending = findViewById(R.id.tab_pending)
        tabPermanent = findViewById(R.id.tab_permanent)
        tabStored.setOnClickListener { selectTab(0) }
        tabPending.setOnClickListener { selectTab(1) }
        tabPermanent.setOnClickListener { selectTab(2) }
        selectTab(0)

        obd.addListener(this)
        renderState(obd.state)
    }

    override fun onDestroy() {
        obd.removeListener(this)
        super.onDestroy()
    }

    private fun readAll() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_LONG).show()
            return
        }
        statusText.text = getString(R.string.obd_connecting)
        lifecycleScope.launch {
            val codes = withContext(Dispatchers.IO) { obd.readDtc() }
            val pending = withContext(Dispatchers.IO) { obd.readPendingDtc() }
            val permanent = withContext(Dispatchers.IO) { obd.readPermanentDtc() }
            val freeze = withContext(Dispatchers.IO) { obd.readFreezeFrame() }
            val im = withContext(Dispatchers.IO) { obd.readImReadiness() }
            val vin = withContext(Dispatchers.IO) { obd.readVin() }
            storedCodes = codes
            pendingCodes = pending
            permanentCodes = permanent
            renderDtcTab(currentTab)
            renderFreezeFrame(freeze)
            renderImReadiness(im)
            renderVin(vin)
            statusText.text = getString(R.string.dtc_read)
        }
    }

    private fun renderDtcTab(tab: Int) {
        val codes = when (tab) {
            1 -> pendingCodes
            2 -> permanentCodes
            else -> storedCodes
        }
        val emptyRes = when (tab) {
            1 -> R.string.dtc_pending_empty
            2 -> R.string.dtc_permanent_empty
            else -> R.string.dtc_empty
        }
        renderDtcList(codes, emptyRes)
    }

    private fun selectTab(tab: Int) {
        currentTab = tab
        updateTabStyles()
        renderDtcTab(tab)
    }

    private fun updateTabStyles() {
        fun style(tab: TextView, selected: Boolean) {
            tab.setBackgroundResource(if (selected) R.drawable.bg_button else R.drawable.bg_card)
            tab.setTextColor(getColor(if (selected) R.color.text_primary else R.color.text_secondary))
        }
        style(tabStored, currentTab == 0)
        style(tabPending, currentTab == 1)
        style(tabPermanent, currentTab == 2)
    }

    private fun renderDtcList(codes: List<String>, emptyRes: Int) {
        container.removeAllViews()
        statusText.text = getString(R.string.dtc_read)

        if (codes.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = getString(emptyRes)
                    setTextColor(getColor(R.color.success))
                    textSize = 16f
                    setPadding(0, dp(16), 0, 0)
                }
            )
            return
        }

        val inflater = LayoutInflater.from(this)
        codes.forEach { code ->
            val row = inflater.inflate(R.layout.item_dtc, container, false)
            row.findViewById<TextView>(R.id.dtc_code).text = code
            row.findViewById<TextView>(R.id.dtc_desc).text = getString(ObdConstants.dtcDescriptionRes(code), code)
            row.findViewById<View>(R.id.dtc_severity_bar).setBackgroundColor(
                getColor(severityColorRes(ObdConstants.dtcSeverity(code)))
            )
            row.isClickable = true
            row.setOnClickListener { showDtcDetail(code) }
            container.addView(row)
        }
    }

    private fun severityColorRes(severity: ObdConstants.DtcSeverity): Int = when (severity) {
        ObdConstants.DtcSeverity.CRITICAL -> R.color.danger
        ObdConstants.DtcSeverity.WARNING -> R.color.amber
        ObdConstants.DtcSeverity.NORMAL -> R.color.success
    }

    private fun severityColorRes(severity: DiagnosisEngine.Severity): Int = when (severity) {
        DiagnosisEngine.Severity.CRITICAL -> R.color.danger
        DiagnosisEngine.Severity.WARNING -> R.color.amber
        DiagnosisEngine.Severity.NORMAL -> R.color.success
    }

    private fun severityTextRes(severity: ObdConstants.DtcSeverity): Int = when (severity) {
        ObdConstants.DtcSeverity.CRITICAL -> R.string.dtc_severity_critical
        ObdConstants.DtcSeverity.WARNING -> R.string.dtc_severity_warning
        ObdConstants.DtcSeverity.NORMAL -> R.string.dtc_severity_normal
    }

    private fun showDtcDetail(code: String) {
        val severity = ObdConstants.dtcSeverity(code)
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(24), dp(16), dp(24), 0)

        content.addView(
            TextView(this).apply {
                text = getString(ObdConstants.dtcDescriptionRes(code), code)
                setTextColor(getColor(R.color.text_primary))
                textSize = 16f
            }
        )
        content.addView(
            TextView(this).apply {
                text = getString(R.string.dtc_severity_label) + "："
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 14f
                setPadding(0, dp(12), 0, 0)
            }
        )
        content.addView(
            TextView(this).apply {
                text = getString(severityTextRes(severity))
                setTextColor(getColor(severityColorRes(severity)))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 15f
                setPadding(0, dp(2), 0, 0)
            }
        )
        content.addView(
            TextView(this).apply {
                text = getString(R.string.dtc_advice_label) + "："
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 14f
                setPadding(0, dp(12), 0, 0)
            }
        )
        content.addView(
            TextView(this).apply {
                text = getString(ObdConstants.dtcAdviceRes(code))
                setTextColor(getColor(R.color.text_secondary))
                textSize = 14f
                setPadding(0, dp(2), 0, 0)
            }
        )
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(code)
            .setView(content)
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }

    private fun exportDtcCsv() {
        val all = (storedCodes + pendingCodes + permanentCodes).distinct()
        if (all.isEmpty()) {
            Toast.makeText(this, R.string.dtc_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.append("code,description,severity,advice\n")
        all.forEach { code ->
            sb.append(code).append(',')
                .append('"')
                .append(getString(ObdConstants.dtcDescriptionRes(code), code).replace("\"", "\"\""))
                .append('"').append(',')
                .append(getString(severityTextRes(ObdConstants.dtcSeverity(code)))).append(',')
                .append('"')
                .append(getString(ObdConstants.dtcAdviceRes(code)).replace("\"", "\"\""))
                .append('"').append('\n')
        }
        val dir = File(filesDir, "export").apply { mkdirs() }
        val file = File(dir, "dtc_${System.currentTimeMillis()}.csv")
        file.writeText(sb.toString())
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.dtc_export_csv)))
    }

    private fun confirmClearDtc() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setMessage(R.string.dtc_clear_confirm)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { obd.clearDtc() }
                    if (ok) {
                        container.removeAllViews()
                        statusText.text = getString(R.string.dtc_cleared)
                        Toast.makeText(this@DtcActivity, R.string.dtc_cleared, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@DtcActivity, R.string.dtc_read_error, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun renderFreezeFrame(freeze: FreezeFrame?) {
        val dtcText = findViewById<TextView>(R.id.freeze_dtc)
        val valuesText = findViewById<TextView>(R.id.freeze_values)
        val diagContainer = findViewById<LinearLayout>(R.id.ff_diag_container)
        diagContainer.removeAllViews()
        if (freeze == null) {
            dtcText.text = getString(R.string.diag_freeze_none)
            valuesText.text = "—"
            return
        }
        dtcText.text = getString(R.string.diag_freeze_trigger, freeze.triggerDtc ?: "—")
        valuesText.text = freeze.values.entries
            .joinToString("\n") { (key, value) -> "${getString(key)}：${value?.toString() ?: "—"}" }

        val rules = HealthCheckEngine.runFreezeFrameRules(freeze)
        if (rules.isEmpty()) {
            diagContainer.addView(
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
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(severityColorRes(rule.severity)))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            titleRow.addView(
                TextView(this).apply {
                    text = getString(R.string.hc_confidence, (rule.confidence * 100).toInt())
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                }
            )
            diagContainer.addView(titleRow)
            diagContainer.addView(
                TextView(this).apply {
                    text = getString(rule.adviceRes)
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(0, dp(2), 0, 0)
                }
            )
        }
    }

    private fun renderImReadiness(im: ImReadiness?) {
        val milText = findViewById<TextView>(R.id.im_mil)
        val testsContainer = findViewById<LinearLayout>(R.id.im_tests_container)
        testsContainer.removeAllViews()
        if (im == null) {
            milText.text = getString(R.string.diag_im_none)
            return
        }
        milText.text = getString(
            R.string.diag_im_mil,
            getString(if (im.milOn) R.string.diag_mil_on else R.string.diag_mil_off),
            im.dtcCount,
            im.readyCount,
            im.supportedCount,
        )
        im.tests.forEach { test ->
            val (label, colorRes) = when {
                !test.supported -> "${getString(test.nameRes)}：${getString(R.string.diag_im_unsupported)}" to R.color.text_secondary
                test.ready -> "${getString(test.nameRes)}：${getString(R.string.diag_im_ready)}" to R.color.success
                else -> "${getString(test.nameRes)}：${getString(R.string.diag_im_not_ready)}" to R.color.danger
            }
            testsContainer.addView(
                TextView(this).apply {
                    text = label
                    setTextColor(getColor(colorRes))
                    textSize = 14f
                    setPadding(0, dp(2), 0, dp(2))
                }
            )
        }
    }

    private fun renderVin(vin: String?) {
        findViewById<TextView>(R.id.vin_text).text = vin ?: getString(R.string.diag_vin_failed)
    }

    // ===== ObdManager.Listener =====

    override fun onStateChanged(state: ObdManager.State) {
        renderState(state)
    }

    override fun onLiveData(data: ObdManager.LiveData) = Unit

    private fun renderState(state: ObdManager.State) {
        statusText.text = when (state) {
            ObdManager.State.Ready -> getString(R.string.obd_connected)
            else -> getString(R.string.obd_disconnected)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

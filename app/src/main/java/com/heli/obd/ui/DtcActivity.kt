/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
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
import com.heli.obd.BaseActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.diag.DiagnosisEngine
import com.heli.obd.diag.HealthCheckEngine
import com.heli.obd.elm.DtcDatabase
import com.heli.obd.elm.FreezeFrame
import com.heli.obd.elm.ImReadiness
import com.heli.obd.elm.MonitorTest
import com.heli.obd.elm.ObdConstants
import com.heli.obd.elm.ObdDecoder
import com.heli.obd.elm.ObdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 故障碼畫面：DTC 讀取/清除 + 診斷三件套（凍結框 / I/M 就緒 / VIN）。
 */
class DtcActivity : BaseActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var statusText: TextView
    private lateinit var container: LinearLayout
    private lateinit var tabStored: TextView
    private lateinit var tabPending: TextView
    private lateinit var tabPermanent: TextView
    private lateinit var clearBtn: Button
    private lateinit var exportBtn: Button

    private var storedCodes: List<String> = emptyList()
    private var pendingCodes: List<String> = emptyList()
    private var permanentCodes: List<String> = emptyList()
    private var currentTab = 0

    /** 內建描述表未收錄之故障碼 → dtc_codes.db 查詢結果（code → 描述或 null） */
    private var descOverrides: Map<String, String?> = emptyMap()

    private var freezeFrame: FreezeFrame? = null
    private var imReadiness: ImReadiness? = null
    private var vin: String? = null
    private var calid: String? = null
    private var cvn: String? = null
    private var monitorTests: List<MonitorTest> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dtc)

        statusText = findViewById(R.id.dtc_status_text)
        container = findViewById(R.id.dtc_container)

        findViewById<Button>(R.id.btn_read_dtc).setOnClickListener { readAll() }
        clearBtn = findViewById(R.id.btn_clear_dtc)
        clearBtn.setOnClickListener { confirmClearDtc() }
        exportBtn = findViewById(R.id.btn_export_dtc)
        exportBtn.setOnClickListener { exportDiagnosticReport() }

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
            val codes: List<String>
            val pending: List<String>
            val permanent: List<String>
            withContext(Dispatchers.IO) {
                codes = obd.readDtc()
                pending = obd.readPendingDtc()
                permanent = obd.readPermanentDtc()
                freezeFrame = obd.readFreezeFrame()
                imReadiness = obd.readImReadiness()
                vin = obd.readVin()
                calid = obd.readCalibrationId()
                cvn = obd.readCvn()
                monitorTests = obd.readMonitorTests()
                DtcDatabase.ensureReady(applicationContext)
                descOverrides =
                    (codes + pending + permanent).distinct()
                        .filter { ObdConstants.dtcDescriptionRes(it) == R.string.dtc_unknown }
                        .associateWith { DtcDatabase.description(it) }
            }
            storedCodes = codes
            pendingCodes = pending
            permanentCodes = permanent
            renderDtcTab(currentTab)
            renderFreezeFrame(freezeFrame)
            renderImReadiness(imReadiness)
            renderVin(vin, calid, cvn)
            renderMonitorTests(monitorTests)
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

    private fun resolveDtcDesc(code: String): String {
        val res = ObdConstants.dtcDescriptionRes(code)
        return if (res == R.string.dtc_unknown) {
            val dbDesc = descOverrides[code]
            if (dbDesc != null) "$code：$dbDesc" else getString(R.string.dtc_unknown, code)
        } else {
            getString(res, code)
        }
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
            row.findViewById<TextView>(R.id.dtc_desc).text = resolveDtcDesc(code)
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
                text = resolveDtcDesc(code)
                setTextColor(getColor(R.color.text_primary))
                textSize = 16f
            }
        )
        content.addView(
            TextView(this).apply {
                text = String.format(Locale.US, "%s：", getString(R.string.dtc_severity_label))
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
                text = String.format(Locale.US, "%s：", getString(R.string.dtc_advice_label))
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

    private fun exportDiagnosticReport() {
        val busy = BusyUi.mark(exportBtn, getString(R.string.busy_exporting))
        val sb = StringBuilder()
        sb.append("""<!DOCTYPE html><html lang="zh-Hant"><head><meta charset="utf-8"><title>HeliOBD 診斷報告</title>""")
        sb.append("""<style>body{font-family:sans-serif;color:#1a1a1a;margin:16px}h1{font-size:20px}h2{font-size:15px;color:#0a5fd0;border-bottom:1px solid #d0d0d0;padding-bottom:4px}table{width:100%%;border-collapse:collapse;margin:4px 0 12px}td,th{border:1px solid #ddd;padding:6px;font-size:13px;text-align:left}th{background:#f2f6fb}.none{color:#2e8b57;font-size:13px}small{color:#888}</style></head><body>""")
        sb.append("<h1>HeliOBD 診斷報告</h1>")
        sb.append("<small>").append(getString(R.string.diag_report_generated, SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))).append("</small>")

        sb.append("<h2>").append(escapeHtml(getString(R.string.diag_vin_section))).append("</h2><table><tr><th>項目</th><th>數值</th></tr>")
        sb.append("<tr><td>VIN</td><td>").append(escapeHtml(vin ?: getString(R.string.diag_vin_failed))).append("</td></tr>")
        sb.append("<tr><td>").append(escapeHtml(getString(R.string.diag_calid_label))).append("</td><td>").append(escapeHtml(calid ?: "—")).append("</td></tr>")
        sb.append("<tr><td>").append(escapeHtml(getString(R.string.diag_cvn_label))).append("</td><td>").append(escapeHtml(cvn ?: "—")).append("</td></tr></table>")

        sb.append("<h2>").append(escapeHtml(getString(R.string.diag_im_section))).append("</h2>")
        if (imReadiness == null) {
            sb.append("<p class=\"none\">").append(escapeHtml(getString(R.string.diag_im_none))).append("</p>")
        } else {
            sb.append("<table><tr><th>測試項目</th><th>狀態</th></tr>")
            imReadiness!!.tests.forEach { test ->
                val status = when {
                    !test.supported -> getString(R.string.diag_im_unsupported)
                    test.ready -> getString(R.string.diag_im_ready)
                    else -> getString(R.string.diag_im_not_ready)
                }
                sb.append("<tr><td>").append(escapeHtml(getString(test.nameRes))).append("</td><td>").append(escapeHtml(status)).append("</td></tr>")
            }
            sb.append("</table>")
        }

        sb.append("<h2>").append(escapeHtml(getString(R.string.diag_freeze_section))).append("</h2>")
        if (freezeFrame == null) {
            sb.append("<p class=\"none\">").append(escapeHtml(getString(R.string.diag_freeze_none))).append("</p>")
        } else {
            sb.append("<table><tr><th>項目</th><th>數值</th></tr>")
            sb.append("<tr><td>").append(escapeHtml(getString(R.string.diag_freeze_trigger, freezeFrame!!.triggerDtc ?: "—"))).append("</td><td>—</td></tr>")
            freezeFrame!!.values.forEach { (key, value) ->
                sb.append("<tr><td>").append(escapeHtml(getString(key))).append("</td><td>").append(escapeHtml(value?.toString() ?: "—")).append("</td></tr>")
            }
            sb.append("</table>")
        }

        sb.append("<h2>").append(escapeHtml(getString(R.string.diag_mode6_section))).append("</h2>")
        if (monitorTests.isEmpty()) {
            sb.append("<p class=\"none\">").append(escapeHtml(getString(R.string.diag_mode6_none))).append("</p>")
        } else {
        var currentTid = -1
        monitorTests.forEach { test ->
            if (test.tid != currentTid) {
                currentTid = test.tid
                val titleRes = test.tidNameRes
                sb.append("<h2 style=\"font-size:13px\">")
                    .append(escapeHtml(if (titleRes != null) getString(titleRes) else getString(R.string.diag_tid_unknown, currentTid)))
                    .append("</h2>")
            }
            val name = test.nameRes?.let { getString(it) }
                ?: getString(R.string.mon_test_unknown, test.testId)
            val prefix = test.cylinder?.let { getString(R.string.mon_test_cylinder, it) + " " } ?: ""
            sb.append("<p style=\"font-size:13px;margin:2px 0\">").append(escapeHtml(prefix + name)).append("：").append(escapeHtml(monitorValueText(test))).append("</p>")
        }
        }

        sb.append("<h2>").append(escapeHtml(getString(R.string.nav_dtc))).append("</h2>")
        val all = (storedCodes + pendingCodes + permanentCodes).distinct()
        if (all.isEmpty()) {
            sb.append("<p class=\"none\">").append(escapeHtml(getString(R.string.diag_report_no_dtc))).append("</p>")
        } else {
            sb.append("<table><tr><th>代碼</th><th>描述</th><th>嚴重度</th><th>建議</th></tr>")
            all.forEach { code ->
                sb.append("<tr><td>").append(escapeHtml(code)).append("</td>")
                    .append("<td>").append(escapeHtml(resolveDtcDesc(code))).append("</td>")
                    .append("<td>").append(escapeHtml(getString(severityTextRes(ObdConstants.dtcSeverity(code))))).append("</td>")
                    .append("<td>").append(escapeHtml(getString(ObdConstants.dtcAdviceRes(code)))).append("</td></tr>")
            }
            sb.append("</table>")
        }

        sb.append("</body></html>")
        try {
            val dir = File(filesDir, "export").apply { mkdirs() }
            val file = File(dir, "diag_report_${System.currentTimeMillis()}.html")
            file.writeText(sb.toString())
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.diag_report_share)))
            busy.done()
        } catch (e: Exception) {
            busy.done()
            Toast.makeText(this, R.string.diag_report_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun confirmClearDtc() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_LONG).show()
            return
        }
        // 引擎運轉中清除故障碼可能被 ECU 立即重新寫入；先讀取轉速判斷引擎狀態
        if (obd.isDemoMode()) {
            showClearConfirmDialog()
            return
        }
        lifecycleScope.launch {
            val rpm = withContext(Dispatchers.IO) {
                obd.sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_RPM)
                    ?.let { ObdDecoder.rpm(it) }
            }
            if (rpm != null && rpm > 0) {
                showEngineRunningWarning(rpm)
            } else {
                showClearConfirmDialog()
            }
        }
    }

    private fun showEngineRunningWarning(rpm: Int) {
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.dtc_clear_engine_running_title)
            .setMessage(getString(R.string.dtc_clear_engine_running, rpm))
            .setPositiveButton(R.string.dtc_clear_anyway) { _, _ -> showClearConfirmDialog() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showClearConfirmDialog() {
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setMessage(R.string.dtc_clear_confirm)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                val busy = BusyUi.mark(clearBtn, getString(R.string.busy_clearing))
                lifecycleScope.launch {
                    try {
                        val ok = withContext(Dispatchers.IO) { obd.clearDtc() }
                        if (ok) {
                            container.removeAllViews()
                            statusText.text = getString(R.string.dtc_cleared)
                            Toast.makeText(this@DtcActivity, R.string.dtc_cleared, Toast.LENGTH_SHORT).show()
                        } else {
                            val reason = obd.lastClearError()
                            val msg = if (reason.isNullOrBlank()) {
                                getString(R.string.dtc_read_error)
                            } else {
                                getString(R.string.dtc_clear_failed, reason)
                            }
                            Toast.makeText(this@DtcActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        busy.done()
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

    private fun renderVin(vin: String?, calid: String?, cvn: String?) {
        findViewById<TextView>(R.id.vin_text).text = vin ?: getString(R.string.diag_vin_failed)
        findViewById<TextView>(R.id.calid_text).text = if (calid != null)
            getString(R.string.diag_calid_label) + "：" + calid
        else
            getString(R.string.diag_vin_failed)
        findViewById<TextView>(R.id.cvn_text).text = if (cvn != null)
            getString(R.string.diag_cvn_label) + "：" + cvn
        else
            getString(R.string.diag_vin_failed)
    }

    private fun renderMonitorTests(tests: List<MonitorTest>) {
        val container = findViewById<LinearLayout>(R.id.monitor_tests_container)
        container.removeAllViews()
        if (tests.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.diag_mode6_none)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 14f
                    setPadding(0, dp(4), 0, 0)
                }
            )
            return
        }
        var currentTid = -1
        tests.forEach { test ->
            if (test.tid != currentTid) {
                currentTid = test.tid
                val titleRes = test.tidNameRes
                container.addView(
                    TextView(this).apply {
                        text = if (titleRes != null) getString(titleRes)
                        else getString(R.string.diag_tid_unknown, currentTid)
                        setTextColor(getColor(R.color.text_primary))
                        setTypeface(typeface, Typeface.BOLD)
                        textSize = 14f
                        setPadding(0, dp(6), 0, 0)
                    }
                )
            }
            val name = test.nameRes?.let { getString(it) }
                ?: getString(R.string.mon_test_unknown, test.testId)
            val prefix = test.cylinder?.let { getString(R.string.mon_test_cylinder, it) + " " } ?: ""
            container.addView(
                TextView(this).apply {
                    text = String.format(Locale.US, "%s%s：%s", prefix, name, monitorValueText(test))
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 13f
                    setPadding(dp(8), dp(1), 0, dp(1))
                }
            )
        }
    }

    /** Mode 06 測試值顯示：縮放值（若有）+ 單位 + 通過/未通過 */
    private fun monitorValueText(test: MonitorTest): String {
        val valueText = test.scaledValue?.let { ObdDecoder.formatScaled(it) } ?: test.value.toString()
        val unitText = test.unit.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
        val passText = test.passed?.let { if (it) getString(R.string.diag_monitor_pass) else getString(R.string.diag_monitor_fail) } ?: ""
        return "$valueText$unitText $passText".trim()
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

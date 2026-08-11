/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.ImReadiness
import com.heli.obd.elm.MonitorTest
import com.heli.obd.elm.ObdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 車況報告：彙整 VIN/故障碼/排放監測器/Mode 06 結果為文字報告，
 * 支援分享、複製，並附優化過的 AI 診斷提示詞供貼給 LLM 分析。
 */
class VehicleReportActivity : BaseActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var reportText: TextView

    /** 目前彙整的報告內容（供分享/複製/AI） */
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle_report)

        reportText = findViewById(R.id.report_text)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_report_build).setOnClickListener { build() }
        findViewById<Button>(R.id.btn_report_share).setOnClickListener { share() }
        findViewById<Button>(R.id.btn_report_copy).setOnClickListener { copy(report) }
        findViewById<Button>(R.id.btn_report_copy_ai).setOnClickListener {
            copy(getString(R.string.report_ai_prompt) + "\n\n" + report)
        }
        build()
    }

    private fun build() {
        findViewById<Button>(R.id.btn_report_build).isEnabled = false
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { collectData() }
            findViewById<Button>(R.id.btn_report_build).isEnabled = true
            report = formatReport(snapshot)
            reportText.text = report
            if (snapshot.vin == null && snapshot.dtcCodes.isEmpty()) {
                Toast.makeText(this@VehicleReportActivity, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private data class Snapshot(
        val vin: String?,
        val calid: String?,
        val cvn: String?,
        val dtcCodes: List<String>,
        val pendingCodes: List<String>,
        val im: ImReadiness?,
        val monitorTests: List<MonitorTest>,
    )

    private fun collectData(): Snapshot {
        if (!obd.isConnected()) {
            return Snapshot(null, null, null, emptyList(), emptyList(), null, emptyList())
        }
        return Snapshot(
            vin = obd.readVin(),
            calid = obd.readCalibrationId(),
            cvn = obd.readCvn(),
            dtcCodes = obd.readDtc(),
            pendingCodes = obd.readPendingDtc(),
            im = obd.readImReadiness(),
            monitorTests = obd.readMonitorTests(),
        )
    }

    private fun formatReport(s: Snapshot): String {
        val sb = StringBuilder()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        sb.append("HeliOBD 車況報告\n").append(time).append("\n\n")

        sb.append("【").append(getString(R.string.report_section_vehicle)).append("】\n")
        sb.append("VIN：").append(s.vin ?: getString(R.string.diag_vin_failed)).append("\n")
        sb.append(getString(R.string.diag_calid_label)).append("：")
            .append(s.calid ?: getString(R.string.diag_vin_failed)).append("\n")
        sb.append(getString(R.string.diag_cvn_label)).append("：")
            .append(s.cvn ?: getString(R.string.diag_vin_failed)).append("\n\n")

        sb.append("【").append(getString(R.string.report_section_dtc)).append("】\n")
        if (s.dtcCodes.isEmpty()) {
            sb.append(getString(R.string.report_no_dtc)).append("\n")
        } else {
            sb.append(s.dtcCodes.joinToString(", ")).append("\n")
        }
        if (s.pendingCodes.isNotEmpty()) {
            sb.append("待處理：").append(s.pendingCodes.joinToString(", ")).append("\n")
        }
        sb.append("\n")

        sb.append("【").append(getString(R.string.report_section_im)).append("】\n")
        if (s.im != null) {
            sb.append("MIL：")
                .append(getString(if (s.im.milOn) R.string.report_mil_on else R.string.report_mil_off))
                .append("，就緒 ").append(s.im.readyCount).append("/").append(s.im.supportedCount)
                .append("\n")
            s.im.tests.filter { it.supported }.forEach { test ->
                sb.append("  ").append(getString(test.nameRes)).append("：")
                    .append(getString(if (test.ready) R.string.diag_im_ready else R.string.diag_im_not_ready))
                    .append("\n")
            }
        } else {
            sb.append(getString(R.string.diag_im_none)).append("\n")
        }
        sb.append("\n")

        sb.append("【").append(getString(R.string.report_section_mode6)).append("】\n")
        if (s.monitorTests.isEmpty()) {
            sb.append(getString(R.string.diag_mode6_none)).append("\n")
        } else {
            s.monitorTests.forEach { t ->
                val name = t.nameRes?.let { getString(it) } ?: "TID ${t.tid} Test ${t.testId}"
                sb.append("  ").append(name).append("：").append(t.value)
                t.cylinder?.let { sb.append("（缸 ").append(it).append("）") }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    private fun share() {
        if (report.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_title))
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(send, getString(R.string.report_share)))
    }

    private fun copy(text: String) {
        if (text.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.report_title), text))
        Toast.makeText(this, R.string.report_copied, Toast.LENGTH_SHORT).show()
    }
}

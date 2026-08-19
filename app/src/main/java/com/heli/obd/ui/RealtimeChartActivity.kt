/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.heli.obd.BaseActivity
import androidx.core.content.FileProvider
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.ObdManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 即時數據曲線圖：多訊號捲動折線 + CSV 記錄與回放。
 */
class RealtimeChartActivity : BaseActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var statusText: TextView
    private lateinit var chart: ChartView
    private lateinit var pauseBtn: Button
    private lateinit var recordBtn: Button
    private lateinit var playbackBtn: Button
    private lateinit var shareBtn: Button

    private var paused = false
    private var recording = false
    private var playingBack = false
    private var recordFile: File? = null

    /** ChartView 正規化時會把負值 clamp 到 0，Fuel Trim 可能為負，統一 +25 平移後才進圖/CSV */
    private val ftOffset = 25f

    private val playbackHandler = Handler(Looper.getMainLooper())
    private val playbackTick = object : Runnable {
        override fun run() {
            if (!playingBack) return
            val row = playbackRows.getOrNull(playbackIndex)
            if (row == null) {
                stopPlayback()
                Toast.makeText(
                    this@RealtimeChartActivity,
                    R.string.chart_playback_done,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            playbackIndex++
            chart.addSample(parseRow(row))
            playbackHandler.postDelayed(this, 200)
        }
    }
    private var playbackRows: List<String> = emptyList()
    private var playbackIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_realtime_chart)

        statusText = findViewById(R.id.chart_status_text)
        chart = findViewById(R.id.chart_view)
        pauseBtn = findViewById(R.id.btn_chart_pause)
        recordBtn = findViewById(R.id.btn_chart_record)
        playbackBtn = findViewById(R.id.btn_chart_playback)
        shareBtn = findViewById(R.id.btn_chart_share)

        chart.setSeries(
            listOf(
                ChartView.Series("RPM", "", 12000f, Color.rgb(0, 180, 216)),
                ChartView.Series("km/h", "", 200f, Color.rgb(16, 185, 129)),
                ChartView.Series("g/s", "", 30f, Color.rgb(245, 158, 11)),
                ChartView.Series("Nm", "", 120f, Color.rgb(239, 68, 68)),
                ChartView.Series("AFR", "", 20f, Color.rgb(168, 85, 247)),
                ChartView.Series("FT", "%", 25f, Color.rgb(6, 182, 212)),
            )
        )

        pauseBtn.setOnClickListener {
            paused = !paused
            pauseBtn.setText(if (paused) R.string.chart_resume else R.string.chart_pause)
            if (!paused) chart.clear()
        }
        recordBtn.setOnClickListener {
            if (recording) stopRecording() else startRecording()
        }
        playbackBtn.setOnClickListener {
            if (playingBack) stopPlayback() else showRecordings()
        }
        shareBtn.setOnClickListener { shareChart() }

        obd.addListener(this)
        renderState(obd.state)
    }

    override fun onDestroy() {
        obd.removeListener(this)
        playbackHandler.removeCallbacks(playbackTick)
        super.onDestroy()
    }

    private fun startRecording() {
        val dir = File(filesDir, "charts").apply { mkdirs() }
        val file = File(dir, "chart_${System.currentTimeMillis()}.csv")
        file.writeText("timeMs,RPM,km/h,g/s,Nm,AFR,FT\n")
        recordFile = file
        recording = true
        recordBtn.setText(R.string.chart_stop_record)
        recordBtn.setBackgroundResource(R.drawable.bg_button_accent)
        Toast.makeText(this, R.string.chart_record_started, Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        recording = false
        recordBtn.setText(R.string.chart_record)
        recordBtn.setBackgroundResource(R.drawable.bg_button)
        Toast.makeText(
            this,
            getString(R.string.chart_record_saved, recordFile?.name ?: ""),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startPlayback(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, R.string.chart_no_record, Toast.LENGTH_SHORT).show()
            return
        }
        val lines = file.readLines().drop(1).filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            Toast.makeText(this, R.string.chart_record_empty, Toast.LENGTH_SHORT).show()
            return
        }
        playbackRows = lines
        playbackIndex = 0
        playingBack = true
        chart.clear()
        playbackBtn.setText(R.string.chart_stop_playback)
        playbackBtn.setBackgroundResource(R.drawable.bg_button_accent)
        playbackHandler.post(playbackTick)
    }

    private fun stopPlayback() {
        playingBack = false
        playbackHandler.removeCallbacks(playbackTick)
        playbackBtn.setText(R.string.chart_playback)
        playbackBtn.setBackgroundResource(R.drawable.bg_card)
    }

    /** 將目前曲線畫面存成 PNG 並透過 FileProvider 分享 */
    private fun shareChart() {
        if (!chart.hasData()) {
            Toast.makeText(this, R.string.chart_no_live_data, Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(filesDir, "export").apply { mkdirs() }
        val file = File(dir, "chart_${System.currentTimeMillis()}.png")
        val bitmap = Bitmap.createBitmap(chart.width, chart.height, Bitmap.Config.ARGB_8888)
        chart.draw(Canvas(bitmap))
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            bitmap.recycle()
        }
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.chart_share)))
    }

    /** 列出所有錄製檔（最新在前），供選取回放或刪除 */
    private fun showRecordings() {
        val dir = File(filesDir, "charts")
        val files = dir.listFiles { f ->
            f.name.startsWith("chart_") && f.name.endsWith(".csv")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.chart_no_record, Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        var dialog: AlertDialog? = null
        files.forEach { file ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL

            val name = TextView(this)
            name.text = fmt.format(Date(file.lastModified()))
            name.textSize = 15f
            name.setTextColor(getColor(R.color.text_primary))
            name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(name)

            val del = TextView(this)
            del.text = getString(R.string.common_delete)
            del.textSize = 13f
            del.setTextColor(getColor(R.color.danger))
            del.setPadding(dp(12), dp(4), dp(4), dp(4))
            row.addView(del)

            row.setOnClickListener {
                dialog?.dismiss()
                startPlayback(file)
            }
            del.setOnClickListener {
                dialog?.dismiss()
                file.delete()
                showRecordings()
            }

            list.addView(row)
        }
        dialog = AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.chart_select_record)
            .setView(list)
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun parseRow(row: String): Map<String, Float> {
        val parts = row.split(",")
        val result = linkedMapOf<String, Float>()
        if (parts.size >= 2) parts[1].toFloatOrNull()?.let { result["RPM"] = it }
        if (parts.size >= 3) parts[2].toFloatOrNull()?.let { result["km/h"] = it }
        if (parts.size >= 4) parts[3].toFloatOrNull()?.let { result["g/s"] = it }
        if (parts.size >= 5) parts[4].toFloatOrNull()?.let { result["Nm"] = it }
        if (parts.size >= 6) parts[5].toFloatOrNull()?.let { result["AFR"] = it }
        if (parts.size >= 7) parts[6].toFloatOrNull()?.let { result["FT"] = it }
        return result
    }

    // ===== ObdManager.Listener =====

    override fun onStateChanged(state: ObdManager.State) {
        renderState(state)
    }

    override fun onLiveData(data: ObdManager.LiveData) {
        if (playingBack) return
        if (paused) return

        val values = linkedMapOf<String, Float>()
        data.rpm?.let { values["RPM"] = it.toFloat() }
        data.speed?.let { values["km/h"] = it.toFloat() }
        data.maf?.let { values["g/s"] = it }
        data.torqueNm?.let { values["Nm"] = it }
        data.afr?.let { values["AFR"] = it }
        data.fuelTrim?.let { values["FT"] = it + ftOffset }
        if (values.isEmpty()) return

        chart.addSample(values)

        if (recording) {
            val now = System.currentTimeMillis()
            val sb = StringBuilder().append(now)
            for (key in listOf("RPM", "km/h", "g/s", "Nm", "AFR", "FT")) {
                sb.append(',').append(values[key]?.toString() ?: "")
            }
            recordFile?.appendText(sb.append('\n').toString())
        }
    }

    private fun renderState(state: ObdManager.State) {
        statusText.text = when (state) {
            ObdManager.State.Ready -> getString(R.string.obd_connected)
            else -> getString(R.string.obd_disconnected)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

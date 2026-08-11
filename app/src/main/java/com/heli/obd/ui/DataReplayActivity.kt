/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.heli.obd.BaseActivity
import com.heli.obd.R
import java.io.File
import java.util.Locale

/**
 * 數據回放：讀取錄製的 JSON 檔，以時間推進逐步餵入 ChartView 繪製曲線。
 * 支援參數多選、縮放、觸控游標讀值與直方圖分析。
 */
class DataReplayActivity : BaseActivity() {

    private data class ParamDef(
        val label: String,
        val unit: String,
        val maxValue: Float,
        val color: Int,
        val extract: (DatalogSample) -> Float?,
    )

    private lateinit var chart: ChartView
    private lateinit var btnReplay: TextView
    private lateinit var progressText: TextView
    private lateinit var fileNameText: TextView
    private lateinit var cursorText: TextView
    private lateinit var paramRow: LinearLayout

    private val samples = mutableListOf<DatalogSample>()
    private var playIndex = 0
    private var playing = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var paramDefs: List<ParamDef>
    private val enabledLabels = linkedSetOf<String>()
    private var cursorEnabled = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing) return
            feedNext()
            if (playIndex < samples.size) {
                handler.postDelayed(this, 100)
            } else {
                playing = false
                btnReplay.text = getString(R.string.replay_play)
                progressText.text = getString(R.string.replay_done)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_replay)

        chart = findViewById(R.id.replay_chart)
        btnReplay = findViewById(R.id.btn_replay)
        progressText = findViewById(R.id.replay_progress_text)
        fileNameText = findViewById(R.id.replay_file_name)
        cursorText = findViewById(R.id.replay_cursor_text)
        paramRow = findViewById(R.id.param_row)

        buildParamDefs()

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnReplay.setOnClickListener {
            if (samples.isEmpty()) return@setOnClickListener
            if (playing) pause() else play()
        }
        findViewById<View>(R.id.btn_replay_restart).setOnClickListener { restart() }
        findViewById<View>(R.id.btn_zoom_in).setOnClickListener {
            chart.setWindowSize(chart.getWindowSize() / 2)
        }
        findViewById<View>(R.id.btn_zoom_out).setOnClickListener {
            chart.setWindowSize(chart.getWindowSize() * 2)
        }
        findViewById<View>(R.id.btn_cursor).setOnClickListener { toggleCursor() }
        findViewById<View>(R.id.btn_histogram).setOnClickListener {
            chart.histogramMode = !chart.histogramMode
        }

        chart.onCursorMoved = { sample -> showCursorValues(sample) }

        val path = intent.getStringExtra("file")
        if (path == null) {
            finish()
            return
        }
        val file = File(path)
        fileNameText.text = file.name
        samples += DataLoggerActivity.parseSamples(file)

        rebuildSeries()
        progressText.text = getString(R.string.replay_progress, 0, samples.size)
    }

    private fun buildParamDefs() {
        paramDefs = listOf(
            ParamDef(getString(R.string.obd_rpm), " rpm", 8000f, getColor(R.color.primary)) {
                it.rpm?.toFloat()
            },
            ParamDef(getString(R.string.obd_speed), " km/h", 200f, getColor(R.color.accent)) {
                it.speed?.toFloat()
            },
            ParamDef(getString(R.string.obd_temp), " ℃", 130f, getColor(R.color.danger)) {
                it.coolant?.toFloat()
            },
            ParamDef(getString(R.string.obd_voltage), " V", 16f, getColor(R.color.success)) {
                it.voltage
            },
            ParamDef(getString(R.string.obd_load), " %", 100f, getColor(R.color.amber)) {
                it.load?.toFloat()
            },
        )
        enabledLabels.clear()
        enabledLabels += paramDefs.map { it.label }
        buildParamChips()
    }

    private fun buildParamChips() {
        paramRow.removeAllViews()
        for (p in paramDefs) {
            val chip = TextView(this).apply {
                text = p.label
                textSize = 13f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    if (!enabledLabels.remove(p.label)) enabledLabels.add(p.label)
                    refreshChipStyle(this, p)
                    rebuildSeries()
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (p != paramDefs.last()) lp.marginEnd = dp(6)
            paramRow.addView(chip, lp)
            refreshChipStyle(chip, p)
        }
    }

    private fun refreshChipStyle(chip: TextView, p: ParamDef) {
        val enabled = p.label in enabledLabels
        chip.background = ContextCompat.getDrawable(
            this,
            if (enabled) R.drawable.bg_button_accent else R.drawable.bg_card,
        )
        chip.setTextColor(
            getColor(if (enabled) android.R.color.white else R.color.text_secondary),
        )
    }

    private fun rebuildSeries() {
        val series = paramDefs
            .filter { it.label in enabledLabels }
            .map { ChartView.Series(it.label, it.unit, it.maxValue, it.color) }
        chart.setSeries(series)
    }

    private fun toggleCursor() {
        cursorEnabled = !cursorEnabled
        val btn = findViewById<TextView>(R.id.btn_cursor)
        btn.background = ContextCompat.getDrawable(
            this,
            if (cursorEnabled) R.drawable.bg_button_accent else R.drawable.bg_card,
        )
        btn.setTextColor(
            getColor(if (cursorEnabled) android.R.color.white else R.color.text_secondary),
        )
        if (!cursorEnabled) cursorText.text = getString(R.string.common_dash)
    }

    private fun showCursorValues(sample: Map<String, Float>?) {
        if (!cursorEnabled) return
        if (sample == null || sample.isEmpty()) {
            cursorText.text = getString(R.string.common_dash)
            return
        }
        val parts = sample.entries.joinToString("  ") { (label, v) ->
            val unit = paramDefs.firstOrNull { it.label == label }?.unit ?: ""
            getString(R.string.replay_cursor_value, label, String.format(Locale.US, "%.1f%s", v, unit))
        }
        cursorText.text = parts
        cursorText.typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDestroy() {
        playing = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun play() {
        playing = true
        btnReplay.text = getString(R.string.replay_pause)
        handler.post(ticker)
    }

    private fun pause() {
        playing = false
        handler.removeCallbacks(ticker)
        btnReplay.text = getString(R.string.replay_play)
    }

    private fun restart() {
        pause()
        playIndex = 0
        chart.clear()
        progressText.text = getString(R.string.replay_progress, 0, samples.size)
        play()
    }

    private fun feedNext() {
        if (playIndex >= samples.size) return
        val s = samples[playIndex]
        val values = LinkedHashMap<String, Float>()
        for (p in paramDefs) {
            if (p.label !in enabledLabels) continue
            p.extract(s)?.let { values[p.label] = it }
        }
        chart.addSample(values)
        playIndex++
        progressText.text = getString(R.string.replay_progress, playIndex, samples.size)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

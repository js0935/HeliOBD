/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.heli.obd.BaseActivity
import com.heli.obd.R
import java.io.File

/**
 * 數據回放：讀取錄製的 JSON 檔，以時間推進逐步餵入 ChartView 繪製曲線。
 */
class DataReplayActivity : BaseActivity() {

    private lateinit var chart: ChartView
    private lateinit var btnReplay: TextView
    private lateinit var progressText: TextView
    private lateinit var fileNameText: TextView

    private val samples = mutableListOf<DatalogSample>()
    private var playIndex = 0
    private var playing = false
    private val handler = Handler(Looper.getMainLooper())

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

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnReplay.setOnClickListener {
            if (samples.isEmpty()) return@setOnClickListener
            if (playing) pause() else play()
        }
        findViewById<View>(R.id.btn_replay_restart).setOnClickListener { restart() }

        val path = intent.getStringExtra("file")
        if (path == null) {
            finish()
            return
        }
        val file = File(path)
        fileNameText.text = file.name
        samples += DataLoggerActivity.parseSamples(file)

        chart.setSeries(
            listOf(
                ChartView.Series(getString(R.string.obd_rpm), " rpm", 8000f, getColor(R.color.primary)),
                ChartView.Series(getString(R.string.obd_speed), " km/h", 200f, getColor(R.color.accent)),
                ChartView.Series(getString(R.string.obd_temp), " ℃", 130f, getColor(R.color.danger)),
                ChartView.Series(getString(R.string.obd_voltage), " V", 16f, getColor(R.color.success)),
            )
        )
        progressText.text = getString(R.string.replay_progress, 0, samples.size)
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
        s.rpm?.let { values[getString(R.string.obd_rpm)] = it.toFloat() }
        s.speed?.let { values[getString(R.string.obd_speed)] = it.toFloat() }
        s.coolant?.let { values[getString(R.string.obd_temp)] = it.toFloat() }
        s.voltage?.let { values[getString(R.string.obd_voltage)] = it }
        chart.addSample(values)
        playIndex++
        progressText.text = getString(R.string.replay_progress, playIndex, samples.size)
    }
}

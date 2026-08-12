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
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.ObdManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 加速測試：以 OBD 車速自動偵測起跑（>0）與目標（0-100 / 0-60 / 1/4 英里），
 * 計時成績儲存最佳記錄（files/accel_scores.json）。
 */
class AccelerationActivity : BaseActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private enum class Mode(val labelRes: Int, val targetKmh: Double, val targetMeters: Double?) {
        ACCEL_100(R.string.accel_mode_100, 100.0, null),
        ACCEL_60(R.string.accel_mode_60, 60.0, null),
        QUARTER_MILE(R.string.accel_mode_quarter, 0.0, 402.336),
    }

    private var mode: Mode = Mode.ACCEL_100

    private lateinit var speedValue: TextView
    private lateinit var timeValue: TextView
    private lateinit var stateText: TextView
    private lateinit var bestValue: TextView
    private lateinit var modeContainer: LinearLayout
    private lateinit var btnStart: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var armed = false
    private var running = false
    private var runStartMs = 0L
    private var lastSampleMs = 0L
    private var lastSpeed = 0.0
    private var distanceM = 0.0
    private var currentSpeed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_acceleration)

        speedValue = findViewById(R.id.accel_speed_value)
        timeValue = findViewById(R.id.accel_time_value)
        stateText = findViewById(R.id.accel_state)
        bestValue = findViewById(R.id.accel_best)
        modeContainer = findViewById(R.id.accel_mode_container)
        btnStart = findViewById(R.id.accel_start)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        buildModeSelector()
        btnStart.setOnClickListener { onStartPressed() }
        renderBest()
    }

    override fun onResume() {
        super.onResume()
        obd.addListener(this)
        renderState()
    }

    override fun onPause() {
        obd.removeListener(this)
        super.onPause()
    }

    private fun buildModeSelector() {
        modeContainer.removeAllViews()
        Mode.entries.forEach { m ->
            val chip = TextView(this)
            chip.text = getString(m.labelRes)
            chip.textSize = 14f
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            chip.setBackgroundResource(R.drawable.bg_card)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(8)
            chip.layoutParams = lp
            chip.gravity = Gravity.CENTER
            chip.setTextColor(getColor(if (m == mode) R.color.primary else R.color.text_secondary))
            chip.setTypeface(chip.typeface, if (m == mode) Typeface.BOLD else Typeface.NORMAL)
            chip.setOnClickListener {
                if (!running) {
                    mode = m
                    armed = false
                    buildModeSelector()
                    renderBest()
                    renderState()
                }
            }
            modeContainer.addView(chip)
        }
    }

    private fun onStartPressed() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.accel_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        if (running) return
        armed = true
        running = false
        runStartMs = 0L
        distanceM = 0.0
        timeValue.text = getString(R.string.accel_time_zero)
        renderState()
    }

    override fun onStateChanged(state: ObdManager.State) {}

    override fun onLiveData(data: ObdManager.LiveData) {
        currentSpeed = data.speed ?: 0
        speedValue.text = String.format(Locale.US, "%d", currentSpeed)
        if (armed && !running) {
            if (currentSpeed > 0) {
                running = true
                runStartMs = System.currentTimeMillis()
                lastSampleMs = runStartMs
                lastSpeed = 0.0
            }
            return
        }
        if (running) {
            val now = System.currentTimeMillis()
            val dtSec = (now - lastSampleMs) / 1000.0
            lastSampleMs = now
            if (dtSec > 0 && dtSec < 5) {
                lastSpeed = currentSpeed.toDouble()
                distanceM += currentSpeed / 3.6 * dtSec
            }
            val elapsedSec = (now - runStartMs) / 1000.0
            timeValue.text = getString(R.string.accel_time_format, elapsedSec)

            val finished = when (mode) {
                Mode.ACCEL_100 -> currentSpeed >= 100
                Mode.ACCEL_60 -> currentSpeed >= 60
                Mode.QUARTER_MILE -> distanceM >= mode.targetMeters!!
            }
            if (finished) {
                finishRun(elapsedSec)
            }
        }
    }

    private fun finishRun(elapsedSec: Double) {
        running = false
        armed = false
        saveScore(mode, elapsedSec)
        timeValue.text = getString(R.string.accel_result_format, elapsedSec)
        renderBest()
        renderState()
    }

    private fun renderState() {
        stateText.text = when {
            running -> getString(R.string.accel_state_running)
            armed -> getString(R.string.accel_state_armed)
            else -> getString(R.string.accel_state_idle)
        }
        stateText.setTextColor(
            getColor(if (running) R.color.success else if (armed) R.color.lock else R.color.text_secondary)
        )
        btnStart.setText(if (armed || running) R.string.accel_restart else R.string.accel_start)
    }

    private fun renderBest() {
        val best = loadScore(mode)
        bestValue.text = if (best == null) {
            getString(R.string.accel_best_none)
        } else {
            getString(R.string.accel_best_format, best)
        }
        bestValue.setTextColor(getColor(R.color.lock))
    }

    private fun scoreFile(): File =
        File(filesDir, "accel_scores.json")

    private fun saveScore(m: Mode, time: Double) {
        val file = scoreFile()
        val obj = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        val key = m.name
        val prev = obj.optDouble(key, Double.MAX_VALUE)
        if (time < prev) {
            obj.put(key, time)
            file.writeText(obj.toString())
        }
    }

    private fun loadScore(m: Mode): Double? {
        val file = scoreFile()
        if (!file.exists()) return null
        return runCatching {
            val v = JSONObject(file.readText()).optDouble(m.name, Double.NaN)
            if (v.isNaN()) null else v
        }.getOrNull()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

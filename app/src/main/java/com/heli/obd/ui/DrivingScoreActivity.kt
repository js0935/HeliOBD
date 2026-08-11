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
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.ObdManager
import com.heli.obd.scoring.DrivingScoreEngine

/**
 * 駕駛評分畫面：即時監聽 OBD 數據，以本機規則計算本次騎乘評分。
 */
class DrivingScoreActivity : BaseActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)
    private val engine = DrivingScoreEngine()

    private lateinit var statusText: TextView
    private lateinit var scoreNumber: TextView
    private lateinit var scoreGrade: TextView
    private lateinit var scoreHint: TextView
    private lateinit var breakdownContainer: LinearLayout
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driving_score)

        statusText = findViewById(R.id.score_status_text)
        scoreNumber = findViewById(R.id.score_number)
        scoreGrade = findViewById(R.id.score_grade)
        scoreHint = findViewById(R.id.score_hint)
        breakdownContainer = findViewById(R.id.score_breakdown_container)
        startBtn = findViewById(R.id.btn_score_start)
        stopBtn = findViewById(R.id.btn_score_stop)

        startBtn.setOnClickListener { startRecording() }
        stopBtn.setOnClickListener { stopRecording() }

        obd.addListener(this)
        renderState(obd.state)
    }

    override fun onDestroy() {
        obd.removeListener(this)
        super.onDestroy()
    }

    private fun startRecording() {
        engine.reset()
        recording = true
        startBtn.visibility = View.GONE
        stopBtn.visibility = View.VISIBLE
        scoreNumber.text = "0"
        scoreGrade.text = ""
        scoreHint.text = getString(R.string.score_counting)
        breakdownContainer.removeAllViews()
    }

    private fun stopRecording() {
        recording = false
        startBtn.visibility = View.VISIBLE
        stopBtn.visibility = View.GONE
        val score = engine.score()
        if (engine.sampleCount() < 6) {
            scoreNumber.text = "—"
            scoreHint.text = getString(R.string.score_insufficient)
            return
        }
        scoreNumber.text = score.toString()
        scoreGrade.text = gradeText(engine.grade(score))
        scoreHint.text = getString(R.string.score_done)
        renderBreakdown()
    }

    private fun renderBreakdown() {
        breakdownContainer.removeAllViews()
        addBreakdownRow(getString(R.string.score_hard_accel),
            getString(R.string.score_times, engine.hardAccelCount()))
        addBreakdownRow(getString(R.string.score_hard_brake),
            getString(R.string.score_times, engine.hardBrakeCount()))
        addBreakdownRow(getString(R.string.score_high_rpm),
            getString(R.string.score_percent, (engine.highRpmRatio() * 100).toInt()))
        addBreakdownRow(getString(R.string.score_overspeed),
            getString(R.string.score_percent, (engine.overspeedRatio() * 100).toInt()))
        addBreakdownRow(getString(R.string.score_idle),
            getString(R.string.score_percent, (engine.idleRatio() * 100).toInt()))
    }

    private fun addBreakdownRow(label: String, value: String) {
        breakdownContainer.addView(
            TextView(this).apply {
                text = "$label：$value"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 14f
                setPadding(0, dp(3), 0, dp(3))
            }
        )
    }

    private fun gradeText(grade: DrivingScoreEngine.Grade): String = when (grade) {
        DrivingScoreEngine.Grade.EXCELLENT -> getString(R.string.score_grade_excellent)
        DrivingScoreEngine.Grade.GOOD -> getString(R.string.score_grade_good)
        DrivingScoreEngine.Grade.FAIR -> getString(R.string.score_grade_fair)
        DrivingScoreEngine.Grade.POOR -> getString(R.string.score_grade_poor)
    }

    // ===== ObdManager.Listener =====

    override fun onStateChanged(state: ObdManager.State) {
        renderState(state)
    }

    override fun onLiveData(data: ObdManager.LiveData) {
        if (!recording) return
        engine.addSample(System.currentTimeMillis(), data.rpm, data.speed)
        val count = engine.sampleCount()
        if (count >= 6) {
            val score = engine.score()
            scoreNumber.text = score.toString()
            scoreGrade.text = gradeText(engine.grade(score))
            scoreHint.text = getString(R.string.score_live, count)
            renderBreakdown()
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

/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.ObdManager
import java.util.Locale

/**
 * 馬力/扭力估算：
 *  - 即時功率：由引擎扭力（Nm）× 轉速（RPM）推算功率（kW / HP）。
 *  - 加速推算：由車重與 0-100 km/h 秒數估算平均功率與峰值馬力。
 */
class DynoActivity : BaseActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var tabLive: TextView
    private lateinit var tabAccel: TextView
    private lateinit var panelLive: LinearLayout
    private lateinit var panelAccel: LinearLayout

    private lateinit var rpmValue: TextView
    private lateinit var torqueValue: TextView
    private lateinit var powerKw: TextView
    private lateinit var powerHp: TextView
    private lateinit var peakValue: TextView
    private lateinit var peakAt: TextView
    private lateinit var liveHint: TextView

    private lateinit var weightField: EditText
    private lateinit var timeField: EditText
    private lateinit var avgResult: TextView
    private lateinit var peakResult: TextView

    private var peakHp = 0f
    private var peakHpRpm = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dyno)

        tabLive = findViewById(R.id.tab_live)
        tabAccel = findViewById(R.id.tab_accel)
        panelLive = findViewById(R.id.panel_live)
        panelAccel = findViewById(R.id.panel_accel)

        rpmValue = findViewById(R.id.dyno_rpm_value)
        torqueValue = findViewById(R.id.dyno_torque_value)
        powerKw = findViewById(R.id.dyno_power_kw)
        powerHp = findViewById(R.id.dyno_power_hp)
        peakValue = findViewById(R.id.dyno_peak_value)
        peakAt = findViewById(R.id.dyno_peak_at)
        liveHint = findViewById(R.id.dyno_live_hint)

        weightField = findViewById(R.id.dyno_weight)
        timeField = findViewById(R.id.dyno_time)
        avgResult = findViewById(R.id.dyno_avg_result)
        peakResult = findViewById(R.id.dyno_peak_result)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_calc_dyno).setOnClickListener { calculateAccel() }
        tabLive.setOnClickListener { selectTab(0) }
        tabAccel.setOnClickListener { selectTab(1) }
        selectTab(0)

        obd.addListener(this)
        renderState(obd.state)
    }

    override fun onDestroy() {
        obd.removeListener(this)
        super.onDestroy()
    }

    private fun selectTab(index: Int) {
        fun style(tab: TextView, selected: Boolean) {
            tab.setBackgroundResource(if (selected) R.drawable.bg_button else R.drawable.bg_card)
            tab.setTextColor(getColor(if (selected) R.color.text_primary else R.color.text_secondary))
        }
        style(tabLive, index == 0)
        style(tabAccel, index == 1)
        panelLive.visibility = if (index == 0) View.VISIBLE else View.GONE
        panelAccel.visibility = if (index == 1) View.VISIBLE else View.GONE
    }

    private fun calculateAccel() {
        val weight = weightField.text.toString().toDoubleOrNull()
        val seconds = timeField.text.toString().toDoubleOrNull()
        if (weight == null || weight <= 0 || seconds == null || seconds <= 0) {
            Toast.makeText(this, R.string.alerts_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        // 平均功率 = ½ × m × v² ÷ t（v = 100 km/h = 27.78 m/s，忽略阻力與傳動損失）
        val v = 27.78
        val avgKw = 0.5 * weight * v * v / seconds / 1000.0
        val avgHp = avgKw * 1.341022
        val peakHpEst = avgHp * 1.6
        avgResult.text = getString(R.string.dyno_kw_hp, avgKw, avgHp)
        peakResult.text = getString(R.string.dyno_kw_hp, peakHpEst / 1.341022, peakHpEst)
    }

    override fun onStateChanged(state: ObdManager.State) {
        renderState(state)
    }

    override fun onLiveData(data: ObdManager.LiveData) {
        val rpm = data.rpm ?: 0
        val torque = data.torqueNm
        rpmValue.text = String.format(Locale.US, "%d", rpm)
        if (torque == null) {
            torqueValue.text = "—"
            powerKw.text = "—"
            powerHp.text = "—"
            liveHint.text = getString(R.string.dyno_no_torque)
            return
        }
        liveHint.text = getString(R.string.dyno_no_live)
        torqueValue.text = String.format(Locale.US, "%.0f Nm", torque)
        val kw = torque * rpm / 9549.3f
        val hp = kw * 1.341022f
        powerKw.text = String.format(Locale.US, "%.1f", kw)
        powerHp.text = String.format(Locale.US, "%.1f", hp)
        if (hp > peakHp && rpm > 0) {
            peakHp = hp
            peakHpRpm = rpm
        }
        peakValue.text = String.format(Locale.US, "%.1f HP", peakHp)
        peakAt.text = if (peakHpRpm > 0)
            getString(R.string.dyno_peak_at, peakHpRpm) else "—"
    }

    private fun renderState(state: ObdManager.State) {
        liveHint.text = when (state) {
            ObdManager.State.Ready -> getString(R.string.dyno_no_live)
            else -> getString(R.string.obd_disconnected)
        }
    }
}

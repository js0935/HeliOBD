/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.DemoConfig
import com.heli.obd.elm.ObdManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HUD 抬頭顯示：黑底全螢幕大字，供夜間騎乘時遠距閱讀。
 * 車速特大、轉速/水溫/電壓大字卡片；連線狀態即時顯示；
 * 未連線時可直接在畫面上開啟模擬模式；數據超限自動變紅警示。
 */
class HudActivity : BaseActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var speedValue: TextView
    private lateinit var rpmValue: TextView
    private lateinit var tempValue: TextView
    private lateinit var voltageValue: TextView
    private lateinit var clockText: TextView
    private lateinit var statusText: TextView
    private lateinit var warningText: TextView
    private lateinit var demoBtn: TextView

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat = lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val clockTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            clockText.text = clockFormat.value.format(Date(now))
            clockHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hud)
        hideSystemUi()

        speedValue = findViewById(R.id.hud_speed_value)
        rpmValue = findViewById(R.id.hud_rpm_value)
        tempValue = findViewById(R.id.hud_temp_value)
        voltageValue = findViewById(R.id.hud_voltage_value)
        clockText = findViewById(R.id.hud_clock)
        statusText = findViewById(R.id.hud_status)
        warningText = findViewById(R.id.hud_warning)
        demoBtn = findViewById(R.id.hud_demo_btn)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        demoBtn.setOnClickListener {
            DemoConfig.setEnabled(this, true)
            obd.setDemoMode(true)
            Toast.makeText(this, R.string.hud_demo_enabled, Toast.LENGTH_SHORT).show()
            renderConnection()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        obd.addListener(this)
        clockHandler.post(clockTick)
        renderConnection()
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockTick)
        obd.removeListener(this)
        super.onPause()
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun renderConnection() {
        val demo = obd.isDemoMode()
        val connected = obd.isConnected()
        demoBtn.visibility = if (connected || demo) View.GONE else View.VISIBLE
        when {
            demo -> setStatus(getString(R.string.hud_status_demo), 0xFF00B4D8.toInt())
            connected -> setStatus(getString(R.string.hud_status_connected), 0xFF2ECC71.toInt())
            else -> setStatus(getString(R.string.hud_status_disconnected), 0xFFF1C40F.toInt())
        }
    }

    private fun setStatus(text: String, color: Int) {
        statusText.text = text
        statusText.setTextColor(color)
    }

    override fun onStateChanged(state: ObdManager.State) {
        renderConnection()
    }

    override fun onLiveData(data: ObdManager.LiveData) {
        speedValue.text = data.speed?.toString() ?: "--"
        rpmValue.text = data.rpm?.toString() ?: "--"
        tempValue.text = data.coolant?.toString() ?: "--"
        voltageValue.text = data.voltage?.let { "%.1f".format(it) } ?: "--"

        val warnings = mutableListOf<String>()
        data.coolant?.let { c ->
            tempValue.setTextColor(if (c >= COOLANT_WARN) WARN_RED else 0xFFFFFFFF.toInt())
            if (c >= COOLANT_WARN) warnings += getString(R.string.hud_warn_coolant, c)
        }
        data.rpm?.let { r ->
            rpmValue.setTextColor(if (r >= RPM_WARN) WARN_RED else 0xFF00B4D8.toInt())
            if (r >= RPM_WARN) warnings += getString(R.string.hud_warn_rpm, r)
        }
        data.voltage?.let { v ->
            voltageValue.setTextColor(if (v <= VOLT_WARN) WARN_RED else 0xFFFFFFFF.toInt())
            if (v <= VOLT_WARN) warnings += getString(R.string.hud_warn_voltage, "%.1f".format(v))
        }
        warningText.text = if (warnings.isEmpty()) "" else "⚠ " + warnings.joinToString("、")
        warningText.visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val COOLANT_WARN = 110
        private const val RPM_WARN = 9000
        private const val VOLT_WARN = 11.5
        private const val WARN_RED = 0xFFFF5252.toInt()
    }
}

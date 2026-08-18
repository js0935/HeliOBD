/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.EvapTest
import com.heli.obd.elm.O2Test
import com.heli.obd.elm.ObdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * O2/EVAP 測試畫面：
 *  - 氧感測器測試（mode 05）：讀取各感測器濃/稀切換閾值與轉換時間
 *  - EVAP 蒸發排放測試（mode 08）：雙向控制執行系統洩漏測試
 */
class O2EvapActivity : BaseActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var o2Container: LinearLayout
    private lateinit var evapStatusText: TextView
    private lateinit var btnO2: Button
    private lateinit var btnEvap: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_o2_evap)

        o2Container = findViewById(R.id.o2_list)
        evapStatusText = findViewById(R.id.evap_status)
        btnO2 = findViewById(R.id.btn_o2)
        btnEvap = findViewById(R.id.btn_evap)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnO2.setOnClickListener { readO2Tests() }
        btnEvap.setOnClickListener { runEvapTest() }
    }

    private fun readO2Tests() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        o2Container.removeAllViews()
        val busy = BusyUi.mark(btnO2, getString(R.string.busy_reading))
        lifecycleScope.launch {
            try {
                val tests = withContext(Dispatchers.IO) { obd.readO2Tests() }
                renderO2Tests(tests)
            } finally {
                busy.done()
            }
        }
    }

    private fun renderO2Tests(tests: List<O2Test>) {
        if (tests.isEmpty()) {
            o2Container.addView(TextView(this).apply {
                text = getString(R.string.o2_test_none)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(dp(2), dp(6), dp(2), dp(6))
            })
            return
        }
        for (t in tests) {
            val valueText = t.value?.let { String.format(Locale.US, "%.3f %s", it, t.unit) } ?: "—"
            o2Container.addView(TextView(this).apply {
                text = String.format(
                    Locale.US,
                    "%s · %s：%s",
                    getString(R.string.o2_test_sensor, t.sensor),
                    getString(t.nameRes),
                    valueText,
                )
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), dp(5), dp(2), dp(5))
            })
        }
    }

    private fun runEvapTest() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        evapStatusText.text = getString(R.string.evap_status_running)
        val busy = BusyUi.mark(btnEvap, getString(R.string.busy_sending))
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { obd.runEvapTest() }
                renderEvapStatus(result)
            } finally {
                busy.done()
            }
        }
    }

    private fun renderEvapStatus(result: EvapTest?) {
        val statusRes = result?.statusRes ?: R.string.evap_status_unknown
        evapStatusText.text = getString(statusRes)
        val color = when (result?.status) {
            2 -> R.color.success
            3 -> R.color.danger
            else -> R.color.text_primary
        }
        evapStatusText.setTextColor(getColor(color))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

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
import com.heli.obd.elm.EcuModule
import com.heli.obd.elm.ObdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * ECU 模組掃描畫面：依序探測常見 11-bit CAN header，
 * 列出車上回應的 ECU 模組（引擎/變速箱/ABS…）。
 */
class EcuScanActivity : BaseActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var statusText: TextView
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecu_scan)

        statusText = findViewById(R.id.ecu_status)
        container = findViewById(R.id.ecu_list)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_scan).setOnClickListener { startScan() }
    }

    private fun startScan() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        statusText.text = getString(R.string.ecu_scanning)
        container.removeAllViews()
        findViewById<Button>(R.id.btn_scan).isEnabled = false

        lifecycleScope.launch {
            val modules = withContext(Dispatchers.IO) { obd.scanEcuModules() }
            renderModules(modules)
            findViewById<Button>(R.id.btn_scan).isEnabled = true
        }
    }

    private fun renderModules(modules: List<EcuModule>) {
        if (modules.isEmpty()) {
            statusText.text = getString(R.string.ecu_none)
            return
        }
        statusText.text = getString(R.string.ecu_count, modules.size)
        for (m in modules) {
            container.addView(TextView(this).apply {
                text = String.format(Locale.US, "%s  %s", m.header, getString(m.nameRes))
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(6), dp(4), dp(6))
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

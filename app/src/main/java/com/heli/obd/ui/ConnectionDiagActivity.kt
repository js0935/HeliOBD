/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.ConnectionDiag
import com.heli.obd.elm.ObdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 連線診斷：以 AT 指令查詢 adapter 版本 / 裝置描述 / 電瓶電壓 / 通訊協定。
 */
class ConnectionDiagActivity : BaseActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var versionText: TextView
    private lateinit var deviceText: TextView
    private lateinit var voltageText: TextView
    private lateinit var protocolText: TextView
    private lateinit var protocolNumText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection_diag)

        versionText = findViewById(R.id.diag_version)
        deviceText = findViewById(R.id.diag_device)
        voltageText = findViewById(R.id.diag_voltage)
        protocolText = findViewById(R.id.diag_protocol)
        protocolNumText = findViewById(R.id.diag_protocol_number)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_diag_refresh).setOnClickListener { load() }
        load()
    }

    private fun load() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        val btn = findViewById<Button>(R.id.btn_diag_refresh)
        val busy = BusyUi.mark(btn, getString(R.string.busy_reading))
        lifecycleScope.launch {
            try {
                val diag = withContext(Dispatchers.IO) { obd.readConnectionDiag() }
                render(diag)
            } finally {
                busy.done()
            }
        }
    }

    private fun render(diag: ConnectionDiag?) {
        versionText.text = diag?.version ?: "—"
        deviceText.text = diag?.deviceDesc ?: "—"
        voltageText.text = diag?.voltage?.let { "%.1f V".format(it) } ?: "—"
        protocolText.text = diag?.protocol ?: "—"
        protocolNumText.text = diag?.protocolNumber ?: "—"
    }
}

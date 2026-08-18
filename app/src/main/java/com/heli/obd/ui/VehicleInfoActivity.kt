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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 車輛資訊（Mode 09）：VIN / 校正 ID / CVN / ECU 名稱 + 通訊協定與電壓。
 */
class VehicleInfoActivity : BaseActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private data class VehicleInfoData(
        val vin: String?,
        val calibrationId: String?,
        val cvn: String?,
        val ecuName: String?,
        val diag: ConnectionDiag?,
    )

    private lateinit var vinText: TextView
    private lateinit var calibrationText: TextView
    private lateinit var cvnText: TextView
    private lateinit var ecuNameText: TextView
    private lateinit var protocolText: TextView
    private lateinit var protocolNumberText: TextView
    private lateinit var voltageText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle_info)

        vinText = findViewById(R.id.vehicle_vin)
        calibrationText = findViewById(R.id.vehicle_calibration)
        cvnText = findViewById(R.id.vehicle_cvn)
        ecuNameText = findViewById(R.id.vehicle_ecu_name)
        protocolText = findViewById(R.id.vehicle_protocol)
        protocolNumberText = findViewById(R.id.vehicle_protocol_number)
        voltageText = findViewById(R.id.vehicle_voltage)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_vehicle_info_refresh).setOnClickListener { load() }
        load()
    }

    private fun load() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        val btn = findViewById<Button>(R.id.btn_vehicle_info_refresh)
        val busy = BusyUi.mark(btn, getString(R.string.busy_reading))
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    VehicleInfoData(
                        vin = obd.readVin(),
                        calibrationId = obd.readCalibrationId(),
                        cvn = obd.readCvn(),
                        ecuName = obd.readEcuName(),
                        diag = obd.readConnectionDiag(),
                    )
                }
                render(data)
            } finally {
                busy.done()
            }
        }
    }

    private fun render(data: VehicleInfoData) {
        vinText.text = data.vin ?: "—"
        calibrationText.text = data.calibrationId ?: "—"
        cvnText.text = data.cvn ?: "—"
        ecuNameText.text = data.ecuName ?: "—"
        protocolText.text = data.diag?.protocol ?: "—"
        protocolNumberText.text = data.diag?.protocolNumber ?: "—"
        voltageText.text = data.diag?.voltage?.let { String.format(Locale.US, "%.1f V", it) } ?: "—"
    }
}

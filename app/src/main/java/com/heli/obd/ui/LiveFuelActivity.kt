/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.ObdManager
import java.util.Locale

/**
 * 即時油耗：由燃油率（L/h）與車速（km/h）推算 L/100km 與 km/L；
 * 加油校準以「兩次加油間里程 ÷ 加油量」的實際油耗回饋校準顯示值。
 */
class LiveFuelActivity : BaseActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var fuelRateValue: TextView
    private lateinit var l100Value: TextView
    private lateinit var kmlValue: TextView
    private lateinit var statusHint: TextView
    private lateinit var factorText: TextView
    private lateinit var odometerField: EditText
    private lateinit var litersField: EditText
    private lateinit var actualText: TextView

    private var lastTick = 0L
    private var accDistanceKm = 0.0
    private var accFuelL = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_fuel)

        fuelRateValue = findViewById(R.id.fuel_rate_value)
        l100Value = findViewById(R.id.fuel_l100_value)
        kmlValue = findViewById(R.id.fuel_kml_value)
        statusHint = findViewById(R.id.fuel_status_hint)
        factorText = findViewById(R.id.fuel_factor_text)
        odometerField = findViewById(R.id.fuel_odometer)
        litersField = findViewById(R.id.fuel_liters)
        actualText = findViewById(R.id.fuel_actual_text)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_calc_fuel).setOnClickListener { calibrate() }
        findViewById<View>(R.id.btn_reset_factor).setOnClickListener {
            setFactor(1.0f)
            Toast.makeText(this, R.string.fuel_reset_factor, Toast.LENGTH_SHORT).show()
        }

        renderFactor()
        obd.addListener(this)
        renderState(obd.state)
    }

    override fun onDestroy() {
        obd.removeListener(this)
        super.onDestroy()
    }

    private fun factor(): Float = getSharedPreferences("fuel_prefs", Context.MODE_PRIVATE)
        .getFloat("calibrationFactor", 1.0f)

    private fun setFactor(value: Float) {
        getSharedPreferences("fuel_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("calibrationFactor", value).apply()
        renderFactor()
    }

    private fun renderFactor() {
        factorText.text = getString(R.string.fuel_factor, factor())
    }

    override fun onStateChanged(state: ObdManager.State) {
        renderState(state)
    }

    override fun onLiveData(data: ObdManager.LiveData) {
        val now = System.currentTimeMillis()
        val dtSec = if (lastTick == 0L) 0.0 else (now - lastTick).coerceAtMost(5000) / 1000.0
        lastTick = now

        val rate = data.fuelRate
        if (rate == null) {
            fuelRateValue.text = getString(R.string.common_dash)
            l100Value.text = getString(R.string.common_dash)
            kmlValue.text = getString(R.string.common_dash)
            return
        }
        fuelRateValue.text = String.format(Locale.US, "%.1f", rate)
        val speed = data.speed ?: 0
        if (speed > 0 && rate > 0) {
            val f = factor()
            val l100 = rate / speed * 100.0 * f
            val kml = speed / (rate * f)
            l100Value.text = String.format(Locale.US, "%.1f", l100)
            kmlValue.text = String.format(Locale.US, "%.1f", kml)
            statusHint.text = getString(R.string.obd_connected)
            // 累計行駛距離與燃油（供校準比對）
            if (dtSec > 0) {
                accDistanceKm += speed * dtSec / 3600.0
                accFuelL += rate * dtSec / 3600.0
            }
        } else {
            l100Value.text = getString(R.string.common_dash)
            kmlValue.text = getString(R.string.common_dash)
            statusHint.text = getString(R.string.fuel_no_speed)
        }
    }

    private fun calibrate() {
        val odometer = odometerField.text.toString().toDoubleOrNull()
        val liters = litersField.text.toString().toDoubleOrNull()
        if (odometer == null || odometer <= 0 || liters == null || liters <= 0) {
            Toast.makeText(this, R.string.fuel_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val actualL100 = liters / odometer * 100.0
        val actualKml = odometer / liters
        actualText.text = getString(R.string.fuel_actual_result, actualL100, actualKml)

        // 以本趟累計的顯示平均值比對，回饋校準係數
        if (accFuelL > 0.1 && accDistanceKm > 1.0) {
            val shownAvgL100 = accFuelL / accDistanceKm * 100.0
            if (shownAvgL100 > 0.5) {
                val newFactor = (factor() * actualL100 / shownAvgL100).toFloat()
                    .coerceIn(0.5f, 2.5f)
                setFactor(newFactor)
                Toast.makeText(this, getString(R.string.fuel_factor_applied, newFactor), Toast.LENGTH_SHORT).show()
            }
        }
        accDistanceKm = 0.0
        accFuelL = 0.0
    }

    private fun renderState(state: ObdManager.State) {
        if (state != ObdManager.State.Ready) {
            fuelRateValue.text = getString(R.string.common_dash)
            l100Value.text = getString(R.string.common_dash)
            kmlValue.text = getString(R.string.common_dash)
            statusHint.text = getString(R.string.obd_disconnected)
        }
    }
}

package com.heli.obd.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.maintenance.MaintenanceStore
import com.heli.obd.elm.ObdManager
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 保養提醒 + 電池健康畫面。
 * 里程由車速積分估計並持久化；電池狀態以即時電壓判斷。
 */
class MaintenanceActivity : AppCompatActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)
    private lateinit var store: MaintenanceStore

    private lateinit var statusText: TextView
    private lateinit var dueBanner: TextView
    private lateinit var remainingText: TextView
    private lateinit var odometerText: TextView
    private lateinit var voltageText: TextView
    private lateinit var batteryStateText: TextView
    private lateinit var voltageMinText: TextView
    private lateinit var editLastKm: EditText
    private lateinit var editLastDate: EditText
    private lateinit var editIntervalKm: EditText
    private lateinit var editIntervalDays: EditText

    private var lastSampleMs = 0L
    private var minVoltage = Float.MAX_VALUE
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maintenance)

        store = MaintenanceStore(this)
        statusText = findViewById(R.id.mnt_status_text)
        dueBanner = findViewById(R.id.mnt_due_banner)
        remainingText = findViewById(R.id.mnt_remaining)
        odometerText = findViewById(R.id.mnt_odometer)
        voltageText = findViewById(R.id.mnt_voltage)
        batteryStateText = findViewById(R.id.mnt_battery_state)
        voltageMinText = findViewById(R.id.mnt_voltage_min)
        editLastKm = findViewById(R.id.mnt_edit_last_km)
        editLastDate = findViewById(R.id.mnt_edit_last_date)
        editIntervalKm = findViewById(R.id.mnt_edit_interval_km)
        editIntervalDays = findViewById(R.id.mnt_edit_interval_days)

        findViewById<Button>(R.id.btn_mnt_save).setOnClickListener { saveSettings() }
        loadSettingsToForm()
        refreshMaintenance()

        obd.addListener(this)
        renderState(obd.state)
    }

    override fun onDestroy() {
        obd.removeListener(this)
        super.onDestroy()
    }

    private fun loadSettingsToForm() {
        editLastKm.setText(store.lastServiceKm.toString())
        editLastDate.setText(dateFormat.format(store.lastServiceDateMs))
        editIntervalKm.setText(store.serviceIntervalKm.toString())
        editIntervalDays.setText(store.serviceIntervalDays.toString())
    }

    private fun saveSettings() {
        val lastKm = editLastKm.text.toString().toIntOrNull() ?: 0
        val intervalKm = editIntervalKm.text.toString().toIntOrNull() ?: 3000
        val intervalDays = editIntervalDays.text.toString().toIntOrNull() ?: 180
        val date = editLastDate.text.toString().toLongOrNull()
        store.lastServiceKm = lastKm
        store.serviceIntervalKm = intervalKm.coerceIn(100, 100000)
        store.serviceIntervalDays = intervalDays.coerceIn(30, 3650)
        if (date != null) {
            store.lastServiceDateMs = runCatching {
                dateFormat.parse(date.toString())?.time ?: store.lastServiceDateMs
            }.getOrDefault(store.lastServiceDateMs)
        }
        refreshMaintenance()
        Toast.makeText(this, R.string.mnt_saved, Toast.LENGTH_SHORT).show()
    }

    private fun refreshMaintenance() {
        val remainingKm = store.remainingKm()
        val remainingDays = store.remainingDays()
        val due = store.isDue()

        dueBanner.visibility = if (due) View.VISIBLE else View.GONE
        dueBanner.text = if (due) getString(R.string.mnt_due_banner) else ""

        remainingText.text = if (remainingKm == null) {
            getString(R.string.mnt_remaining_none)
        } else {
            val days = if (store.lastServiceKm > 0) remainingDays.toString() else "—"
            getString(R.string.mnt_remaining, remainingKm, days)
        }
        odometerText.text = getString(
            R.string.mnt_odometer, store.currentKm, store.lastServiceKm
        )
    }

    // ===== ObdManager.Listener =====

    override fun onStateChanged(state: ObdManager.State) {
        renderState(state)
    }

    override fun onLiveData(data: ObdManager.LiveData) {
        val now = System.currentTimeMillis()
        if (lastSampleMs > 0) {
            val dtSec = (now - lastSampleMs) / 1000f
            data.speed?.let { speed ->
                if (speed > 0 && dtSec in 0.05f..10f) {
                    store.currentKm = store.currentKm + (speed * dtSec / 3600f).toInt()
                }
            }
        }
        lastSampleMs = now

        data.voltage?.let { v ->
            if (v < minVoltage) minVoltage = v
            voltageText.text = getString(R.string.mnt_voltage_value, v)
            batteryStateText.text = when {
                v >= 13.5f -> getString(R.string.mnt_battery_charging)
                v >= 12.4f -> getString(R.string.mnt_battery_normal)
                else -> getString(R.string.mnt_battery_low)
            }
            batteryStateText.setTextColor(
                getColor(if (v >= 12.4f) R.color.success else R.color.danger)
            )
            voltageMinText.text = if (minVoltage != Float.MAX_VALUE) {
                getString(R.string.mnt_voltage_min, minVoltage)
            } else {
                getString(R.string.mnt_voltage_min_none)
            }
        }
        refreshMaintenance()
    }

    private fun renderState(state: ObdManager.State) {
        statusText.text = when (state) {
            ObdManager.State.Ready -> getString(R.string.obd_connected)
            else -> getString(R.string.obd_disconnected)
        }
    }
}

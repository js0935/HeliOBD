package com.heli.obd.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.heli.obd.R

/**
 * 閾值警示設定：設定水溫上限 / 轉速上限 / 電壓下限，超限時由 AlertMonitor 發出提醒。
 */
class AlertsActivity : AppCompatActivity() {

    private lateinit var enabledSwitch: Switch
    private lateinit var coolantField: EditText
    private lateinit var rpmField: EditText
    private lateinit var voltageField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alerts)

        enabledSwitch = findViewById(R.id.alert_enabled)
        coolantField = findViewById(R.id.alert_coolant)
        rpmField = findViewById(R.id.alert_rpm)
        voltageField = findViewById(R.id.alert_voltage)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }
        loadPrefs()
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences("alert_prefs", MODE_PRIVATE)
        enabledSwitch.isChecked = prefs.getBoolean("enabled", false)
        coolantField.setText(prefs.getInt("coolantMax", 110).toString())
        rpmField.setText(prefs.getInt("rpmMax", 9000).toString())
        voltageField.setText(prefs.getFloat("voltageMin", 11.5f).toString())
    }

    private fun save() {
        val coolant = coolantField.text.toString().toIntOrNull()
        val rpm = rpmField.text.toString().toIntOrNull()
        val voltage = voltageField.text.toString().toDoubleOrNull()
        if (coolant == null || rpm == null || voltage == null) {
            Toast.makeText(this, R.string.alerts_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("alert_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", enabledSwitch.isChecked)
            .putInt("coolantMax", coolant)
            .putInt("rpmMax", rpm)
            .putFloat("voltageMin", voltage.toFloat())
            .apply()
        Toast.makeText(this, R.string.alerts_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}

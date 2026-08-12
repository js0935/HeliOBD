/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import com.heli.obd.BaseActivity
import com.heli.obd.R
import java.util.Locale

/**
 * 閾值警示設定：設定水溫上限 / 轉速上限 / 電壓下限，超限時由 AlertMonitor 發出提醒。
 */
class AlertsActivity : BaseActivity() {

    private lateinit var enabledSwitch: Switch
    private lateinit var voiceSwitch: Switch
    private lateinit var coolantField: EditText
    private lateinit var rpmField: EditText
    private lateinit var voltageField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alerts)

        enabledSwitch = findViewById(R.id.alert_enabled)
        voiceSwitch = findViewById(R.id.alert_voice)
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
        voiceSwitch.isChecked = prefs.getBoolean("voice", true)
        coolantField.setText(String.format(Locale.US, "%d", prefs.getInt("coolantMax", 110)))
        rpmField.setText(String.format(Locale.US, "%d", prefs.getInt("rpmMax", 9000)))
        voltageField.setText(String.format(Locale.US, "%.1f", prefs.getFloat("voltageMin", 11.5f)))
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
            .putBoolean("voice", voiceSwitch.isChecked)
            .putInt("coolantMax", coolant)
            .putInt("rpmMax", rpm)
            .putFloat("voltageMin", voltage.toFloat())
            .apply()
        Toast.makeText(this, R.string.alerts_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}

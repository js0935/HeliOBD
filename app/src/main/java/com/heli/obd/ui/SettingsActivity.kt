/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import com.heli.obd.App
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.BtPermissions
import com.heli.obd.elm.ObdManager

/**
 * 連線設定：OBD 連線/斷開控制（仿車機 App 的連線管理），
 * 以及自訂 ELM327 初始化指令（每行一個 AT 指令），
 * 於每次連線初始化時依序執行，用於相容性調整。
 */
class SettingsActivity : BaseActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var elmCmdsField: EditText
    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var appearanceValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        elmCmdsField = findViewById(R.id.settings_elm_cmds)
        statusText = findViewById(R.id.settings_status_text)
        connectBtn = findViewById(R.id.btn_connect_settings)
        disconnectBtn = findViewById(R.id.btn_disconnect_settings)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }

        appearanceValue = findViewById(R.id.settings_appearance_value)
        renderAppearance()
        findViewById<View>(R.id.settings_appearance_row).setOnClickListener { pickAppearance() }

        connectBtn.setOnClickListener { ensurePermissionAndConnect() }
        disconnectBtn.setOnClickListener {
            obd.disconnect()
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
        }

        elmCmdsField.setText(
            getSharedPreferences(ObdManager.PREFS, MODE_PRIVATE)
                .getString(ObdManager.KEY_ELM_CMDS, "")
                .orEmpty()
        )

        obd.addListener(this)
        renderState(obd.state)
    }

    override fun onDestroy() {
        obd.removeListener(this)
        super.onDestroy()
    }

    // ===== ObdManager.Listener =====

    override fun onStateChanged(state: ObdManager.State) {
        renderState(state)
    }

    override fun onLiveData(data: ObdManager.LiveData) {
        // 連線設定畫面不需即時數據
    }

    // ===== 連線 =====

    private fun ensurePermissionAndConnect() {
        if (!locationEnabledIfNeeded()) return
        if (BtPermissions.hasAll(this)) {
            connectFlow()
        } else {
            requestPermissions(BtPermissions.required(), REQ_BT_PERMISSION)
        }
    }

    /** 有上次連線裝置就直接連線，否則掃描選擇 ELM327 裝置 */
    private fun connectFlow() {
        val address = obd.lastDeviceAddress()
        if (address != null) {
            val device = runCatching {
                BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
            }.getOrNull()
            if (device != null) {
                connectTo(device)
                return
            }
        }
        pickDevice()
    }

    private fun pickDevice() {
        obd.discover { devices ->
            if (devices.isEmpty()) {
                Toast.makeText(this, R.string.obd_no_device, Toast.LENGTH_LONG).show()
                return@discover
            }
            val names = devices.map { it.name ?: it.address }
            AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                .setTitle(R.string.obd_select_device)
                .setItems(names.toTypedArray()) { _, which ->
                    connectTo(devices[which])
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        obd.connect(device) { success, message ->
            if (!success) {
                Toast.makeText(
                    this,
                    message?.let { getString(R.string.obd_connect_failed, it) }
                        ?: getString(R.string.obd_init_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Android 11 以下掃描藍牙需位置服務開啟，關閉時引導使用者開啟並回傳 false */
    private fun locationEnabledIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return true
        val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!enabled) {
            AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                .setTitle(R.string.obd_location_title)
                .setMessage(R.string.obd_location_message)
                .setPositiveButton(R.string.obd_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
        return enabled
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_PERMISSION) {
            if (BtPermissions.hasAll(this)) connectFlow()
            else Toast.makeText(this, R.string.obd_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderState(state: ObdManager.State) {
        when (state) {
            ObdManager.State.Idle -> {
                statusText.text = getString(R.string.obd_disconnected)
                statusText.setTextColor(getColor(R.color.text_secondary))
                connectBtn.visibility = View.VISIBLE
                disconnectBtn.visibility = View.GONE
            }
            ObdManager.State.Connecting -> {
                statusText.text = getString(R.string.obd_connecting)
                statusText.setTextColor(getColor(R.color.lock))
                connectBtn.visibility = View.VISIBLE
                disconnectBtn.visibility = View.GONE
            }
            ObdManager.State.Ready -> {
                statusText.text = getString(R.string.obd_connected)
                statusText.setTextColor(getColor(R.color.success))
                connectBtn.visibility = View.GONE
                disconnectBtn.visibility = View.VISIBLE
            }
            is ObdManager.State.Error -> {
                statusText.text = getString(R.string.obd_disconnected)
                statusText.setTextColor(getColor(R.color.danger))
                connectBtn.visibility = View.VISIBLE
                disconnectBtn.visibility = View.GONE
            }
        }
    }

    private fun save() {
        val cmds = elmCmdsField.text.toString().trim()
        getSharedPreferences(ObdManager.PREFS, MODE_PRIVATE)
            .edit()
            .putString(ObdManager.KEY_ELM_CMDS, cmds)
            .apply()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun appearanceMode(): String =
        getSharedPreferences(ObdManager.PREFS, MODE_PRIVATE)
            .getString(App.KEY_APPEARANCE, "system")
            .orEmpty()

    private fun renderAppearance() {
        val labelRes = when (appearanceMode()) {
            "dark" -> R.string.settings_appearance_dark
            "light" -> R.string.settings_appearance_light
            else -> R.string.settings_appearance_system
        }
        appearanceValue.text = getString(R.string.settings_appearance_value, getString(labelRes))
    }

    private fun pickAppearance() {
        val options = listOf(
            "system" to R.string.settings_appearance_system,
            "light" to R.string.settings_appearance_light,
            "dark" to R.string.settings_appearance_dark,
        )
        val labels = options.map { getString(it.second) }.toTypedArray()
        val checked = options.indexOfFirst { it.first == appearanceMode() }.coerceAtLeast(0)
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.settings_appearance_title)
            .setSingleChoiceItems(labels, checked) { _, which ->
                val mode = options[which].first
                getSharedPreferences(ObdManager.PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(App.KEY_APPEARANCE, mode)
                    .apply()
                applyNightMode(mode)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun applyNightMode(mode: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    companion object {
        private const val REQ_BT_PERMISSION = 100
    }
}

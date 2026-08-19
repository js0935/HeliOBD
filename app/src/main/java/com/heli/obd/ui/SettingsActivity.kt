/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.heli.obd.App
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.backup.BackupStore
import com.heli.obd.elm.BtPermissions
import com.heli.obd.elm.ObdLog
import com.heli.obd.elm.LogUploader
import com.heli.obd.elm.ObdManager
import com.heli.obd.elm.TransportTarget
import com.heli.obd.llm.LlmClient
import com.heli.obd.llm.LlmStore
import com.heli.obd.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var exportBtn: Button
    private lateinit var importBtn: Button
    private lateinit var checkBtn: Button
    private lateinit var appearanceValue: TextView
    private lateinit var llmBaseUrlField: EditText
    private lateinit var llmApiKeyField: EditText
    private lateinit var llmModelField: EditText

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportBackup(uri)
        }

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importBackup(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        elmCmdsField = findViewById(R.id.settings_elm_cmds)
        statusText = findViewById(R.id.settings_status_text)
        connectBtn = findViewById(R.id.btn_connect_settings)
        disconnectBtn = findViewById(R.id.btn_disconnect_settings)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }

        exportBtn = findViewById(R.id.btn_backup_export)
        exportBtn.setOnClickListener {
            exportBackupLauncher.launch("HeliOBD_Backup.json")
        }
        importBtn = findViewById(R.id.btn_backup_import)
        importBtn.setOnClickListener {
            importBackupLauncher.launch(arrayOf("application/json", "text/*"))
        }

        appearanceValue = findViewById(R.id.settings_appearance_value)
        renderAppearance()
        findViewById<View>(R.id.settings_appearance_row).setOnClickListener { pickAppearance() }

        llmBaseUrlField = findViewById(R.id.settings_llm_base_url)
        llmApiKeyField = findViewById(R.id.settings_llm_api_key)
        llmModelField = findViewById(R.id.settings_llm_model)
        llmApiKeyField.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        llmApiKeyField.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        val llm = LlmStore.load(this)
        llmBaseUrlField.setText(llm.baseUrl)
        llmApiKeyField.setText(llm.apiKey)
        llmModelField.setText(llm.model)

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

        val autoReconnectSwitch = findViewById<SwitchCompat>(R.id.settings_auto_reconnect)
        autoReconnectSwitch.isChecked = obd.isAutoReconnectEnabled()
        autoReconnectSwitch.setOnClickListener {
            obd.setAutoReconnectEnabled(autoReconnectSwitch.isChecked)
        }

        findViewById<TextView>(R.id.settings_log_path).text = getString(
            R.string.settings_log_summary,
            ObdLog.currentDirPath().ifEmpty { getString(R.string.settings_log_none) },
        )

        findViewById<Button>(R.id.btn_log_share).setOnClickListener { shareLog() }

        val autoUploadSwitch = findViewById<SwitchCompat>(R.id.settings_auto_upload_github)
        autoUploadSwitch.isChecked = LogUploader.isAutoUploadEnabled(this)
        autoUploadSwitch.setOnClickListener {
            LogUploader.setAutoUploadEnabled(this, autoUploadSwitch.isChecked)
        }

        val autoUpdateSwitch = findViewById<SwitchCompat>(R.id.settings_auto_update)
        autoUpdateSwitch.isChecked = UpdateChecker.isAutoUpdateEnabled(this)
        autoUpdateSwitch.setOnClickListener {
            UpdateChecker.setAutoUpdateEnabled(this, autoUpdateSwitch.isChecked)
        }
        checkBtn = findViewById(R.id.btn_check_update)
        checkBtn.setOnClickListener { checkUpdateNow() }

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

    // ===== LOG 管理 =====

    private val logPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) doShareLog() }

    private fun shareLog() {
        doShareLog()
    }

    private fun doShareLog() {
        ObdLog.log("doShareLog start")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            LogUploader.clearPendingFlags(this)
        }
        val file = LogUploader.latestLogFile(this)
        ObdLog.log("doShareLog file=${file?.absolutePath ?: "null"}")
        if (file == null) {
            Toast.makeText(this, R.string.log_no_file, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val ok = LogUploader.shareLogFile(this, file)
            ObdLog.log("doShareLog shareLogFile result=$ok")
            if (!ok) {
                Toast.makeText(this, "無法分享 LOG 檔案", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            ObdLog.log("doShareLog EXCEPTION: ${e.message}")
            Toast.makeText(this, "分享失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 自動更新 =====

    /** 立即檢查 GitHub 最新版：有新版彈出下載確認，否則提示已是最新 */
    private fun checkUpdateNow() {
        val busy = BusyUi.mark(checkBtn, getString(R.string.busy_checking))
        lifecycleScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
                if (release?.apkUrl != null && UpdateChecker.isNewer(localVersion(), release.version)) {
                    AlertDialog.Builder(this@SettingsActivity, R.style.Theme_HeliOBD_Dialog)
                        .setTitle(R.string.update_available_title)
                        .setMessage(getString(R.string.update_available_body, release.version))
                        .setPositiveButton(R.string.update_action_download) { _, _ -> downloadAndInstall() }
                        .setNegativeButton(R.string.common_cancel, null)
                        .show()
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.settings_update_none, Toast.LENGTH_SHORT).show()
                }
            } finally {
                busy.done()
            }
        }
    }

    private fun localVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    private fun downloadAndInstall() {
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
            val url = release?.apkUrl
            if (url == null) {
                Toast.makeText(this@SettingsActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(this@SettingsActivity, R.string.update_downloading, Toast.LENGTH_SHORT).show()
            val ok = withContext(Dispatchers.IO) { UpdateChecker.download(this@SettingsActivity, url) }
            if (!ok) {
                Toast.makeText(this@SettingsActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!UpdateChecker.canInstall(this@SettingsActivity)) {
                promptEnableInstall()
                return@launch
            }
            if (UpdateChecker.install(this@SettingsActivity)) {
                Toast.makeText(this@SettingsActivity, R.string.update_install, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SettingsActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Android 8+ 首次安裝需先允許「安裝未知來源」；引導前往本機設定 */
    private fun promptEnableInstall() {
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_body)
            .setPositiveButton(R.string.update_install_permission_open) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:$packageName".toUri(),
                    )
                )
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    // ===== 連線 =====

    private fun ensurePermissionAndConnect() {
        if (!locationEnabledIfNeeded()) return
        if (BtPermissions.hasAll(this)) {
            connectFlow()
        } else {
            requestPermissions(BtPermissions.required() + BtPermissions.storage(), REQ_BT_PERMISSION)
        }
    }

    /** 有上次連線目標就直接連線，否則掃描選擇 ELM327 裝置 */
    private fun connectFlow() {
        when (val t = obd.lastTarget()) {
            is TransportTarget.Wifi -> {
                connectToWifi(t.host, t.port)
                return
            }
            is TransportTarget.ClassicBt -> {
                connectTo(t.device)
                return
            }
            is TransportTarget.BleBt -> {
                connectToBle(t.device)
                return
            }
            null -> Unit
        }
        pickDevice()
    }

    @android.annotation.SuppressLint("MissingPermission") // 權限由本頁於 pickDevice 前申請
    private fun pickDevice() {
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.obd_connection_type_title)
            .setItems(
                arrayOf(
                    getString(R.string.obd_connection_type_classic),
                    getString(R.string.obd_connection_type_ble),
                    getString(R.string.obd_connection_type_wifi),
                )
            ) { _, which ->
                when (which) {
                    0 -> pickClassicDevice()
                    1 -> pickBleDevice()
                    2 -> pickWifiDevice()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    @android.annotation.SuppressLint("MissingPermission") // 權限由使用者於連線選擇前統一申請
    private fun pickClassicDevice() {
        val busy = BusyUi.mark(connectBtn, getString(R.string.busy_scanning))
        obd.discover { devices ->
            busy.done()
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

    @android.annotation.SuppressLint("MissingPermission") // 權限由使用者於連線選擇前統一申請
    private fun pickBleDevice() {
        val busy = BusyUi.mark(connectBtn, getString(R.string.busy_scanning))
        obd.discoverBle { devices ->
            busy.done()
            if (devices.isEmpty()) {
                Toast.makeText(this, R.string.obd_no_device, Toast.LENGTH_LONG).show()
                return@discoverBle
            }
            val names = devices.map { it.name ?: it.address }
            AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                .setTitle(R.string.obd_select_device)
                .setItems(names.toTypedArray()) { _, which ->
                    connectToBle(devices[which])
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    private fun pickWifiDevice() {
        val saved = (obd.lastTarget() as? TransportTarget.Wifi)
        val input = EditText(this).apply {
            setText(saved?.let { "${it.host}:${it.port}" } ?: DEFAULT_WIFI_ADDRESS)
            hint = DEFAULT_WIFI_ADDRESS
        }
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.obd_wifi_title)
            .setView(input)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                val (host, port) = parseWifiAddress(input.text.toString())
                if (host == null) {
                    Toast.makeText(this, R.string.obd_wifi_invalid, Toast.LENGTH_LONG).show()
                } else {
                    connectToWifi(host, port)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun connectTo(device: BluetoothDevice) {
        obd.connect(device) { success, message ->
            if (!success) {
                showConnectGuide(message)
            } else {
                checkSuspiciousAdapter()
            }
        }
    }

    private fun connectToBle(device: BluetoothDevice) {
        obd.connectBle(device) { success, message ->
            if (!success) {
                showConnectGuide(message)
            } else {
                checkSuspiciousAdapter()
            }
        }
    }

    private fun connectToWifi(host: String, port: Int) {
        obd.connectWifi(host, port) { success, message ->
            if (!success) {
                showConnectGuide(message)
            } else {
                checkSuspiciousAdapter()
            }
        }
    }

    /** 解析使用者輸入的 `IP:port`；空白 port 或格式錯誤回傳 (null, 0) */
    private fun parseWifiAddress(text: String): Pair<String?, Int> {
        val t = text.trim()
        val host = t.substringBefore(':', missingDelimiterValue = "")
        if (host.isEmpty()) return null to 0
        val portStr = t.substringAfter(':', missingDelimiterValue = DEFAULT_WIFI_PORT.toString())
        val port = portStr.toIntOrNull() ?: return null to 0
        if (port !in 1..65535) return null to 0
        return host to port
    }

    /** 連線失敗引導：依序檢查插頭／點火／其他 App／通訊協定，避免新手卡關 */
    private fun showConnectGuide(message: String?) {
        val detail = message?.let { getString(R.string.obd_connect_failed, it) }
            ?: getString(R.string.obd_init_failed)
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.obd_connect_guide_title)
            .setMessage(getString(R.string.obd_connect_guide_body, detail))
            .setPositiveButton(R.string.obd_connect_guide_retry) { _, _ -> pickDevice() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 連線成功後於背景偵測山寨晶片（ATI 讀取會阻塞，不可在主執行緒執行） */
    private fun checkSuspiciousAdapter() {
        lifecycleScope.launch {
            val suspicious = withContext(Dispatchers.IO) { obd.isSuspiciousAdapter() }
            if (suspicious) {
                Toast.makeText(this@SettingsActivity, R.string.obd_adapter_suspicious, Toast.LENGTH_LONG).show()
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
        LlmStore.save(
            this,
            LlmClient.Config(
                baseUrl = llmBaseUrlField.text.toString(),
                apiKey = llmApiKeyField.text.toString(),
                model = llmModelField.text.toString(),
            ),
        )
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun exportBackup(uri: Uri) {
        val busy = BusyUi.mark(exportBtn, getString(R.string.busy_exporting))
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { BackupStore.export(this@SettingsActivity) }
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("null stream")
                }.onSuccess {
                    Toast.makeText(this@SettingsActivity, R.string.backup_exported, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this@SettingsActivity, R.string.backup_import_failed, Toast.LENGTH_SHORT).show()
                }
            } finally {
                busy.done()
            }
        }
    }

    private fun importBackup(uri: Uri) {
        val busy = BusyUi.mark(importBtn, getString(R.string.busy_importing))
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.use { input ->
                            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        }
                    }.getOrNull()
                }
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@SettingsActivity, R.string.backup_import_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val restored = withContext(Dispatchers.IO) { BackupStore.import(this@SettingsActivity, text) }
                if (restored < 0) {
                    Toast.makeText(this@SettingsActivity, R.string.backup_import_failed, Toast.LENGTH_SHORT).show()
                } else {
                    renderState(obd.state)
                    Toast.makeText(
                        this@SettingsActivity,
                        resources.getQuantityString(R.plurals.backup_imported, restored, restored),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                busy.done()
            }
        }
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
        private const val DEFAULT_WIFI_ADDRESS = "192.168.0.10:35000"
        private const val DEFAULT_WIFI_PORT = 35000
    }
}

/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.core.content.edit
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.heli.obd.R
import com.heli.obd.pid.PidEvaluator
import com.heli.obd.pid.PidStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * OBD 藍牙連線管理員（ELM327）。
 *
 * 職責：
 * - 掃描 / 列出 ELM327 藍牙裝置（classic SPP）
 * - 建立 RFCOMM socket 連線
 * - ELM327 AT 指令初始化（ATZ、ATE0、ATL1、ATS1、ATH0、ATSP0）
 * - 指令收發（同步、鎖保護、以 '>' 為回應終止符）
 * - 即時數據輪詢（Coroutine 背景執行）
 * - 故障碼讀取 / 清除
 *
 * 藍牙權限（Android 8–11：BLUETOOTH/ADMIN + 定位；Android 12+：BLUETOOTH_SCAN/CONNECT）
 * 由 Activity 層於連線前請求。
 */
class ObdManager(
    private val appContext: Context,
    private val transportFactory: (TransportTarget) -> ObdTransport = { target ->
        when (target) {
            is TransportTarget.ClassicBt -> BluetoothTransport()
            is TransportTarget.BleBt -> BleTransport()
            is TransportTarget.Wifi -> WifiTransport()
        }
    },
) {

    // ===== 狀態 =====

    sealed class State {
        data object Idle : State()
        data object Connecting : State()
        data object Ready : State()
        data class Error(val message: String) : State()
    }

    /** 一筆即時數據快照（null = 尚未取得該值） */
    data class LiveData(
        val rpm: Int?,
        val speed: Int?,
        val coolant: Int?,
        val voltage: Float?,
        val load: Int? = null,
        val maf: Float? = null,
        val fuelRate: Float? = null,
        val torqueNm: Float? = null,
        val fuelTrim: Float? = null,
        val afr: Float? = null,
        val intake: Int? = null,
        val customValues: Map<Long, Float?> = emptyMap(),
        val map: Int? = null,
        val timingAdvance: Float? = null,
        val throttle: Int? = null,
        val fuelLevel: Int? = null,
        val moduleVoltage: Float? = null,
        val fuelTrimLong: Float? = null,
        val ambientTemp: Int? = null,
        val oilTemp: Int? = null,
    )

    interface Listener {
        fun onStateChanged(state: State)
        fun onLiveData(data: LiveData)
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("obd_prefs", Context.MODE_PRIVATE)

    private var transport: ObdTransport? = null
    private var pollJob: Job? = null
    private val listeners = mutableListOf<Listener>()

    @Volatile
    private var currentState: State = State.Idle

    /** 最近一次即時數據快照（null = 尚未收到；供畫面進入時立即顯示） */
    @Volatile
    var latestLiveData: LiveData? = null

    /** Demo 模擬模式：啟用時不需藍牙硬體，輪詢改由模擬資料驅動 */
    @Volatile
    private var demoMode = false

    @Volatile
    private var customPids: List<PidStore.CustomPid> = emptyList()

    /** 斷線旗標：偵測到 socket EOF/IOException 後設定，防止重複觸發斷線清理；連線成功時重置 */
    @Volatile
    private var disconnectPending = false

    /** 斷線自動重連開關（obd_prefs）；使用者主動斷線不會觸發重連 */
    @Volatile
    private var reconnectEnabled = prefs.getBoolean(KEY_AUTO_RECONNECT, true)

    /** 目前已嘗試的重連次數（達到上限後停止，等待使用者手動重連） */
    @Volatile
    private var reconnectAttempts = 0

    /** 待執行的重連排程；disconnect() 或關閉重連時取消 */
    private var reconnectJob: Job? = null

    /** 支援的 PID 清單（mode 01 PID 00/20/40 bitmask）；null = 尚未建立（視為全部支援） */
    @Volatile
    private var supportedPids: Set<String>? = null

    /** 連線時的協定編號（ATDPN）；null = 未知/偵測失敗（視為快速協定） */
    @Volatile
    private var protocolNumber: Int? = null

    /** 分號批次是否已知不支援（慢速協定或曾整批無回應/回 '?'）：true 後全部改逐個查詢 */
    @Volatile
    private var batchDisabled = false

    /** 暫停即時數據輪詢（診斷讀取期間避免與輪詢搶同一 socket，拖慢讀取） */
    @Volatile
    private var pollingPaused = false

    /** 暫停即時數據輪詢；與 [resumePolling] 成對使用 */
    fun pausePolling() {
        pollingPaused = true
    }

    /** 恢復即時數據輪詢 */
    fun resumePolling() {
        pollingPaused = false
    }

    /** 執行 [block] 期間暫停輪詢，結束後還原（供診斷讀取使用） */
    private inline fun <T> withPollingPaused(block: () -> T): T {
        val wasPaused = pollingPaused
        pollingPaused = true
        try {
            return block()
        } finally {
            pollingPaused = wasPaused
        }
    }

    /** 是否為 UDS 車款（ISO 14229）：以 22F400 探測成功後啟用 22F4/22F8 DID 讀取 */
    @Volatile
    private var udsMode = false

    /** 最近一次清碼失敗原因（null = 最近成功或尚未清碼） */
    @Volatile
    private var lastClearErrorMsg: String? = null

    /** 目前 UDS 診斷工作階段（ISO 14229 service 10）；連線時重置為預設 */
    @Volatile
    private var currentSession: Int = ObdConstants.SESSION_DEFAULT

    /** ELM327 AT 指令狀態追蹤（供指令去重） */
    private val elmState = ElmState()

    /** 是否為慢速串列協定（ISO 9141-2 / ISO 14230-4 KWP）：需降低輪詢頻率避免指令堆疊 */
    val isSlowProtocol: Boolean
        get() = !demoMode && protocolNumber in ObdConstants.SLOW_PROTOCOL_NUMBERS

    // ===== 自動配對（PAIRING_REQUEST）：ELM327 常見 PIN 1234，自動回應省去手動輸入 =====
    private val pairingReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
            val device = intent.getParcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE) ?: return
            val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, Int.MIN_VALUE)
            try {
                when (variant) {
                    // 0=PIN 輸入、3=Passkey 輸入：回應 ELM327 常見 PIN 1234
                    0, 3 -> DeviceReflection.setPin(device, "1234")
                    // 2=Passkey 確認、4=Consent：直接同意
                    2, 4 -> DeviceReflection.confirmPairing(device)
                }
            } catch (_: Exception) {
                // 隱藏 API 呼叫失敗時忽略，配對退回系統/手動流程
            }
        }
    }

    init {
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                pairingReceiver,
                IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    // ===== 分層輪詢（fast 每輪 / medium 每 2 輪 / slow 每 4 輪，跳過輪沿用上次值） =====
    private var pollTick = 0
    private var lastVoltage: Float? = null
    private var lastIntake: Int? = null
    private var lastLoad: Int? = null
    private var lastMaf: Float? = null
    private var lastFuelRate: Float? = null
    private var lastTorqueNm: Float? = null
    private var lastFuelTrim: Float? = null
    private var lastAfr: Float? = null
    private var lastMap: Int? = null
    private var lastTimingAdvance: Float? = null
    private var lastThrottle: Int? = null
    private var lastFuelLevel: Int? = null
    private var lastModuleVoltage: Float? = null
    private var lastFuelTrimLong: Float? = null
    private var lastAmbientTemp: Int? = null
    private var lastOilTemp: Int? = null
    private var lastCustom: Map<Long, Float?> = emptyMap()

    /** 精簡模式：非 null 時每輪只讀取單一數據（"rpm"/"speed"/"coolant"/"voltage"） */
    private var focusKey: String? = null

    /** 最後一筆完整快照：精簡模式下其餘欄位沿用此基底，切換顯示不會閃空 */
    private var lastFullLiveData: LiveData? = null

    // ===== 歷史數據 ring buffer（key 與 MonitorTiles 一致） =====
    private val history = mutableMapOf<String, ArrayDeque<Float>>()

    // ===== Demo 模擬狀態 =====
    private var simRpm = 1100.0
    private var simTargetRpm = 1500.0
    private var simStartMs = 0L

    val state: State get() = currentState

    fun addListener(listener: Listener) {
        synchronized(listeners) { listeners.add(listener) }
        notifyState(currentState)
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun isConnected(): Boolean = demoMode || transport?.isOpen == true

    fun isDemoMode(): Boolean = demoMode

    /** 上次成功連線的裝置位址（供自動重連） */
    fun lastDeviceAddress(): String? =
        when (val t = lastTarget()) {
            is TransportTarget.ClassicBt -> t.device.address
            is TransportTarget.BleBt -> t.device.address
            else -> null
        }

    /**
     * 上次成功連線的目標（含連線方式）。無記錄回傳 null。
     * 舊版本僅存 MAC 位址，解析時視為經典藍牙，向後相容。
     */
    fun lastTarget(): TransportTarget? {
        val raw = prefs.getString(KEY_LAST_DEVICE, null) ?: return null
        val prefix = raw.substringBefore('|', missingDelimiterValue = "")
        return when (prefix) {
            "classic" -> raw.substringAfter('|').let { remoteDeviceOrNull(it)?.let { d -> TransportTarget.ClassicBt(d) } }
            "ble" -> raw.substringAfter('|').let { remoteDeviceOrNull(it)?.let { d -> TransportTarget.BleBt(d) } }
            "wifi" -> raw.substringAfter('|').split(':', limit = 2).let { parts ->
                if (parts.size == 2) {
                    val port = parts[1].toIntOrNull() ?: return null
                    TransportTarget.Wifi(parts[0], port)
                } else {
                    null
                }
            }
            else -> remoteDeviceOrNull(raw)?.let { d -> TransportTarget.ClassicBt(d) }
        }
    }

    /**
     * 切換 Demo 模擬模式。開啟時不需藍牙連線，立即以模擬資料輪詢；
     * 關閉時若無真實連線則回到 Idle。
     */
    fun setDemoMode(enabled: Boolean) {
        if (demoMode == enabled) return
        demoMode = enabled
        if (enabled) {
            simRpm = 1100.0
            simTargetRpm = 1500.0
            simStartMs = System.currentTimeMillis()
            setState(State.Ready)
            startPolling()
        } else {
            pollJob?.cancel()
            pollJob = null
            if (transport == null) {
                setState(State.Idle)
            }
        }
    }

    // ===== 掃描 =====

    private val adapter: BluetoothAdapter?
        @Suppress("DEPRECATION")
        get() = BluetoothAdapter.getDefaultAdapter()

    /** 掃描 ELM327 裝置：已配對裝置（全部）先列入，再進行 10 秒搜尋合併回傳 */
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("MissingPermission") // 權限由 UI 層於呼叫前統一申請
    fun discover(callback: (List<BluetoothDevice>) -> Unit) {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            callback(emptyList())
            return
        }
        val results = linkedMapOf<String, BluetoothDevice>()
        // 已配對裝置全部列入：使用者既已配對，名稱不含關鍵字也不應被過濾
        bt.bondedDevices?.forEach { results[it.address] = it }

        var reported = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE) ?: return
                        if (isElm327(device)) results[device.address] = device
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        bt.cancelDiscovery()
                        if (!reported) {
                            reported = true
                            runCatching { appContext.unregisterReceiver(this) }
                            mainHandler.post { callback(results.values.toList()) }
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        bt.startDiscovery()

        ioScope.launch {
            delay(10000)
            bt.cancelDiscovery()
            if (!reported) {
                reported = true
                runCatching { appContext.unregisterReceiver(receiver) }
                mainHandler.post { callback(results.values.toList()) }
            }
        }
    }

    /** 掃描 BLE ELM327 裝置：10 秒掃描後合併回傳（BLE 裝置不需先配對） */
    @android.annotation.SuppressLint("MissingPermission") // 權限由 UI 層於呼叫前統一申請
    fun discoverBle(callback: (List<BluetoothDevice>) -> Unit) {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            callback(emptyList())
            return
        }
        val scanner = bt.bluetoothLeScanner
        if (scanner == null) {
            callback(emptyList())
            return
        }
        val results = linkedMapOf<String, BluetoothDevice>()
        var reported = false
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (isElm327(device)) results[device.address] = device
            }

            override fun onScanFailed(errorCode: Int) {
                if (reported) return
                reported = true
                runCatching { scanner.stopScan(this) }
                mainHandler.post { callback(results.values.toList()) }
            }
        }

        scanner.startScan(scanCallback)
        ioScope.launch {
            delay(10000)
            if (!reported) {
                reported = true
                runCatching { scanner.stopScan(scanCallback) }
                mainHandler.post { callback(results.values.toList()) }
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // 權限由 UI 層於呼叫前統一申請
    private fun isElm327(device: BluetoothDevice): Boolean {
        val name = device.name?.trim().orEmpty()
        // 名稱空白（廉價 ELM327 常見）或包含 OBD/ELM 關鍵字都列入
        if (name.isEmpty()) return true
        val upper = name.uppercase()
        return ObdConstants.ELM327_NAME_KEYWORDS.any { upper.contains(it) }
    }

    // ===== 連線 =====

    /** 經典藍牙（RFCOMM）連線 */
    fun connect(device: BluetoothDevice, callback: (success: Boolean, message: String?) -> Unit) {
        connectTarget(TransportTarget.ClassicBt(device), callback)
    }

    /** BLE ELM327 連線 */
    fun connectBle(device: BluetoothDevice, callback: (success: Boolean, message: String?) -> Unit) {
        connectTarget(TransportTarget.BleBt(device), callback)
    }

    /** WiFi ELM327 連線（TCP） */
    fun connectWifi(host: String, port: Int, callback: (success: Boolean, message: String?) -> Unit) {
        connectTarget(TransportTarget.Wifi(host, port), callback)
    }

    /** 依連線目標建立連線（含 ELM327 初始化、協定偵測、開始輪詢） */
    fun connectTarget(target: TransportTarget, callback: (success: Boolean, message: String?) -> Unit) {
        disconnectPending = false
        supportedPids = null
        udsMode = false
        currentSession = ObdConstants.SESSION_DEFAULT
        setState(State.Connecting)
        ObdLog.start(appContext, target.displayName)
        ioScope.launch {
            val (ok, msg) = try {
                val t = transportFactory(target)
                if (!t.open(target)) throw IOException("connect failed (${target.displayName})")
                transport = t
                // 連線成立後稍等，避免首批 AT 指令被剛建立的 socket 丟棄
                Thread.sleep(500)
                val initOk = initElm327()
                if (!initOk) {
                    closeQuietly()
                    false to appContext.getString(R.string.obd_init_failed)
                } else {
                    detectUdsMode()
                    loadSupportedPids()
                    if (supportedPids == null && !demoMode) {
                        // 自動協定讀不到支援清單：主動搜尋協定（標準協定 → KWP 特殊配方）
                        if (tryProtocolSearch()) {
                            loadSupportedPids()
                        }
                    }
                    true to null
                }
            } catch (e: Exception) {
                closeQuietly()
                ObdLog.log("CONNECT FAILED ${e.message.orEmpty().replace('\n', ' ')}")
                ObdLog.stop()
                false to (e.message ?: appContext.getString(R.string.obd_connect_error))
            }
            if (ok) {
                reconnectAttempts = 0
                prefs.edit().putString(KEY_LAST_DEVICE, encodeTarget(target)).apply()
                protocolNumber = detectProtocolNumber()
                setState(State.Ready)
                startPolling()
            } else {
                setState(State.Error(msg ?: appContext.getString(R.string.obd_connect_error)))
            }
            mainHandler.post { callback(ok, msg) }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        disconnectPending = false
        pollJob?.cancel()
        pollJob = null
        protocolNumber = null
        closeQuietly()
        ObdLog.log("DISCONNECT manual")
        ObdLog.stop()
        setState(State.Idle)
    }

    /**
     * 偵測通訊協定編號（ATDPN，hex）。部分山寨晶片回 '?' 或空白 → 回 null（視為快速協定）。
     * ATDPN 失敗時以 `01 00` 探測回應的格式兜底推斷（KWP 帶 3-byte header、CAN 回 `41 00 …`）。
     */
    private fun detectProtocolNumber(): Int? {
        val raw = sendCommand(ObdConstants.CMD_PROTOCOL_NUMBER)
            ?.let { lastLine(it) }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
        // ELM327 自動協定（ATSP0）下 ATDPN 回 A5（A=auto、5=ISO 14230-4 KWP）等；
        // 需去掉 'A' 前綴再解讀，否則 A5 會被誤判成 165，導致 KWP 不被當慢速協定而仍發批次。
        val dpn = raw?.let { s ->
            val body = if (s.length > 1 && s.startsWith("A", ignoreCase = true)) s.substring(1) else s
            body.toIntOrNull(10) ?: body.toIntOrNull(16)
        }
        if (dpn != null) return dpn
        val probe = sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_SUPPORTED)
            ?: return null
        return ObdDecoder.inferProtocolFromProbe(probe).takeIf { it != 0 }
    }

    /**
     * 偵測是否為 UDS 車款（ISO 14229 ReadDataByIdentifier）。
     * 送 `22 F4 00`（DID 0xF400 = mode 01 PID 00 對應）；正向回應 `62 F4 00 …` 代表支援。
     * 不支援的車款回 NO DATA / ?，維持 udsMode = false。
     */
    private fun detectUdsMode() {
        if (demoMode) {
            udsMode = false
            return
        }
        val resp = sendCommand(ObdConstants.UDS_PROBE_CMD, timeoutMs = ObdConstants.UDS_PROBE_TIMEOUT_MS)
        val tokens = resp?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        udsMode = tokens.size >= 3 &&
            tokens[0].equals(ObdConstants.UDS_RESPONSE_MARKER, ignoreCase = true) &&
            tokens[1].equals("F4", ignoreCase = true)
    }

    /**
     * 自動重連上次成功連線的裝置（無記錄則回呼失敗）。
     * demoMode 下視為成功（無需藍牙）。
     */
    fun connectLastDevice(callback: (success: Boolean, message: String?) -> Unit) {
        if (demoMode) {
            callback(true, null)
            return
        }
        val target = lastTarget()
        if (target == null) {
            callback(false, appContext.getString(R.string.obd_connect_error))
            return
        }
        connectTarget(target, callback)
    }

    /** ELM327 初始化：ATZ 必成功，其餘設定指令失敗不立即放棄（部分山寨晶片回 '?'） */
    private fun initElm327(): Boolean {
        if (sendCommand(ObdConstants.CMD_RESET) == null) return false
        // ATZ 後等待裝置重置，否則後續指令常被忽略（pires 實測）
        Thread.sleep(500)
        // ATE0 送兩次：便宜 ELM327 常漏掉第一次
        sendCommand(ObdConstants.CMD_ECHO_OFF)
        sendCommand(ObdConstants.CMD_ECHO_OFF)
        // 注意：不可關閉換行/空格（ATL0/ATS0）。關閉後多行 PID 回應失去 \n 與空格分隔，
        // ObdDecoder 將無法拆行與拆 byte，導致 mode 01 全部解析失敗（僅 ATRV 電壓仍可讀）。
        listOf(
            ObdConstants.CMD_LINEFEED_ON,
            ObdConstants.CMD_SPACES_ON,
            ObdConstants.CMD_HEADERS_OFF,
            ObdConstants.CMD_AUTO_PROTOCOL,
        ).forEach { cmd ->
            if (sendCommand(cmd) == null && cmd == ObdConstants.CMD_AUTO_PROTOCOL) {
                sendCommand(ObdConstants.CMD_AUTO_PROTOCOL_ALT)
            }
        }
        val custom = prefs.getString(KEY_ELM_CMDS, "").orEmpty()
        custom.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { sendCommand(it) }
        // ATRV 可順帶確認通訊正常（回應含電壓）
        return sendCommand(ObdConstants.CMD_VOLTAGE) != null
    }

    // ===== 指令收發 =====

    /**
     * 送出 ELM327 指令並等待回應（以 '>' 為終止符）。
     * 回應清洗雜訊後取最後一行（ATE0 關閉 echo 後為單行）。失敗或斷線回傳 null。
     */
    fun sendCommand(cmd: String, timeoutMs: Long = ObdConstants.COMMAND_TIMEOUT_MS): String? = synchronized(lock) {
        val t = transport ?: return null
        if (!t.isOpen) return null
        var result: String? = null
        try {
            t.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            elmState.update(cmd)

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            read@ while (System.currentTimeMillis() < deadline) {
                while (t.available() > 0) {
                    val c = t.read()
                    if (c == -1) {
                        markDisconnected()
                        return@synchronized null
                    }
                    if (c.toChar() == '>') {
                        result = lastLine(cleanResponse(sb.toString()))
                        break@read
                    }
                    sb.append(c.toChar())
                }
                Thread.sleep(15)
            }
            if (result == null) {
                result = lastLine(cleanResponse(sb.toString())).takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            markDisconnected()
            return@synchronized null
        }
        ObdLog.log("CMD $cmd -> ${result ?: "NO_RESPONSE"}")
        result
    }

    /**
     * 送出一條 AT 設定指令，若目前 ELM 狀態已達成就跳過（去重，省一次往返）。
     * 適用於反覆的 Before/After 狀態確保（如長指令的 CAN 模式切換）；
     * 回傳回應；已達成跳過時回傳 null（呼叫端不應以 null 視為失敗）。
     */
    fun sendCommandIfNeeded(cmd: String): String? {
        return if (elmState.shouldSkip(cmd)) null else sendCommand(cmd)
    }

    /**
     * 送出 ELM327 指令並回傳「完整」原始回應（清洗雜訊行後保留所有資料行，不含 '>' prompt）。
     * 供 OBD 終端機顯示用；一般功能請使用 sendCommand()（只取最後一行）。
     * 模擬模式下回傳對應的假回應。
     */
    fun sendRawCommand(cmd: String): String? = synchronized(lock) {
        if (demoMode) return@synchronized demoTerminalResponse(cmd)
        val t = transport ?: return null
        if (!t.isOpen) return null
        var result: String? = null
        try {
            t.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            elmState.update(cmd)

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + ObdConstants.COMMAND_TIMEOUT_MS
            read@ while (System.currentTimeMillis() < deadline) {
                while (t.available() > 0) {
                    val c = t.read()
                    if (c == -1) {
                        markDisconnected()
                        return@synchronized null
                    }
                    if (c.toChar() == '>') {
                        result = cleanResponse(sb.toString())
                        break@read
                    }
                    sb.append(c.toChar())
                }
                Thread.sleep(15)
            }
            if (result == null) {
                result = cleanResponse(sb.toString()).takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            markDisconnected()
            return@synchronized null
        }
        val logResp = result?.replace('\n', '|') ?: "NO_RESPONSE"
        ObdLog.log("RAW $cmd -> $logResp")
        result
    }

    /** 模擬模式終端機回應：常用 AT 指令給固定假回應，其餘依模式給簡單回應 */
    private fun demoTerminalResponse(cmd: String): String {
        val c = cmd.trim().uppercase()
        return when {
            c == "ATZ" -> "ELM327 v1.5a"
            c == "ATI" || c == "ATI0" -> "ELM327 v1.5a"
            c == "ATVN" -> "12.34.56"
            c == "ATRV" || c == "ATRV0" -> "13.8V"
            c == "ATE0" || c == "ATL1" || c == "ATS1" || c == "ATH0" ||
                c == "ATSP0" || c == "ATSP A0" || c == "ATAT2" -> "OK"
            c.startsWith("AT") -> "OK"
            c.startsWith("01") || c.startsWith("010") -> "41 ${c.drop(2).padEnd(2, '0')} 00 00"
            c.startsWith("03") -> "43 01 03 00 00 00 00"
            c.startsWith("09") -> "49 02 4D 4F 54 4F 44 49 41 47 00"
            c.startsWith("02") -> "42 ${c.drop(2).padEnd(2, '0')} 00 00"
            else -> "7F 00 12"
        }
    }

    /**
     * 清洗 ELM327 原始回應：移除 SEARCHING… / BUS INIT / STOPPED 等雜訊行與空白行，
     * 保留純資料行。sendRawCommand 保留多行結構；sendCommand 再取最後一行。
     */
    private fun cleanResponse(raw: String): String {
        val lines = raw.lines().mapNotNull { line ->
            val cleaned = line.trim()
            if (cleaned.isEmpty()) return@mapNotNull null
            val upper = cleaned.uppercase()
            if (upper.contains("SEARCHING") || upper.contains("BUS INIT") || upper.contains("STOPPED")) {
                null
            } else {
                cleaned
            }
        }
        return lines.joinToString("\n")
    }

    private fun lastLine(raw: String): String =
        raw.trim().lines().lastOrNull { it.isNotBlank() } ?: ""

    // ===== 即時數據 =====

    /**
     * 建立支援 PID 清單：讀取 mode 01 PID 00/20/40/80/C0 的 32-bit bitmask，
     * 只對支援的 PID 輪詢，避免不支援的 PID 每次浪費一個 timeout 週期。
     * 任一讀取失敗即保留 null（視為全部支援，維持原有行為），不阻斷連線。
     */
    /** 讀取支援 PID 清單；成功回 true，失敗回 false（維持 supportedPids = null 表示全部支援） */
    private fun loadSupportedPids(): Boolean {
        try {
            val mask00 = sendPidRequest(ObdConstants.PID_SUPPORTED)
                ?.let { ObdDecoder.supportedPidMask(it) } ?: return false
            val pids = mutableSetOf<String>()
            for (i in 0 until 32) {
                if ((mask00 shr (31 - i)) and 1L != 0L) pids += (0x01 + i).pidHex()
            }
            if ((mask00 and 1L) != 0L) {
                val mask20 = sendPidRequest("20")
                    ?.let { ObdDecoder.supportedPidMask(it) } ?: return false
                for (i in 0 until 32) {
                    if ((mask20 shr (31 - i)) and 1L != 0L) pids += (0x21 + i).pidHex()
                }
                if ((mask20 and 1L) != 0L) {
                    val mask40 = sendPidRequest("40")
                        ?.let { ObdDecoder.supportedPidMask(it) } ?: return false
                    for (i in 0 until 32) {
                        if ((mask40 shr (31 - i)) and 1L != 0L) pids += (0x41 + i).pidHex()
                    }
                    if ((mask40 and 1L) != 0L) {
                        val mask80 = sendPidRequest("80")
                            ?.let { ObdDecoder.supportedPidMask(it) } ?: return false
                        for (i in 0 until 32) {
                            if ((mask80 shr (31 - i)) and 1L != 0L) pids += (0x81 + i).pidHex()
                        }
                        if ((mask80 and 1L) != 0L) {
                            val maskC0 = sendPidRequest("C0")
                                ?.let { ObdDecoder.supportedPidMask(it) } ?: return false
                            for (i in 0 until 32) {
                                if ((maskC0 shr (31 - i)) and 1L != 0L) pids += (0xC1 + i).pidHex()
                            }
                        }
                    }
                }
            }
            if (pids.isNotEmpty()) {
                supportedPids = pids
                return true
            }
        } catch (_: Exception) {
            // 讀取失敗時維持 null（全部支援），不阻斷連線
        }
        return false
    }

    /**
     * 自動協定（ATSP0）初始化失敗時的主動協定搜尋：
     * 1. 依 Car Scanner 優先序逐一嘗試標準協定（CAN → KWP → ISO9141 → J1850）。
     * 2. 全數失敗再嘗試 KWP/ISO9141 特殊配方（不同鮑率、init 定址、通訊 header）。
     * 每個候選以 mode 01 PID 0C（RPM）有回應為成功判準。
     */
    private fun tryProtocolSearch(): Boolean {
        for ((proto, label) in ObdConstants.PROTOCOL_SEARCH_ORDER) {
            if (disconnectPending || !(transport?.isOpen ?: false)) return false
            if (tryPreset("ATSP$proto", listOf("ATSP$proto"), label)) return true
        }
        for (preset in ObdConstants.KWP_INIT_PRESETS) {
            if (disconnectPending || !(transport?.isOpen ?: false)) return false
            if (tryPreset(preset.label, preset.commands, preset.label)) return true
        }
        return false
    }

    /** 套用一組協定設定指令並以 010C 驗證；成功回 true */
    private fun tryPreset(label: String, commands: List<String>, logLabel: String): Boolean {
        ObdLog.log("PROTOCOL try $logLabel")
        // 每次候選前重設，確保 AT 狀態回到預設（協定/鮑率/header 全清）
        sendCommand(ObdConstants.CMD_RESET)
        Thread.sleep(300)
        for (cmd in commands) {
            if (sendCommand(cmd) == null) return false
        }
        val probe = sendPidRequest(ObdConstants.PID_RPM)
        if (probe != null) {
            ObdLog.log("PROTOCOL OK $logLabel")
            return true
        }
        return false
    }

    /**
     * 依 UDS 狀態送出 mode 01 PID 讀取指令並回傳回應。
     * UDS 車款（22F4 前綴）下回應為 `62 F4 <pid> …`，先 normalize 為 `41 <pid> …`
     * 供現有解碼器直接解析；非 UDS 或非 mode 01 直接送原始指令。
     */
    private fun sendPidRequest(pid: String, mode: String = ObdConstants.MODE_CURRENT_DATA): String? {
        if (udsMode && mode == ObdConstants.MODE_CURRENT_DATA) {
            return sendCommand(ObdConstants.UDS_PID_PREFIX + pid)
                ?.let { ObdDecoder.normalizeUdsResponse(it, targetMode = 0x41) }
        }
        return sendCommand(mode + pid)
    }

    /** PID 是否受支援；清單尚未建立（null）時視為全部支援（保守行為） */
    private fun isPidSupported(pid: String): Boolean =
        supportedPids?.contains(pid) ?: true

    /** Int → ELM PID 十六進位字串（0x0C → "0C"） */
    private fun Int.pidHex(): String =
        (this and 0xFF).toString(16).uppercase().padStart(2, '0')

    /** 設定要隨輪詢一起讀取的自訂 PID（車廠專用感測器）。 */
    fun setCustomPids(pids: List<PidStore.CustomPid>) {
        customPids = pids
    }

    /** 設定精簡模式：每輪只輪詢指定單一數據（key 為 "rpm"/"speed"/"coolant"/"voltage"；null 恢復完整輪詢）。 */
    fun setFocusKey(key: String?) {
        if (key != null && key != "rpm" && key != "speed" && key != "coolant" && key != "voltage") return
        focusKey = key
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = ioScope.launch {
            var intervalMs = if (isSlowProtocol) ObdConstants.POLL_INTERVAL_MS_SLOW
            else ObdConstants.POLL_INTERVAL_MS
            var consecutiveFailures = 0
            while (isActive) {
                if (pollingPaused) {
                    delay(100)
                    continue
                }
                if (!isConnected()) break
                val started = System.currentTimeMillis()
                val data = requestLiveData()
                val elapsed = System.currentTimeMillis() - started
                if (data != null) {
                    val hasValue = listOf(data.rpm, data.speed, data.coolant, data.voltage)
                        .any { it != null }
                    if (hasValue) {
                        consecutiveFailures = 0
                        intervalMs = if (focusKey != null) {
                            // 精簡模式僅查單一數據，間隔壓到最短讓更新貼近即時
                            if (isSlowProtocol) 60L else 120L
                        } else if (isSlowProtocol) {
                            // KWP/ISO9141 已停用合併批次、逐個查詢，指令不會堆疊；
                            // 間隔維持短值，讓轉速/車速/水溫/電壓貼近即時更新
                            elapsed.coerceAtMost(300)
                        } else {
                            // 自適應間隔：單次輪詢耗時逼近目前間隔時放大，避免指令堆疊；
                            // 餘裕充足時緩慢縮小，但不低於基準間隔。
                            if (elapsed > intervalMs * 0.8) {
                                (elapsed * 1.5).toLong().coerceAtMost(ObdConstants.POLL_INTERVAL_MS * 4)
                            } else {
                                (intervalMs * 0.9).toLong().coerceAtLeast(ObdConstants.POLL_INTERVAL_MS)
                            }
                        }
                    } else {
                        // 整輪無任何數據 → 退避
                        consecutiveFailures++
                        intervalMs = (intervalMs * 2).toLong()
                            .coerceAtMost(ObdConstants.POLL_INTERVAL_MS * 8)
                    }
                    mainHandler.post { notifyLiveData(data) }
                } else {
                    consecutiveFailures++
                    intervalMs = (intervalMs * 2).toLong()
                        .coerceAtMost(ObdConstants.POLL_INTERVAL_MS * 8)
                }
                delay(intervalMs)
            }
        }
    }

    /**
     * 批次讀取多個 PID（合併指令如 `01 0C 0D 05`，一次通訊取回多行）。
     * 合併指令失敗或某 PID 缺漏時，缺漏者自動回退為個別 sendCommand。
     * 回傳 PID（2 位大寫 hex）→ 該 PID 回應行；全部失敗回傳全 null。
     * @param mode 服務模式（MODE_CURRENT_DATA="01" 或 MODE_FREEZE_FRAME="02"）
     */
    private fun sendBatchMode01(
        pids: List<String>,
        mode: String = ObdConstants.MODE_CURRENT_DATA,
    ): Map<String, String?> {
        if (pids.isEmpty()) return emptyMap()
        // UDS 的 22F4 每個 DID 獨立回應，無法合併指令，逐個送出並 normalize
        if (udsMode && mode == ObdConstants.MODE_CURRENT_DATA) {
            return pids.associateWith { pid ->
                sendCommand(ObdConstants.UDS_PID_PREFIX + pid)
                    ?.let { ObdDecoder.normalizeUdsResponse(it, targetMode = 0x41) }
            }
        }
        // 慢速協定（KWP/ISO9141）或已知批次不支援：直接逐個查詢，
        // 避免 `01 0C 0D 05 04` 這類合併指令整批 NO_RESPONSE 白等約 2 秒。
        if (isSlowProtocol || batchDisabled) {
            return pids.associateWith { sendCommand(mode + it) }
        }
        val cmd = listOf(mode) + pids
        val raw = sendRawCommand(cmd.joinToString(" "))
        val parsed = raw?.let { ObdDecoder.parseMode01Batch(it, mode.toInt(16)) }.orEmpty()
        val result = pids.associateWith { pid ->
            parsed[pid] ?: sendCommand(mode + pid)
        }
        // 整批無回應或 ELM 回 '?'：批次無效，之後停用避免反覆白等
        if (raw.isNullOrBlank() || raw.trim() == "?") batchDisabled = true
        return result
    }

    fun requestLiveData(): LiveData? {
        if (demoMode) return simulateLiveData().also { recordHistory(it) }
        if (!isConnected()) return null
        val focus = focusKey
        if (focus != null) {
            val focused = readFocused(focus)
            if (focused != null) return focused
            // 尚未有完整快照基底時，先跑一輪完整讀取建立基底，之後維持精簡讀取
        }
        val tick = ++pollTick
        // 慢速協定（KWP/ISO9141）將 medium/slow 分層的週期加倍，降低單輪指令數避免堆疊
        val cycle = if (isSlowProtocol) 2 else 1
        val medium = tick % (2 * cycle) == 1
        val slow = tick % (4 * cycle) == 1
        // 核心 PID（轉速/車速/水溫）每 tick 取回；負載移至 medium 層，縮短每輪往返讓核心數值更新更快
        val core = sendBatchMode01(
            buildList {
                add(ObdConstants.PID_RPM)
                add(ObdConstants.PID_SPEED)
                add(ObdConstants.PID_COOLANT)
            }
        )
        val rpm = core[ObdConstants.PID_RPM]?.let { ObdDecoder.rpm(it) }
        val speed = core[ObdConstants.PID_SPEED]?.let { ObdDecoder.speed(it) }
        val coolant = core[ObdConstants.PID_COOLANT]?.let { ObdDecoder.coolantTemp(it) }
        // 電壓（ATRV）每 tick 讀取，維持電壓即時更新
        val voltage = sendCommand(ObdConstants.CMD_VOLTAGE)
            ?.let { ObdDecoder.voltage(it) }?.also { lastVoltage = it }
            ?: lastVoltage
        // medium 層 PID 批次取回（以支援清單過濾）
        val mediumResponses = if (medium) sendBatchMode01(
            buildList {
                if (isPidSupported(ObdConstants.PID_LOAD)) add(ObdConstants.PID_LOAD)
                if (isPidSupported(ObdConstants.PID_INTAKE)) add(ObdConstants.PID_INTAKE)
                if (isPidSupported(ObdConstants.PID_MAF)) add(ObdConstants.PID_MAF)
                if (isPidSupported(ObdConstants.PID_FUEL_RATE)) add(ObdConstants.PID_FUEL_RATE)
                if (isPidSupported(ObdConstants.PID_TORQUE)) add(ObdConstants.PID_TORQUE)
                if (isPidSupported(ObdConstants.PID_MAP)) add(ObdConstants.PID_MAP)
                if (isPidSupported(ObdConstants.PID_TIMING_ADVANCE)) add(ObdConstants.PID_TIMING_ADVANCE)
                if (isPidSupported(ObdConstants.PID_THROTTLE)) add(ObdConstants.PID_THROTTLE)
                if (isPidSupported(ObdConstants.PID_MODULE_VOLTAGE)) add(ObdConstants.PID_MODULE_VOLTAGE)
            }
        ) else emptyMap()
        val load = mediumResponses[ObdConstants.PID_LOAD]?.let { ObdDecoder.engineLoad(it) }
            ?.also { lastLoad = it } ?: lastLoad
        val intake = mediumResponses[ObdConstants.PID_INTAKE]?.let { ObdDecoder.intakeTemp(it) }
            ?.also { lastIntake = it } ?: lastIntake
        val maf = mediumResponses[ObdConstants.PID_MAF]?.let { ObdDecoder.maf(it) }
            ?.also { lastMaf = it } ?: lastMaf
        val fuelRate = mediumResponses[ObdConstants.PID_FUEL_RATE]?.let { ObdDecoder.fuelRate(it) }
            ?.also { lastFuelRate = it } ?: lastFuelRate
        val torqueNm = mediumResponses[ObdConstants.PID_TORQUE]?.let { ObdDecoder.torqueNm(it) }
            ?.also { lastTorqueNm = it } ?: lastTorqueNm
        val map = mediumResponses[ObdConstants.PID_MAP]?.let { ObdDecoder.manifoldPressure(it) }
            ?.also { lastMap = it } ?: lastMap
        val timingAdvance = mediumResponses[ObdConstants.PID_TIMING_ADVANCE]?.let { ObdDecoder.timingAdvance(it) }
            ?.also { lastTimingAdvance = it } ?: lastTimingAdvance
        val throttle = mediumResponses[ObdConstants.PID_THROTTLE]?.let { ObdDecoder.throttlePosition(it) }
            ?.also { lastThrottle = it } ?: lastThrottle
        val moduleVoltage = mediumResponses[ObdConstants.PID_MODULE_VOLTAGE]?.let { ObdDecoder.moduleVoltage(it) }
            ?.also { lastModuleVoltage = it } ?: lastModuleVoltage
        // slow 層 PID（每 4 tick 一次）批次取回
        val slowResponses = if (slow) sendBatchMode01(
            buildList {
                if (isPidSupported(ObdConstants.PID_SHORT_FUEL_TRIM)) add(ObdConstants.PID_SHORT_FUEL_TRIM)
                if (isPidSupported(ObdConstants.PID_LONG_FUEL_TRIM)) add(ObdConstants.PID_LONG_FUEL_TRIM)
                if (isPidSupported(ObdConstants.PID_WIDEBAND_AFR)) add(ObdConstants.PID_WIDEBAND_AFR)
                if (isPidSupported(ObdConstants.PID_FUEL_LEVEL)) add(ObdConstants.PID_FUEL_LEVEL)
                if (isPidSupported(ObdConstants.PID_AMBIENT_TEMP)) add(ObdConstants.PID_AMBIENT_TEMP)
                if (isPidSupported(ObdConstants.PID_OIL_TEMP)) add(ObdConstants.PID_OIL_TEMP)
            }
        ) else emptyMap()
        val fuelTrim = slowResponses[ObdConstants.PID_SHORT_FUEL_TRIM]?.let { ObdDecoder.fuelTrim(it) }
            ?.also { lastFuelTrim = it } ?: lastFuelTrim
        val fuelTrimLong = slowResponses[ObdConstants.PID_LONG_FUEL_TRIM]?.let { ObdDecoder.fuelTrimLong(it) }
            ?.also { lastFuelTrimLong = it } ?: lastFuelTrimLong
        val afr = slowResponses[ObdConstants.PID_WIDEBAND_AFR]?.let { ObdDecoder.widebandAfr(it) }
            ?.also { lastAfr = it } ?: lastAfr
        val fuelLevel = slowResponses[ObdConstants.PID_FUEL_LEVEL]?.let { ObdDecoder.fuelLevel(it) }
            ?.also { lastFuelLevel = it } ?: lastFuelLevel
        val ambientTemp = slowResponses[ObdConstants.PID_AMBIENT_TEMP]?.let { ObdDecoder.ambientTemp(it) }
            ?.also { lastAmbientTemp = it } ?: lastAmbientTemp
        val oilTemp = slowResponses[ObdConstants.PID_OIL_TEMP]?.let { ObdDecoder.oilTemp(it) }
            ?.also { lastOilTemp = it } ?: lastOilTemp
        val customValues = if (slow) {
            lastCustom = readCustomPids()
            lastCustom
        } else {
            lastCustom
        }
        return LiveData(
            rpm = rpm,
            speed = speed,
            coolant = coolant,
            voltage = voltage,
            load = load,
            maf = maf,
            fuelRate = fuelRate,
            torqueNm = torqueNm,
            fuelTrim = fuelTrim,
            afr = afr,
            intake = intake,
            customValues = customValues,
            map = map,
            timingAdvance = timingAdvance,
            throttle = throttle,
            fuelLevel = fuelLevel,
            moduleVoltage = moduleVoltage,
            fuelTrimLong = fuelTrimLong,
            ambientTemp = ambientTemp,
            oilTemp = oilTemp,
        ).also { recordHistory(it) }.also { lastFullLiveData = it }
    }

    /** 精簡模式：只查詢單一數據並更新對應欄位，其餘欄位沿用最後一筆完整快照。 */
    private fun readFocused(key: String): LiveData? {
        if (!isConnected()) return null
        val base = lastFullLiveData ?: return null
        val updated = when (key) {
            "rpm" -> sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_RPM)
                ?.let { base.copy(rpm = ObdDecoder.rpm(it)) }
            "speed" -> sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_SPEED)
                ?.let { base.copy(speed = ObdDecoder.speed(it)) }
            "coolant" -> sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_COOLANT)
                ?.let { base.copy(coolant = ObdDecoder.coolantTemp(it)) }
            "voltage" -> sendCommand(ObdConstants.CMD_VOLTAGE)
                ?.let { base.copy(voltage = ObdDecoder.voltage(it)) }
            else -> null
        } ?: return null
        return updated.also { recordHistory(it) }
    }

    private fun readCustomPids(): Map<Long, Float?> =
        customPids.associate { p ->
            val raw = sendCommand(p.mode + p.pid)?.let { ObdDecoder.rawValues(it) }
            p.id to raw?.let { PidEvaluator.evaluate(p.formula, it)?.toFloat() }
        }

    /** 指定 key（內建 "rpm"… 或自訂 "custom:{id}"）的歷史序列，最早 → 最新 */
    fun historySeries(key: String): List<Float> =
        synchronized(history) { history[key]?.toList() ?: emptyList() }

    fun historyKeys(): Set<String> =
        synchronized(history) { history.keys.toSet() }

    private fun recordHistory(data: LiveData) {
        fun push(key: String, v: Float?) {
            if (v == null) return
            synchronized(history) {
                val q = history.getOrPut(key) { ArrayDeque() }
                q.addLast(v)
                if (q.size > HISTORY_MAX) q.removeFirst()
            }
        }
        push("rpm", data.rpm?.toFloat())
        push("speed", data.speed?.toFloat())
        push("coolant", data.coolant?.toFloat())
        push("intake", data.intake?.toFloat())
        push("voltage", data.voltage)
        push("load", data.load?.toFloat())
        push("maf", data.maf)
        push("fuelRate", data.fuelRate)
        push("torqueNm", data.torqueNm)
        push("fuelTrim", data.fuelTrim)
        push("afr", data.afr)
        push("map", data.map?.toFloat())
        push("timingAdvance", data.timingAdvance)
        push("throttle", data.throttle?.toFloat())
        push("fuelLevel", data.fuelLevel?.toFloat())
        push("moduleVoltage", data.moduleVoltage)
        push("fuelTrimLong", data.fuelTrimLong)
        push("ambientTemp", data.ambientTemp?.toFloat())
        push("oilTemp", data.oilTemp?.toFloat())
        data.customValues.forEach { (id, v) -> push("custom:$id", v) }
    }

    // ===== 診斷擴充（凍結框 / I/M 就緒 / VIN） =====

    /** 連線診斷：查詢 adapter 版本 / 裝置描述 / 電瓶電壓 / 通訊協定（AT I / AT @1 / AT RV / AT DP / AT DPN） */
    fun readConnectionDiag(): ConnectionDiag? {
        if (demoMode) {
            return ConnectionDiag(
                version = "ELM327 v1.5a",
                deviceDesc = "HeliOBD Demo Adapter",
                voltage = 13.8f,
                protocol = "ISO 15765-4 (CAN 11/500)",
                protocolNumber = "6",
            )
        }
        if (!isConnected()) return null
        return withPollingPaused {
            ConnectionDiag(
                version = sendCommand(ObdConstants.CMD_INFO)?.let { lastLine(it) }?.takeIf { it.isNotBlank() },
                deviceDesc = sendCommand(ObdConstants.CMD_DEVICE_DESC)?.let { lastLine(it) }?.takeIf { it.isNotBlank() },
                voltage = sendCommand(ObdConstants.CMD_VOLTAGE)?.let { ObdDecoder.voltage(it) },
                protocol = sendCommand(ObdConstants.CMD_DESCRIBE_PROTOCOL)?.let { lastLine(it) }?.takeIf { it.isNotBlank() },
                protocolNumber = sendCommand(ObdConstants.CMD_PROTOCOL_NUMBER)?.let { lastLine(it) }?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * 偵測疑似山寨 ELM327：官方版本僅 1.3/1.4/2.1/2.2/2.3，
     * 回報 v1.5 為業界公認的仿冒晶片標記。無法讀取版本或 demoMode 時回傳 false。
     */
    fun isSuspiciousAdapter(): Boolean {
        if (demoMode) return false
        val version = readConnectionDiag()?.version ?: return false
        return version.uppercase().contains("V1.5")
    }

    /** 凍結框：讀取觸發碼 + 關鍵數據的凍結值（mode 02，批次取回） */
    fun readFreezeFrame(): FreezeFrame? {
        if (demoMode) {
            return FreezeFrame(
                triggerDtc = "P0300",
                values = mapOf(
                    R.string.pid_name_coolant to 88, R.string.pid_name_rpm to 3100,
                    R.string.pid_name_speed to 12, R.string.pid_name_load to 42,
                    R.string.pid_name_intake to 45, R.string.pid_name_map to 55,
                    R.string.pid_name_throttle to 30, R.string.pid_name_fuel_level to 60,
                ),
                floatValues = mapOf(
                    R.string.pid_name_maf to 6.2f, R.string.pid_name_timing_advance to 8f,
                    R.string.pid_name_module_voltage to 13.8f,
                    R.string.pid_name_fuel_trim to -2.5f, R.string.pid_name_afr to 14.2f,
                ),
            )
        }
        if (!isConnected()) return null
        return withPollingPaused {
        val triggerRaw = sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_FREEZE_DTC)
        // 觸發碼回應非「41」前綴（log 實例：`0102 -> 10 C5`）代表該車不支援凍結框，
        // 後續 19 個 02xx PID 也只會回無效回應，直接中止讀取
        if (triggerRaw == null || !triggerRaw.trim().startsWith("41")) {
            ObdLog.log("freeze frame trigger 回應無效（${triggerRaw?.trim() ?: "無回應"}），跳過凍結框讀取")
            return null
        }
        val trigger = ObdDecoder.freezeDtc(triggerRaw)
        val intPids = ObdConstants.FREEZE_FRAME_PIDS.map { it.first }
        val intResponses = sendBatchMode01(intPids, mode = ObdConstants.MODE_FREEZE_FRAME)
        if (intResponses.values.none { it != null }) return null
        val values = ObdConstants.FREEZE_FRAME_PIDS.associate { (pid, labelRes) ->
            labelRes to when (pid) {
                ObdConstants.PID_COOLANT -> intResponses[pid]?.let { ObdDecoder.coolantTemp(it) }
                ObdConstants.PID_RPM -> intResponses[pid]?.let { ObdDecoder.rpm(it) }
                ObdConstants.PID_SPEED -> intResponses[pid]?.let { ObdDecoder.speed(it) }
                ObdConstants.PID_LOAD -> intResponses[pid]?.let { ObdDecoder.engineLoad(it) }
                ObdConstants.PID_INTAKE -> intResponses[pid]?.let { ObdDecoder.intakeTemp(it) }
                ObdConstants.PID_MAP -> intResponses[pid]?.let { ObdDecoder.manifoldPressure(it) }
                ObdConstants.PID_THROTTLE -> intResponses[pid]?.let { ObdDecoder.throttlePosition(it) }
                ObdConstants.PID_FUEL_LEVEL -> intResponses[pid]?.let { ObdDecoder.fuelLevel(it) }
                else -> null
            }
        }
        val floatPids = ObdConstants.FREEZE_FRAME_FLOAT_PIDS.map { it.first }
        val floatResponses = sendBatchMode01(floatPids, mode = ObdConstants.MODE_FREEZE_FRAME)
        val floatValues = ObdConstants.FREEZE_FRAME_FLOAT_PIDS.associate { (pid, labelRes) ->
            labelRes to when (pid) {
                ObdConstants.PID_MAF -> floatResponses[pid]?.let { ObdDecoder.maf(it) }
                ObdConstants.PID_TIMING_ADVANCE -> floatResponses[pid]?.let { ObdDecoder.timingAdvance(it) }
                ObdConstants.PID_MODULE_VOLTAGE -> floatResponses[pid]?.let { ObdDecoder.moduleVoltage(it) }
                ObdConstants.PID_SHORT_FUEL_TRIM -> floatResponses[pid]?.let { ObdDecoder.fuelTrim(it) }
                ObdConstants.PID_WIDEBAND_AFR -> floatResponses[pid]?.let { ObdDecoder.widebandAfr(it) }
                else -> null
            }
        }
        return FreezeFrame(trigger, values, floatValues)
        }
    }

    /** I/M 排放就緒狀態（mode 01 PID 01） */
    fun readImReadiness(): ImReadiness? {
        if (demoMode) {
            return ObdDecoder.imReadiness("41 01 01 07 05 07 01")
        }
        if (!isConnected()) return null
        return withPollingPaused {
            val resp = sendPidRequest(ObdConstants.PID_STATUS)
            resp?.let { ObdDecoder.imReadiness(it) }
        }
    }

    /** 車身 VIN（mode 09 PID 02）；長回應可能為 ISO-TP 多幀，故使用完整回應 */
    fun readVin(): String? {
        if (demoMode) return "MOTODIAG-DEMO-VIN-0001"
        if (!isConnected()) return null
        return withPollingPaused {
            val cmd = mode09Command("02")
            val decode: (String?) -> String? = { r -> r?.let { mode09Decode(it) }?.let { ObdDecoder.vin(it) } }
            var result = decode(sendRawCommand(cmd))
            if (result == null && udsMode) {
                result = withExtendedSession { decode(sendRawCommand(cmd)) }
            }
            result
        }
    }

    /** 校正 ID（mode 09 PID 0A）；長回應可能為 ISO-TP 多幀，故使用完整回應 */
    fun readCalibrationId(): String? {
        if (demoMode) return "MOTODIAG-DEMO-CALID"
        if (!isConnected()) return null
        return withPollingPaused {
            val cmd = mode09Command("0A")
            val decode: (String?) -> String? = { r -> r?.let { mode09Decode(it) }?.let { ObdDecoder.calibrationId(it) } }
            var result = decode(sendRawCommand(cmd))
            if (result == null && udsMode) {
                result = withExtendedSession { decode(sendRawCommand(cmd)) }
            }
            result
        }
    }

    /** 校驗號碼（mode 09 PID 0B） */
    fun readCvn(): String? {
        if (demoMode) return "ABCD1234"
        if (!isConnected()) return null
        return withPollingPaused {
            val resp = sendCommand(mode09Command("0B"))
            resp?.let { mode09Decode(it) }?.let { ObdDecoder.cvn(it) }
        }
    }

    /** ECU 名稱（mode 09 PID 0D）；長回應可能為 ISO-TP 多幀，故使用完整回應 */
    fun readEcuName(): String? {
        if (demoMode) return "HeliOBD-Demo-ECU"
        if (!isConnected()) return null
        val cmd = mode09Command("0D")
        val decode: (String?) -> String? = { r -> r?.let { mode09Decode(it) }?.let { ObdDecoder.ecuName(it) } }
        var result = decode(sendRawCommand(cmd))
        if (result == null && udsMode) {
            result = withExtendedSession { decode(sendRawCommand(cmd)) }
        }
        return result
    }

    /**
     * 切換 UDS 診斷工作階段（ISO 14229 service 10）：1=預設、2=程式設計、3=擴充。
     * 部分 ECU 在預設階段限制單次回應長度，切換後可讀取更長的 ISO-TP 多幀資料。
     * 成功回應 `50 <session>`；失敗（負回應/無回應）回 false。
     */
    fun setDiagnosticSession(session: Int): Boolean {
        if (demoMode) return true
        if (!isConnected()) return false
        val resp = sendCommand("%02X%02X".format(ObdConstants.SERVICE_SESSION_CONTROL, session))
        val ok = resp != null &&
            !ObdDecoder.isNoDataOrNegativeResponse(resp, service = ObdConstants.SERVICE_SESSION_CONTROL)
        if (ok) currentSession = session
        return ok
    }

    /** 目前 UDS 診斷工作階段編號（預設 1；非 UDS 車款維持預設） */
    fun currentDiagnosticSession(): Int = currentSession

    /**
     * 於擴充診斷工作階段下執行讀取，結束時回復原工作階段（僅 UDS 車款）。
     * 切換失敗時仍以原工作階段嘗試一次。
     */
    private inline fun <T> withExtendedSession(block: () -> T?): T? {
        val original = currentSession
        if (!udsMode) return block()
        if (original != ObdConstants.SESSION_EXTENDED) {
            if (!setDiagnosticSession(ObdConstants.SESSION_EXTENDED)) return block()
        }
        try {
            return block()
        } finally {
            if (original != ObdConstants.SESSION_EXTENDED) setDiagnosticSession(original)
        }
    }

    /**
     * 依 UDS 狀態送出 mode 09 讀取指令。
     * UDS 車款以 DID 0xF8xx 讀取（`22F802` 等）；非 UDS 直接送 `09 <pid>`。
     */
    private fun mode09Command(pid: String): String {
        return if (udsMode) ObdConstants.UDS_INFO_PREFIX + pid else ObdConstants.MODE_VEHICLE_INFO + pid
    }

    /**
     * 預處理 mode 09 原始回應。
     * UDS 車款回應為 `62 F8 <pid> …`（可能 ISO-TP 多幀），先重組多幀再 normalize 為 `49 <pid> …`
     * 供現有解碼器解析；非 UDS 原樣回傳。
     */
    private fun mode09Decode(raw: String): String {
        if (!udsMode) return raw
        val assembled = ObdDecoder.assembleIsoTp(raw) ?: return raw
        return ObdDecoder.normalizeUdsResponse(
            assembled.joinToString(" ") { "%02X".format(it) },
            targetMode = 0x49,
        )
    }

    /**
     * 偵測到通訊斷線（socket EOF / IOException）時呼叫。
     * 冪等：以 disconnectPending 旗標防止重複清理；連線成功時重置。
     * 清理動作移到背景執行緒，避免在 sendCommand 的 synchronized(lock) 區塊內長時間佔鎖。
     */
    private fun markDisconnected() {
        if (disconnectPending) return
        disconnectPending = true
        ioScope.launch {
            pollJob?.cancel()
            pollJob = null
            closeQuietly()
            ObdLog.log("DISCONNECT unexpected")
            ObdLog.stop()
            setState(State.Idle)
            scheduleReconnect()
        }
    }

    /**
     * 意外斷線後自動重連上次成功連線的裝置。
     * 延遲後嘗試，失敗時最多重試 MAX_RECONNECT_ATTEMPTS 次即停止（需使用者手動重連）。
     * 使用者主動 disconnect()（取消 reconnectJob 且 disconnectPending=false）或重連成功時中止。
     */
    private fun scheduleReconnect() {
        if (!reconnectEnabled || demoMode) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        reconnectJob = ioScope.launch {
            delay(RECONNECT_DELAY_MS)
            // 期間內使用者已主動斷線，或已有其他連線成立 → 取消重連
            if (!disconnectPending || transport?.isOpen == true) return@launch
            reconnectAttempts++
            connectLastDevice { ok, _ -> if (!ok) scheduleReconnect() }
        }
    }

    /** 斷線自動重連是否開啟（設定頁開關） */
    fun isAutoReconnectEnabled(): Boolean = reconnectEnabled

    /** 設定斷線自動重連開關；關閉時一併取消已排程的重連 */
    fun setAutoReconnectEnabled(enabled: Boolean) {
        reconnectEnabled = enabled
        prefs.edit { putBoolean(KEY_AUTO_RECONNECT, enabled) }
        if (!enabled) {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempts = 0
        }
    }

    /** 車載監控測試結果（mode 06）：依 MONITOR_TEST_TIDS 掃描各監控族群 TID */
    fun readMonitorTests(): List<MonitorTest> {
        if (demoMode) {
            return listOf(
                MonitorTest(1, 0x00, 0, ObdConstants.MONITOR_TEST_NAMES[0x00], cylinder = 1, scaledValue = 0.0, unit = "", minValue = 0.0, maxValue = 0.0, passed = true, tidNameRes = ObdConstants.monitorTidNameRes(1)),
                MonitorTest(1, 0x00, 0, ObdConstants.MONITOR_TEST_NAMES[0x00], cylinder = 2, scaledValue = 0.0, unit = "", minValue = 0.0, maxValue = 0.0, passed = true, tidNameRes = ObdConstants.monitorTidNameRes(1)),
                MonitorTest(1, 0x00, 0, ObdConstants.MONITOR_TEST_NAMES[0x00], cylinder = 3, scaledValue = 0.0, unit = "", minValue = 0.0, maxValue = 0.0, passed = true, tidNameRes = ObdConstants.monitorTidNameRes(1)),
                MonitorTest(1, 0x01, 200, ObdConstants.MONITOR_TEST_NAMES[0x01], scaledValue = 200.0, minValue = 0.0, maxValue = 200.0, passed = true, tidNameRes = ObdConstants.monitorTidNameRes(1)),
                MonitorTest(1, 0x03, 200, ObdConstants.MONITOR_TEST_NAMES[0x03], scaledValue = 200.0, minValue = 0.0, maxValue = 200.0, passed = true, tidNameRes = ObdConstants.monitorTidNameRes(1)),
            )
        }
        if (!isConnected()) return emptyList()
        val result = mutableListOf<MonitorTest>()
        for (tid in ObdConstants.MONITOR_TEST_TIDS) {
            val resp = sendCommand(ObdConstants.MODE_MONITOR_TESTS + tid)
            if (resp != null) {
                // 回應非「46」前綴（負回應／無效）代表該車不支援此監控族群，
                // 其餘 TID 也是相同回應，直接中止掃描節省通訊時間
                if (!resp.trim().startsWith("46")) {
                    ObdLog.log("mode06 TID $tid 回應非 46 前綴（${resp.trim()}），中止剩餘掃描")
                    break
                }
                result += ObdDecoder.monitorTests(resp)
            }
        }
        return result
    }

    /**
     * 氧感測器測試（mode 05）：僅非 CAN 協定支援，依序嘗試 01-18 PID，
     * 每組 6 個 PID 對應一顆感測器；不支援的 PID 回應 NO DATA 自動跳過。
     */
    fun readO2Tests(): List<O2Test> {
        if (demoMode) {
            return listOf(
                O2Test(1, 0x01, ObdConstants.O2_TEST_NAMES[0x01]!!, 0.45f, "V"),
                O2Test(1, 0x02, ObdConstants.O2_TEST_NAMES[0x02]!!, 0.10f, "V"),
                O2Test(1, 0x05, ObdConstants.O2_TEST_NAMES[0x05]!!, 0.08f, "s"),
                O2Test(2, 0x07, ObdConstants.O2_TEST_NAMES[0x01]!!, 0.55f, "V"),
            )
        }
        if (!isConnected()) return emptyList()
        val result = mutableListOf<O2Test>()
        for (pid in ObdConstants.O2_TEST_PIDS) {
            val resp = sendCommand(ObdConstants.MODE_O2_TEST + pid)
            if (resp != null) {
                // 回應非「45」前綴（負回應／無效）代表該車不支援 mode 05 氧感測器測試，
                // 其餘 PID 也會回相同回應，直接中止掃描
                if (!resp.trim().startsWith("45")) {
                    ObdLog.log("mode05 PID $pid 回應非 45 前綴（${resp.trim()}），中止剩餘掃描")
                    break
                }
                result += ObdDecoder.o2Tests(resp)
            }
        }
        return result
    }

    /** EVAP 系統洩漏測試（mode 08 PID 01）：回傳測試狀態 */
    fun runEvapTest(): EvapTest? {
        if (demoMode) return EvapTest(2, ObdConstants.EVAP_STATUS_NAMES[2]!!)
        if (!isConnected()) return null
        val resp = sendCommand(ObdConstants.MODE_EVAP_TEST + ObdConstants.EVAP_TEST_PID)
        return resp?.let { ObdDecoder.evapStatus(it) }
    }

    /**
     * ECU 模組掃描：依序對常見 11-bit CAN header 送出 mode 01 PID 00，
     * 有回應代表該模組存在（引擎/變速箱/ABS…）。掃描後重設 header。
     */
    fun scanEcuModules(): List<EcuModule> {
        if (demoMode) {
            return listOf(
                EcuModule("7E0", R.string.ecu_engine),
                EcuModule("7E1", R.string.ecu_transmission),
                EcuModule("7E2", R.string.ecu_abs),
            )
        }
        if (!isConnected()) return emptyList()
        val found = mutableListOf<EcuModule>()
        for ((header, nameRes) in ObdConstants.ECU_HEADERS) {
            sendCommand(ObdConstants.CMD_SET_HEADER + header)
            val resp = sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_SUPPORTED)
            if (resp?.startsWith("41 00") == true) {
                found.add(EcuModule(header, nameRes))
            }
        }
        sendCommand(ObdConstants.CMD_SET_HEADER + "0")
        return found
    }

    // ===== 故障碼 =====

    fun readDtc(): List<String> {
        if (demoMode) return listOf("P0300", "P0135")
        if (!isConnected()) return emptyList()
        return withPollingPaused {
            val resp = sendCommandWithPendingRetry(ObdConstants.MODE_DTC) ?: return emptyList()
            ObdDecoder.dtcList(resp, protocolNumber = protocolNumber)
        }
    }

    /** 待處理故障碼（mode 07）：尚未確立的間歇性故障 */
    fun readPendingDtc(): List<String> {
        if (demoMode) return listOf("P0301")
        if (!isConnected()) return emptyList()
        return withPollingPaused {
            val resp = sendCommandWithPendingRetry(ObdConstants.MODE_PENDING_DTC) ?: return emptyList()
            ObdDecoder.dtcList(resp, modeByte = 0x47, protocolNumber = protocolNumber)
        }
    }

    /** 永久故障碼（mode 0A）：清除後仍存在的排放相關故障 */
    fun readPermanentDtc(): List<String> {
        if (demoMode) return emptyList()
        if (!isConnected()) return emptyList()
        return withPollingPaused {
            val resp = sendCommandWithPendingRetry(ObdConstants.MODE_PERMANENT_DTC) ?: return emptyList()
            ObdDecoder.dtcList(resp, modeByte = 0x4A, protocolNumber = protocolNumber)
        }
    }

    /**
     * 送出指令；KWP 串列協定下依 UDS 負回應判別：
     * - 0x78（response pending）：ECU 仍在處理，短暫等待後重送，直到取得完整回應或達重試上限。
     * - 0x11（服務不支援/一般拒絕）：重試無意義，立即回傳（對應 Car Scanner 的取消佇列語義）。
     * - 其他負回應或正常回應：直接回傳。
     */
    private fun sendCommandWithPendingRetry(cmd: String): String? {
        if (!isSlowProtocol) return sendCommand(cmd)
        val service = cmd.take(2).toIntOrNull(16) ?: return sendCommand(cmd)
        var resp = sendCommand(cmd)
        var attempt = 0
        while (resp != null && attempt < ObdConstants.KWP_PENDING_RETRIES) {
            val code = ObdDecoder.negativeResponseCode(resp, service)
            when {
                code == 0x78 -> {
                    attempt++
                    ObdLog.log("KWP pending 0x78, retry $attempt ($cmd)")
                    Thread.sleep(ObdConstants.KWP_PENDING_RETRY_DELAY_MS)
                    resp = sendCommand(cmd)
                }
                code == 0x11 -> {
                    ObdLog.log("NR 0x11（服務不支援/一般拒絕），$cmd 中止重試")
                    return resp
                }
                else -> return resp
            }
        }
        return resp
    }

    /** KWP 負回應是否為 response pending（0x7F <service> 0x78） */

    fun clearDtc(): Boolean {
        if (demoMode) return true
        if (!isConnected()) {
            lastClearErrorMsg = null
            return false
        }
        return withPollingPaused {
            repeat(ObdConstants.CLEAR_DTC_ATTEMPTS) {
                val resp = sendCommand(ObdConstants.MODE_CLEAR_DTC, ObdConstants.CLEAR_DTC_TIMEOUT_MS)
                val reason = clearFailureReason(resp, service = 0x04)
                if (reason == null) {
                    lastClearErrorMsg = null
                    return true
                }
                lastClearErrorMsg = reason
                Thread.sleep(ObdConstants.CLEAR_DTC_RETRY_DELAY_MS)
            }
            val uds = sendCommand(ObdConstants.MODE_CLEAR_DTC_UDS, ObdConstants.CLEAR_DTC_TIMEOUT_MS)
            val udsReason = clearFailureReason(uds, service = 0x14)
            if (udsReason == null) {
                lastClearErrorMsg = null
                true
            } else {
                lastClearErrorMsg = udsReason
                false
            }
        }
    }

    /** 最近一次清碼失敗原因（null = 最近成功或尚未清碼）；供 UI 顯示有意義的錯誤訊息 */
    fun lastClearError(): String? = lastClearErrorMsg

    /**
     * 清碼失敗判定：成功回 null；失敗回中文原因。
     * 失敗種類：無回應、NO DATA / '?' / 空白、UDS 負回應（依 NRC 給出含義）。
     * 部分 ECU 清碼成功回應為 OK、部分為 `44 00`（mode echo）、部分為空行。
     */
    private fun clearFailureReason(resp: String?, service: Int): String? {
        if (resp == null) return "無回應"
        val upper = resp.uppercase()
        if (upper.contains("NO DATA") || upper.contains("?") || upper.isBlank()) return "無資料回應"
        if (upper.contains("7F")) {
            val code = ObdDecoder.negativeResponseCode(resp, service)
            val msg = code?.let { ObdDecoder.negativeResponseMessage(it) }
            return if (msg != null) "負回應：$msg" else "負回應"
        }
        return null
    }

    /**
     * 產生一筆平滑、物理合理的模擬數據：
     * 轉速隨機漫遊（怠速 1100 → 8200）、車速與轉速相關、水溫 2 分鐘內升至 90°C、電壓微幅抖動。
     */
    private fun simulateLiveData(): LiveData {
        if (Math.random() < 0.15) {
            simTargetRpm = 1100 + Math.random() * 7100
        }
        simRpm += (simTargetRpm - simRpm) * 0.1 + (Math.random() - 0.5) * 60
        val rpm = simRpm.coerceIn(1100.0, 8200.0).toInt()

        val speed = (rpm / 55.0).coerceIn(0.0, 120.0).toInt()

        val elapsedMin = (System.currentTimeMillis() - simStartMs) / 60000.0
        val coolant = (40.0 + elapsedMin * 25.0).coerceIn(0.0, 90.0).toInt()

        val intake = (15.0 + Math.random() * 25.0 + (rpm - 1100) / 300.0)
            .coerceIn(0.0, 80.0).toInt()

        val voltage = (13.9 + (Math.random() - 0.5) * 0.6).toFloat()

        val load = (25.0 + (rpm - 1100) / 100.0 * 3.0 + (Math.random() - 0.5) * 6)
            .coerceIn(0.0, 100.0).toInt()
        val maf = (2.5 + load / 100.0 * 18.0 + (Math.random() - 0.5) * 1.2).toFloat()
        val fuelRate = (0.6 + load / 100.0 * 4.5 + (Math.random() - 0.5) * 0.3).toFloat()
        val torqueNm = (10.0 + load / 100.0 * 85.0 + (Math.random() - 0.5) * 4.0).toFloat()
        val fuelTrim = ((Math.random() - 0.5) * 8.0).toFloat()
        val fuelTrimLong = (fuelTrim * 0.6).toFloat()
        val afr = (14.0 + (Math.random() - 0.5) * 1.5).toFloat()
        val map = (30.0 + load / 100.0 * 70.0 + (Math.random() - 0.5) * 4.0)
            .coerceIn(20.0, 105.0).toInt()
        val timingAdvance = (-5.0 + load / 100.0 * 30.0 + (Math.random() - 0.5) * 3.0)
            .coerceIn(-10.0, 40.0).toFloat()
        val throttle = (load + (Math.random() - 0.5) * 4.0).coerceIn(0.0, 100.0).toInt()
        val fuelLevel = (65.0 + (Math.random() - 0.5) * 1.0).coerceIn(0.0, 100.0).toInt()
        val moduleVoltage = (13.7 + (Math.random() - 0.5) * 0.4).toFloat()
        val ambientTemp = (18.0 + (Math.random() - 0.5) * 6.0).toInt()
        val oilTemp = (88.0 + (Math.random() - 0.5) * 8.0).toInt()
        return LiveData(
            rpm = rpm,
            speed = speed,
            coolant = coolant,
            voltage = voltage,
            load = load,
            maf = maf,
            fuelRate = fuelRate,
            torqueNm = torqueNm,
            fuelTrim = fuelTrim,
            fuelTrimLong = fuelTrimLong,
            afr = afr,
            intake = intake,
            map = map,
            timingAdvance = timingAdvance,
            throttle = throttle,
            fuelLevel = fuelLevel,
            moduleVoltage = moduleVoltage,
            ambientTemp = ambientTemp,
            oilTemp = oilTemp,
        )
    }

    // ===== 內部 =====

    private fun setState(state: State) {
        currentState = state
        mainHandler.post { notifyState(state) }
    }

    private fun notifyState(state: State) {
        synchronized(listeners) { listeners.toList() }.forEach { it.onStateChanged(state) }
    }

    private fun notifyLiveData(data: LiveData) {
        latestLiveData = data
        synchronized(listeners) { listeners.toList() }.forEach { it.onLiveData(data) }
    }

    private fun closeQuietly() {
        synchronized(lock) {
            runCatching { transport?.close() }
            transport = null
        }
    }

    private fun Intent.getParcelableExtraCompat(name: String): BluetoothDevice? =
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(name, BluetoothDevice::class.java)
        } else {
            @Suppress("UNCHECKED_CAST")
            getParcelableExtra(name)
        }

    /** 將連線目標序列化存入 prefs（供自動重連） */
    private fun encodeTarget(target: TransportTarget): String = when (target) {
        is TransportTarget.ClassicBt -> "classic|${target.device.address}"
        is TransportTarget.BleBt -> "ble|${target.device.address}"
        is TransportTarget.Wifi -> "wifi|${target.host}:${target.port}"
    }

    private fun remoteDeviceOrNull(address: String): BluetoothDevice? =
        runCatching {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
        }.getOrNull()

    companion object {
        private const val HISTORY_MAX = 300
        private const val KEY_LAST_DEVICE = "last_device_address"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 5_000L
        const val KEY_AUTO_RECONNECT = "auto_reconnect"
        const val PREFS = "obd_prefs"
        const val KEY_ELM_CMDS = "custom_init_cmds"
    }
}

package com.heli.obd.elm

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.io.DataInputStream
import java.io.OutputStream
import java.util.UUID

/**
 * OBD 藍牙連線管理員（ELM327）。
 *
 * 職責：
 * - 掃描 / 列出 ELM327 藍牙裝置（classic SPP）
 * - 建立 RFCOMM socket 連線
 * - ELM327 AT 指令初始化（ATZ、ATE0、ATL0、ATH0、ATSP0）
 * - 指令收發（同步、鎖保護、以 '>' 為回應終止符）
 * - 即時數據輪詢（Coroutine 背景執行）
 * - 故障碼讀取 / 清除
 *
 * 藍牙權限（Android 8–11：BLUETOOTH/ADMIN + 定位；Android 12+：BLUETOOTH_SCAN/CONNECT）
 * 由 Activity 層於連線前請求。
 */
class ObdManager(private val appContext: Context) {

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
        val customValues: Map<Long, Float?> = emptyMap(),
    )

    interface Listener {
        fun onStateChanged(state: State)
        fun onLiveData(data: LiveData)
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var socket: BluetoothSocket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var pollJob: Job? = null
    private val listeners = mutableListOf<Listener>()

    @Volatile
    private var currentState: State = State.Idle

    /** Demo 模擬模式：啟用時不需藍牙硬體，輪詢改由模擬資料驅動 */
    @Volatile
    private var demoMode = false

    @Volatile
    private var customPids: List<PidStore.CustomPid> = emptyList()

    // ===== 分組輪詢（快每輪 / 慢 2 輪 / 自訂 4 輪，跳過輪沿用上次值） =====
    private var pollTick = 0
    private var lastMaf: Float? = null
    private var lastFuelRate: Float? = null
    private var lastTorqueNm: Float? = null
    private var lastFuelTrim: Float? = null
    private var lastAfr: Float? = null
    private var lastCustom: Map<Long, Float?> = emptyMap()

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

    fun isConnected(): Boolean = demoMode || socket?.isConnected == true

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
            if (socket == null) {
                setState(State.Idle)
            }
        }
    }

    // ===== 掃描 =====

    private val adapter: BluetoothAdapter?
        @Suppress("DEPRECATION")
        get() = BluetoothAdapter.getDefaultAdapter()

    /** 掃描 ELM327 裝置：先回傳已配對，再進行 6 秒搜尋合併回傳 */
    @Suppress("DEPRECATION")
    fun discover(callback: (List<BluetoothDevice>) -> Unit) {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            callback(emptyList())
            return
        }
        val results = linkedMapOf<String, BluetoothDevice>()
        bt.bondedDevices?.forEach { if (isElm327(it)) results[it.address] = it }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                    val device = intent.getParcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (isElm327(device)) results[device.address] = device
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        bt.startDiscovery()

        ioScope.launch {
            delay(6000)
            bt.cancelDiscovery()
            runCatching { appContext.unregisterReceiver(receiver) }
            mainHandler.post { callback(results.values.toList()) }
        }
    }

    private fun isElm327(device: BluetoothDevice): Boolean {
        val name = device.name?.trim().orEmpty()
        // 名稱空白（廉價 ELM327 常見）或包含 OBD/ELM 關鍵字都列入
        if (name.isEmpty()) return true
        val upper = name.uppercase()
        return ObdConstants.ELM327_NAME_KEYWORDS.any { upper.contains(it) }
    }

    // ===== 連線 =====

    fun connect(device: BluetoothDevice, callback: (success: Boolean, message: String?) -> Unit) {
        setState(State.Connecting)
        ioScope.launch {
            val (ok, msg) = try {
                val sock = device.createRfcommSocketToServiceRecord(UUID.fromString(ObdConstants.SPP_UUID))
                sock.connect()
                socket = sock
                input = DataInputStream(sock.inputStream)
                output = sock.outputStream
                val initOk = initElm327()
                if (!initOk) {
                    closeQuietly()
                    false to appContext.getString(R.string.obd_init_failed)
                } else {
                    true to null
                }
            } catch (e: Exception) {
                closeQuietly()
                false to (e.message ?: appContext.getString(R.string.obd_connect_error))
            }
            if (ok) {
                setState(State.Ready)
                startPolling()
            } else {
                setState(State.Error(msg ?: appContext.getString(R.string.obd_connect_error)))
            }
            mainHandler.post { callback(ok, msg) }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        closeQuietly()
        setState(State.Idle)
    }

    /** ELM327 初始化指令序列；全部執行成功回傳 true */
    private fun initElm327(): Boolean {
        val bootCmds = listOf(
            ObdConstants.CMD_RESET,
            ObdConstants.CMD_ECHO_OFF,
            ObdConstants.CMD_LINEFEED_OFF,
            ObdConstants.CMD_HEADERS_OFF,
            ObdConstants.CMD_AUTO_PROTOCOL,
        )
        for (cmd in bootCmds) {
            if (sendCommand(cmd) == null) return false
        }
        // ATRV 可順帶確認通訊正常（回應含電壓）
        return sendCommand(ObdConstants.CMD_VOLTAGE) != null
    }

    // ===== 指令收發 =====

    /**
     * 送出 ELM327 指令並等待回應（以 '>' 為終止符）。
     * 回應取最後一行（ATE0/ATL0/ATH0 之後為單行）。失敗回傳 null。
     */
    fun sendCommand(cmd: String): String? = synchronized(lock) {
        val out = output ?: return null
        val inStream = input ?: return null
        try {
            out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            out.flush()

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + ObdConstants.COMMAND_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                while (inStream.available() > 0) {
                    val c = inStream.read()
                    if (c == -1) return@synchronized null
                    if (c.toChar() == '>') {
                        return@synchronized lastLine(sb.toString())
                    }
                    sb.append(c.toChar())
                }
                Thread.sleep(15)
            }
            lastLine(sb.toString()).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun lastLine(raw: String): String =
        raw.trim().lines().lastOrNull { it.isNotBlank() } ?: ""

    // ===== 即時數據 =====

    /** 設定要隨輪詢一起讀取的自訂 PID（車廠專用感測器）。 */
    fun setCustomPids(pids: List<PidStore.CustomPid>) {
        customPids = pids
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = ioScope.launch {
            while (isActive) {
                if (!isConnected()) break
                val data = requestLiveData()
                if (data != null) {
                    mainHandler.post { notifyLiveData(data) }
                }
                delay(ObdConstants.POLL_INTERVAL_MS)
            }
        }
    }

    fun requestLiveData(): LiveData? {
        if (demoMode) return simulateLiveData().also { recordHistory(it) }
        if (!isConnected()) return null
        val tick = ++pollTick
        val slow = tick % 2 == 1
        val custom = tick % 4 == 1
        val rpm = sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_RPM)
            ?.let { ObdDecoder.rpm(it) }
        val speed = sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_SPEED)
            ?.let { ObdDecoder.speed(it) }
        val coolant = sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_COOLANT)
            ?.let { ObdDecoder.coolantTemp(it) }
        val voltage = sendCommand(ObdConstants.CMD_VOLTAGE)
            ?.let { ObdDecoder.voltage(it) }
        val load = sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_LOAD)
            ?.let { ObdDecoder.engineLoad(it) }
        val maf = if (slow) {
            sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_MAF)
                ?.let { ObdDecoder.maf(it) }?.also { lastMaf = it }
        } else lastMaf
        val fuelRate = if (slow) {
            sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_FUEL_RATE)
                ?.let { ObdDecoder.fuelRate(it) }?.also { lastFuelRate = it }
        } else lastFuelRate
        val torqueNm = if (slow) {
            sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_TORQUE)
                ?.let { ObdDecoder.torqueNm(it) }?.also { lastTorqueNm = it }
        } else lastTorqueNm
        val fuelTrim = if (slow) {
            sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_SHORT_FUEL_TRIM)
                ?.let { ObdDecoder.fuelTrim(it) }?.also { lastFuelTrim = it }
        } else lastFuelTrim
        val afr = if (slow) {
            sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_WIDEBAND_AFR)
                ?.let { ObdDecoder.widebandAfr(it) }?.also { lastAfr = it }
        } else lastAfr
        val customValues = if (custom) {
            lastCustom = readCustomPids()
            lastCustom
        } else {
            lastCustom
        }
        return LiveData(rpm, speed, coolant, voltage, load, maf, fuelRate, torqueNm, fuelTrim, afr, customValues)
            .also { recordHistory(it) }
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
        push("voltage", data.voltage)
        push("load", data.load?.toFloat())
        push("maf", data.maf)
        push("fuelRate", data.fuelRate)
        push("torqueNm", data.torqueNm)
        push("fuelTrim", data.fuelTrim)
        push("afr", data.afr)
        data.customValues.forEach { (id, v) -> push("custom:$id", v) }
    }

    // ===== 診斷擴充（凍結框 / I/M 就緒 / VIN） =====

    /** 凍結框：讀取觸發碼 + 水溫/轉速/車速/負載的凍結值（mode 02） */
    fun readFreezeFrame(): FreezeFrame? {
        if (demoMode) {
            return FreezeFrame(
                triggerDtc = "P0300",
                values = mapOf(
                    R.string.pid_name_coolant to 88, R.string.pid_name_rpm to 3100,
                    R.string.pid_name_speed to 12, R.string.pid_name_load to 42,
                ),
            )
        }
        if (!isConnected()) return null
        val trigger = sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_FREEZE_DTC)
            ?.let { ObdDecoder.freezeDtc(it) }
        val values = ObdConstants.FREEZE_FRAME_PIDS.associate { (pid, labelRes) ->
            val resp = sendCommand(ObdConstants.MODE_FREEZE_FRAME + pid)
            labelRes to when (pid) {
                ObdConstants.PID_COOLANT -> resp?.let { ObdDecoder.coolantTemp(it) }
                ObdConstants.PID_RPM -> resp?.let { ObdDecoder.rpm(it) }
                ObdConstants.PID_SPEED -> resp?.let { ObdDecoder.speed(it) }
                ObdConstants.PID_LOAD -> resp?.let { ObdDecoder.engineLoad(it) }
                else -> null
            }
        }
        return FreezeFrame(trigger, values)
    }

    /** I/M 排放就緒狀態（mode 01 PID 01） */
    fun readImReadiness(): ImReadiness? {
        if (demoMode) {
            return ObdDecoder.imReadiness("41 01 01 07 05 07 01")
        }
        if (!isConnected()) return null
        val resp = sendCommand(ObdConstants.MODE_CURRENT_DATA + ObdConstants.PID_STATUS)
        return resp?.let { ObdDecoder.imReadiness(it) }
    }

    /** 車身 VIN（mode 09 PID 02） */
    fun readVin(): String? {
        if (demoMode) return "MOTODIAG-DEMO-VIN-0001"
        if (!isConnected()) return null
        val resp = sendCommand(ObdConstants.MODE_VEHICLE_INFO + "02")
        return resp?.let { ObdDecoder.vin(it) }
    }

    // ===== 故障碼 =====

    fun readDtc(): List<String> {
        if (demoMode) return listOf("P0300", "P0135")
        if (!isConnected()) return emptyList()
        val resp = sendCommand(ObdConstants.MODE_DTC) ?: return emptyList()
        return ObdDecoder.dtcList(resp)
    }

    /** 待處理故障碼（mode 07）：尚未確立的間歇性故障 */
    fun readPendingDtc(): List<String> {
        if (demoMode) return listOf("P0301")
        if (!isConnected()) return emptyList()
        val resp = sendCommand(ObdConstants.MODE_PENDING_DTC) ?: return emptyList()
        return ObdDecoder.dtcList(resp, modeByte = 0x47)
    }

    /** 永久故障碼（mode 0A）：清除後仍存在的排放相關故障 */
    fun readPermanentDtc(): List<String> {
        if (demoMode) return emptyList()
        if (!isConnected()) return emptyList()
        val resp = sendCommand(ObdConstants.MODE_PERMANENT_DTC) ?: return emptyList()
        return ObdDecoder.dtcList(resp, modeByte = 0x4A)
    }

    fun clearDtc(): Boolean {
        if (demoMode) return true
        if (!isConnected()) return false
        val resp = sendCommand(ObdConstants.MODE_CLEAR_DTC) ?: return false
        return resp.uppercase().contains("OK")
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

        val voltage = (13.9 + (Math.random() - 0.5) * 0.6).toFloat()

        val load = (25.0 + (rpm - 1100) / 100.0 * 3.0 + (Math.random() - 0.5) * 6)
            .coerceIn(0.0, 100.0).toInt()
        val maf = (2.5 + load / 100.0 * 18.0 + (Math.random() - 0.5) * 1.2).toFloat()
        val fuelRate = (0.6 + load / 100.0 * 4.5 + (Math.random() - 0.5) * 0.3).toFloat()
        val torqueNm = (10.0 + load / 100.0 * 85.0 + (Math.random() - 0.5) * 4.0).toFloat()
        val fuelTrim = ((Math.random() - 0.5) * 8.0).toFloat()
        val afr = (14.0 + (Math.random() - 0.5) * 1.5).toFloat()
        return LiveData(rpm, speed, coolant, voltage, load, maf, fuelRate, torqueNm, fuelTrim, afr)
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
        synchronized(listeners) { listeners.toList() }.forEach { it.onLiveData(data) }
    }

    private fun closeQuietly() {
        synchronized(lock) {
            runCatching { output?.flush() }
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
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

    companion object {
        private const val HISTORY_MAX = 300
    }
}

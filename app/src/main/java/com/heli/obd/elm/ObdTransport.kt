/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.DataInputStream
import java.io.OutputStream
import java.util.UUID

/**
 * OBD 連線目標。
 *
 * 同一抽象涵蓋三種實體連線方式（由 [ObdTransport.open] 分派）：
 * - [ClassicBt]：藍牙 RFCOMM（SPP），傳統 ELM327 藍牙晶片。
 * - [BleBt]：藍牙低功耗（BLE），服務 UUID `FFF0`（寫入 `FFF1`、通知 `FFF2`），
 *   常見於 BLE-only 的 ELM327 晶片（如 Veepeak、ELM327 v2.1 BLE）。
 * - [Wifi]：WiFi ELM327（TCP socket，常見 `192.168.0.10:35000`）。
 */
sealed interface TransportTarget {

    /** 用於 log / UI 顯示的目標描述 */
    val displayName: String

    @SuppressLint("MissingPermission") // 讀取 device.name 需 BLUETOOTH_CONNECT；權限由 UI 層連線前統一申請
    data class ClassicBt(val device: BluetoothDevice) : TransportTarget {
        override val displayName: String get() = device.name ?: device.address
    }

    @SuppressLint("MissingPermission") // 同上
    data class BleBt(val device: BluetoothDevice) : TransportTarget {
        override val displayName: String get() = device.name ?: device.address
    }

    data class Wifi(val host: String, val port: Int) : TransportTarget {
        override val displayName: String get() = "$host:$port"
    }
}

/**
 * OBD 實體通訊層抽象。
 *
 * 將「連線 socket 讀寫」與「ELM327 指令邏輯」分離：
 * - 真實連線使用 [BluetoothTransport]（RFCOMM SPP）、[BleTransport]（BLE GATT）、
 *   [WifiTransport]（TCP socket）。
 * - 單元測試可注入 fake 實作（回放預錄回應），不需要真機硬體。
 */
interface ObdTransport {

    /** 建立連線（依 [target] 型別分派實作）；成功回傳 true */
    fun open(target: TransportTarget): Boolean

    val isOpen: Boolean

    /** 寫入指令位元組（呼叫端需自行追加 \r 與 flush 語意） */
    fun write(bytes: ByteArray)

    /** 讀取單一位元組；-1 代表 EOF（對端關閉連線） */
    fun read(): Int

    /** 目前可讀位元組數 */
    fun available(): Int

    fun close()
}

/**
 * 藍牙 RFCOMM（SPP）實作：封裝 BluetoothSocket 與串流。
 *
 * 以多層 fallback 建立 socket（提高廉價 ELM327 相容性）：
 * 已配對 → secure SPP → channel 1 reflection → insecure SPP；
 * 未配對 → insecure SPP → channel 1 reflection → secure SPP。
 */
class BluetoothTransport : ObdTransport {

    private var socket: BluetoothSocket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    override val isOpen: Boolean
        get() = socket?.isConnected == true

    override fun open(target: TransportTarget): Boolean {
        val device = (target as? TransportTarget.ClassicBt)?.device ?: return false
        val sock = openSocket(device) ?: return false
        socket = sock
        input = DataInputStream(sock.inputStream)
        output = sock.outputStream
        return true
    }

    override fun write(bytes: ByteArray) {
        output?.write(bytes)
        output?.flush()
    }

    override fun read(): Int = input?.read() ?: -1

    override fun available(): Int = input?.available() ?: 0

    override fun close() {
        runCatching { output?.flush() }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("MissingPermission") // 權限由 UI 層於連線前統一申請
    private fun openSocket(device: BluetoothDevice): BluetoothSocket? {
        val spp = UUID.fromString(ObdConstants.SPP_UUID)
        val bonded = device.bondState == BluetoothDevice.BOND_BONDED
        // true = secure（需配對）、false = insecure（免配對）、null = reflection channel 1
        val modes = if (bonded) listOf(true, null, false) else listOf(false, null, true)
        for (mode in modes) {
            val sock = try {
                when (mode) {
                    true -> device.createRfcommSocketToServiceRecord(spp)
                    false -> device.createInsecureRfcommSocketToServiceRecord(spp)
                    else -> DeviceReflection.channel1(device) ?: continue
                }
            } catch (_: Exception) {
                continue
            }
            try {
                sock.connect()
                return sock
            } catch (_: Exception) {
                runCatching { sock.close() }
            }
        }
        return null
    }
}

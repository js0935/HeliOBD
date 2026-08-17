/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE ELM327 實作：封裝 BluetoothGatt。
 *
 * BLE 是「通知式」而非串流式通訊，因此與 [ObdTransport] 的輪詢介面對接如下：
 * - 寫入：以 `writeCharacteristic` 送出（主執行緒執行，等待 onCharacteristicWrite 完成，確保序列化）。
 * - 接收：裝置透過 notify 推送資料，`onCharacteristicChanged` 將位元組累積進內部佇列；
 *   [read] 從佇列取、[available] 回傳佇列剩餘，與 ObdManager 的非阻塞輪詢讀取模式相容。
 * - 斷線：`onConnectionStateChange(DISCONNECTED)` 於佇列放入 -1 sentinel，[read] 回 -1，
 *   ObdManager 視為 EOF 進入斷線流程。
 *
 * 服務模型（OBD2 通用 ELM327 BLE，如 Veepeak / ELM327 v2.1）：
 * - Service `0000FFF0`
 * - 寫入 Characteristic `0000FFF1`（慣例）
 * - 通知 Characteristic `0000FFF2`（慣例）
 * 部分晶片反轉 FFF1/FFF2 角色，連線時依 attribute properties 自動判定。
 */
@SuppressLint("MissingPermission") // 權限由 UI 層於連線前統一申請
class BleTransport : ObdTransport {

    private companion object {
        val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_MS = 2_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 無鎖環形緩衝區：BLE callback 為單一寫入者，IO 執行緒為單一讀取者 */
    private val ringBuf = ByteArray(4096)
    private val writePos = AtomicInteger(0)
    private val readPos = AtomicInteger(0)

    private val isConnected = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var gattState = GattState.IDLE

    private enum class GattState { IDLE, CONNECTING, DISCOVERING, READY }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gattState = GattState.DISCOVERING
                    connectedLatch.countDown()
                    mainHandler.post { gatt.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected.set(false)
                    gattState = GattState.IDLE
                    closed.set(true)
                    serviceLatch.countDown()
                    connectedLatch.countDown()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                serviceLatch.countDown()
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                serviceLatch.countDown()
                return
            }
            val f1 = service.getCharacteristic(WRITE_UUID)
            val f2 = service.getCharacteristic(NOTIFY_UUID)
            val w1 = f1?.let { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 } == true
            val n1 = f1?.let { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 } == true
            val w2 = f2?.let { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 } == true
            val n2 = f2?.let { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 } == true

            // 慣例：FFF1 寫入 + FFF2 通知；若晶片反轉（FFF1 可通知、FFF2 可寫）則交換。
            var write = if (w1 && n2) f1 else null
            var notify = if (w1 && n2) f2 else null
            if (write == null && n1 && w2) {
                write = f2
                notify = f1
            }
            // 兩者屬性相同或都支援：依慣例 FFF1 寫入、FFF2 通知
            if (write == null && f1 != null) write = f1
            if (notify == null && f2 != null) notify = f2

            if (write == null || notify == null) {
                serviceLatch.countDown()
                return
            }
            this@BleTransport.writeChar = write
            gatt.setCharacteristicNotification(notify, true)
            notify.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
            serviceLatch.countDown()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (isConnected.get() && value.isNotEmpty()) {
                val wp = writePos.get()
                val rp = readPos.get()
                val free = ringBuf.size - (wp - rp)
                val toWrite = minOf(value.size, free)
                if (toWrite > 0) {
                    for (i in 0 until toWrite) {
                        ringBuf[(wp + i) % ringBuf.size] = value[i]
                    }
                    writePos.addAndGet(toWrite)
                }
            }
        }

        // 覆寫舊版（3-arg）回呼：所有系統版本皆會呼叫此版本，新舊 API 皆可靠
        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeLatch?.countDown()
        }
    }

    private var connectedLatch = CountDownLatch(1)
    private var serviceLatch = CountDownLatch(1)
    private var writeLatch: CountDownLatch? = null

    override val isOpen: Boolean
        get() = isConnected.get()

    override fun open(target: TransportTarget): Boolean {
        val device = (target as? TransportTarget.BleBt)?.device ?: return false
        closed.set(false)
        writePos.set(0)
        readPos.set(0)
        isConnected.set(false)
        gattState = GattState.CONNECTING
        connectedLatch = CountDownLatch(1)
        serviceLatch = CountDownLatch(1)

        val g = device.connectGatt(null, false, callback)
        if (g == null) return false
        gatt = g

        if (!await(connectedLatch, CONNECT_TIMEOUT_MS)) {
            close()
            return false
        }
        if (!await(serviceLatch, CONNECT_TIMEOUT_MS)) {
            close()
            return false
        }
        if (writeChar == null) {
            close()
            return false
        }
        isConnected.set(true)
        return true
    }

    override fun write(bytes: ByteArray) {
        val g = gatt ?: return
        val char = writeChar ?: return
        val latch = CountDownLatch(1)
        writeLatch = latch
        mainHandler.post {
            val success = writeCharacteristicCompat(g, char, bytes)
            if (!success) latch.countDown()
        }
        val ok = await(latch, WRITE_TIMEOUT_MS)
        writeLatch = null
        if (!ok) throw java.io.IOException("BLE write timeout")
    }

    /** 依系統版本選用新的 writeCharacteristic(characteristic, value, writeType) 或舊版 API */
    @android.annotation.SuppressLint("WrongConstant") // SDK stub 誤將 writeType 標註為 status code；實際為 WRITE_TYPE_DEFAULT
    private fun writeCharacteristicCompat(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return g.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        }
        @Suppress("DEPRECATION")
        char.setValue(bytes)
        @Suppress("DEPRECATION")
        return g.writeCharacteristic(char)
    }

    override fun read(): Int {
        while (true) {
            val rp = readPos.get()
            val wp = writePos.get()
            if (rp < wp) {
                val b = ringBuf[rp % ringBuf.size].toInt() and 0xFF
                readPos.incrementAndGet()
                return b
            }
            if (closed.get()) return -1
            // 無資料但未關閉：短暫等待 BLE callback 寫入
            Thread.sleep(1)
        }
    }

    override fun available(): Int = (writePos.get() - readPos.get()).coerceAtLeast(0)

    override fun close() {
        if (closed.getAndSet(true)) return
        isConnected.set(false)
        gattState = GattState.IDLE
        val g = gatt
        gatt = null
        writeChar = null
        if (g != null) {
            mainHandler.post {
                runCatching { g.disconnect() }
                runCatching { g.close() }
            }
        }
    }

    private inline fun await(latch: CountDownLatch, timeoutMs: Long): Boolean {
        var remaining = timeoutMs
        val step = 100L
        while (remaining > 0) {
            if (latch.await(step, TimeUnit.MILLISECONDS)) return true
            if (closed.get()) return false
            remaining -= step
        }
        return false
    }
}

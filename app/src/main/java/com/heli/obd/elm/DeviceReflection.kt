/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket

/**
 * 透過反射呼叫 Android 隱藏藍牙 API（Android 各版本未公開的 RFCOMM 與配對方法）。
 * 用於提高廉價 ELM327 裝置的相容性；所有呼叫均以安全方式執行，失敗回傳 null/忽略。
 */
object DeviceReflection {

    /** 呼叫隱藏方法 BluetoothDevice.createRfcommSocket(int channel)，固定使用 channel 1 */
    fun channel1(device: BluetoothDevice): BluetoothSocket? = runCatching {
        val method = BluetoothDevice::class.java.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        method.invoke(device, 1) as BluetoothSocket
    }.getOrNull()

    /** 呼叫隱藏方法 setPin(byte[])，回應配對 PIN 輸入請求 */
    fun setPin(device: BluetoothDevice, pin: String) {
        val method = BluetoothDevice::class.java.getMethod("setPin", ByteArray::class.java)
        method.invoke(device, pin.toByteArray(Charsets.US_ASCII))
    }

    /** 呼叫隱藏方法 setPairingConfirmation(boolean)，同意數字比對配對 */
    fun confirmPairing(device: BluetoothDevice) {
        val method = BluetoothDevice::class.java.getMethod("setPairingConfirmation", Boolean::class.javaPrimitiveType)
        method.invoke(device, true)
    }
}

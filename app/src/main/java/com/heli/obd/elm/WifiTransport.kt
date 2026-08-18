/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * WiFi ELM327 實作：封裝 TCP socket。
 *
 * 市售 WiFi ELM327 將序列埠轉成 TCP 伺服器（常見 IP `192.168.0.10`、port `35000`），
 * 連線後即為 ELM327 的 byte stream，讀寫行為與藍牙 RFCOMM 完全一致。
 */
class WifiTransport : ObdTransport {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    override val isOpen: Boolean
        get() = socket?.isConnected == true && socket?.isClosed != true

    override fun open(target: TransportTarget): Boolean {
        val wifi = (target as? TransportTarget.Wifi) ?: return false
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(wifi.host, wifi.port), CONNECT_TIMEOUT_MS)
            s.soTimeout = READ_TIMEOUT_MS
            socket = s
            input = DataInputStream(s.getInputStream())
            output = s.getOutputStream()
            true
        } catch (_: Exception) {
            close()
            false
        }
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

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 3_000
    }
}

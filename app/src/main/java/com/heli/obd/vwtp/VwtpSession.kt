/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.vwtp

/**
 * VW TP 2.0 會話層（ELM327）——移植自 CarScanner 反編譯的 VWTPECU.cs / VWTPManager.cs
 * 中確定無歧義的純邏輯：
 *
 * - [VwtpTiming]：CAN 時序 byte 解碼（ConvertCanTimingToMsec）
 * - [VwtpFrameClassifier]：幀分類（IsDataFrameVWTP / IsEndOfMessage / IsAckRequiredForDataFrame）
 * - [VwtpRequestBuilder]：SendRequest 的單/多幀 ELM327 指令組裝
 * - [VwtpResponseParser]：ELM327 回應文字 → CAN 幀解析（ELMResponseToCANFrames）
 * - [VwtpAddressMap]：ECU 邏輯位址 ↔ CAN 位址映射 + CRA 通道分配（VWTPManager）
 * - [VwtpEcuStateResolver]：ECU 狀態機（VWTPECU.CurrentState）
 * - [VwtpSession]：連線/傳送流程（ChannelSetup → OpenConnection → SendRequest），
 *   [send]/[read] 由呼叫端注入，未來接藍牙層時實作。
 *
 * 未納入：依賴 ELMStatus 快取與 SharedSettings 的 ATSH/ATST 前置同步步驟（留待藍牙層整合）。
 */

/** CAN 時序 byte 解碼（VWTPECU.ConvertCanTimingToMsec）：高 2 位指數、低 6 位係數。 */
object VwtpTiming {

    /**
     * 時序 byte → 毫秒。
     * 指數 0 → 係數/1000、1 → 係數、2 → 係數×10、3 → 係數×100。
     */
    fun decodeCanTiming(b: Int): Int {
        val exp = (b shr 6) and 3
        val mantissa = b and 0x3F
        return when (exp) {
            0 -> mantissa / 1000
            1 -> mantissa
            2 -> mantissa * 10
            else -> mantissa * 100
        }
    }
}

/** VW TP 2.0 資料幀分類（VWTPECU.IsDataFrameVWTP / IsEndOfMessage / IsAckRequiredForDataFrame）。 */
object VwtpFrameClassifier {

    /** 高半位元組為 0/1/2/3（0x00/0x10/0x20/0x30）→ 資料幀。 */
    fun isDataFrame(firstByte: Int): Boolean {
        val high = firstByte and 0xF0
        return high == 0 || high == 0x10 || high == 0x20 || high == 0x30
    }

    /** bit 0x10 置位 → 訊息結束幀。 */
    fun isEndOfMessage(firstByte: Int): Boolean = (firstByte and 0x10) == 0x10

    /** bit 0x20 未置位 → 需要回 ACK。 */
    fun isAckRequired(firstByte: Int): Boolean = (firstByte and 0x20) != 0x20
}

/** SendRequest 指令幀組裝（VWTPECU.SendRequest 的字串組裝部分）。 */
object VwtpRequestBuilder {

    /** packet counter > 15 時歸零（VWTPECU.PacketCounter setter）。 */
    fun nextPacketCounter(counter: Int): Int = if (counter + 1 > 15) 0 else counter + 1

    /**
     * 組裝 ELM327 指令幀清單：
     * - 指令 ≤ 5 bytes：`1 + counter + 長度(4 hex) + 指令`
     * - 指令 > 5 bytes：首幀 `2 + counter + 長度(4 hex) + 前 5 bytes`，
     *   其後每 7 bytes 一幀 `2/1 + counter + 區塊`（末幀以 1 標記）。
     */
    fun buildFrames(cmdHex: String, startCounter: Int): List<String> {
        val bytes = hexToBytes(cmdHex)
        val out = mutableListOf<String>()
        val lenHex = bytes.size.toString(16).padStart(4, '0').uppercase()
        if (bytes.size <= 5) {
            out += "1" + startCounter.toHex1() + lenHex + cmdHex
            return out
        }
        var counter = startCounter
        out += "2" + counter.toHex1() + lenHex + cmdHex.substring(0, 5 * 2)
        counter = nextPacketCounter(counter)
        var pos = 5
        while (pos < bytes.size) {
            val end = minOf(pos + 7, bytes.size)
            val prefix = if (end >= bytes.size) "1" else "2"
            out += prefix + counter.toHex1() + bytes.copyOfRange(pos, end).toHex()
            counter = nextPacketCounter(counter)
            pos = end
        }
        return out
    }

    private fun Int.toHex1(): String = (this and 0xF).toString(16).uppercase()
}

/** ELM327 回應中的單一 CAN 幀。 */
data class VwtpCanFrame(val canIdHex: String, val data: List<Int>)

/** ELM327 回應文字 → CAN 幀解析（VWTPECU.ELMResponseToCANFrames）。 */
object VwtpResponseParser {

    /**
     * 每行取前 3 個 hex 為 CAN ID、其餘為資料；資料長度為奇數的行跳過。
     * 空白回應或含 "NO DATA" 回傳空清單。
     */
    fun parse(text: String): List<VwtpCanFrame> {
        if (text.isBlank()) return emptyList()
        if (text.contains("NO DATA")) return emptyList()
        val out = mutableListOf<VwtpCanFrame>()
        for (line in text.lines()) {
            val clean = line.filter { it in "0123456789abcdefABCDEF" }
            if (clean.length < 3) continue
            val header = clean.substring(0, 3)
            val payload = clean.substring(3)
            if (payload.length % 2 != 0) continue
            out += VwtpCanFrame(header, payload.chunked(2).map { it.toInt(16) })
        }
        return out
    }
}

/** ECU 邏輯位址 ↔ CAN 位址映射與 CRA 通道分配（VWTPManager）。 */
object VwtpAddressMap {

    /** 邏輯位址（如 "04"）→ CAN 位址（如 "13"）。未知回傳 null。 */
    fun unitToCan(unit: String): String? {
        if (unit.isBlank()) return null
        return UNIT_TO_CAN[unit.uppercase()]
    }

    /** CAN 位址 → 邏輯位址。未知回傳 null。 */
    fun canToUnit(can: String): String? {
        if (can.isBlank()) return null
        return CAN_TO_UNIT[can.uppercase()]
    }

    /**
     * 掃描 768..1022（0x300..0x3FE）找第一個未被使用的 CRA 回應 header；
     * 全滿回傳 1023（VWTPManager.GetFreeCRAChannel）。
     */
    fun findFreeChannel(usedHeaders: Set<String>): Int {
        for (n in 768 until 1023) {
            if (n.toString(16).uppercase().padStart(3, '0') !in usedHeaders) return n
        }
        return 1023
    }

    private val UNIT_TO_CAN: Map<String, String> = mapOf(
        "01" to "01", "02" to "02", "03" to "03",
        "04" to "13", "05" to "31", "06" to "35", "07" to "3F",
        "08" to "3A", "09" to "3B", "0A" to "3E", "0B" to "38", "0C" to "39",
        "0D" to "31", "0E" to "35", "0F" to "3F",
        "10" to "3A", "11" to "3B", "12" to "3E", "13" to "38", "14" to "39",
        "15" to "30", "16" to "33", "17" to "32", "18" to "37", "19" to "36",
        "1A" to "34", "1B" to "3C", "1C" to "3D",
        "1D" to "10", "1E" to "11", "1F" to "12",
        "20" to "41", "21" to "18", "22" to "19", "23" to "54", "24" to "49",
        "25" to "13", "26" to "31", "27" to "35", "28" to "3F", "29" to "3A",
    )

    private val CAN_TO_UNIT: Map<String, String> = mapOf(
        "01" to "01", "02" to "02", "03" to "03",
        "13" to "04", "31" to "05", "35" to "06", "3F" to "07",
        "3A" to "08", "3B" to "09", "3E" to "0A", "38" to "0B", "39" to "0C",
        "30" to "15", "33" to "16", "32" to "17", "37" to "18", "36" to "19",
        "34" to "1A", "3C" to "1B", "3D" to "1C",
        "10" to "1D", "11" to "1E", "12" to "1F",
        "41" to "20", "18" to "21", "19" to "22", "54" to "23", "49" to "24",
    )
}

/** ECU 狀態機（VWTPECU.CurrentState getter 的純函數版）。 */
object VwtpEcuStateResolver {

    enum class EcuState {
        Disconnected,
        WaitingForIncomingConnection,
        ConnectedWaitingForData,
        ConnectedWaitingForAck,
        ConnectedWaitingForConnectionTest,
    }

    /**
     * 依「距上次狀態變更的經過時間」解析目前狀態：
     * - [EcuState.WaitingForIncomingConnection]：elapsed < T1×2 保持，否則斷線
     * - [EcuState.ConnectedWaitingForData] / [EcuState.ConnectedWaitingForAck]：
     *   elapsed ≤ activeTimeout 保持；≤ activeTimeout+passiveTimeout 進入連線測試；否則斷線
     */
    fun resolve(
        state: EcuState,
        elapsedMs: Long,
        t1Ms: Int = 200,
        activeTimeoutMs: Long = 600,
        passiveTimeoutMs: Long = 1050,
    ): EcuState {
        return when (state) {
            EcuState.WaitingForIncomingConnection ->
                if (elapsedMs < t1Ms * 2L) state else EcuState.Disconnected
            EcuState.ConnectedWaitingForData, EcuState.ConnectedWaitingForAck -> when {
                elapsedMs <= activeTimeoutMs -> state
                elapsedMs <= activeTimeoutMs + passiveTimeoutMs ->
                    EcuState.ConnectedWaitingForConnectionTest
                else -> EcuState.Disconnected
            }
            else -> EcuState.Disconnected
        }
    }
}

/**
 * VW TP 2.0 會話：管理多個 ECU 的連線狀態並執行
 * ChannelSetup → OpenConnection → SendRequest 流程。
 *
 * [send] 送出 ELM327 指令、[read] 讀取回應（每送一指令讀一次，與
 * VWTPECU 的 SendString/ReadData 對應）；[clock] 注入時間源以便測試。
 */
class VwtpSession(
    private val send: (String) -> Unit,
    private val read: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class VwCommand(val ecu: String, val param: String)

    companion object {
        const val PREFIX = "VWTP"
        const val NO_DATA = "NO DATA"
        const val CMD_AT_SP = "ATSP6"
        const val CMD_AT_SH = "ATSH200"
        const val CMD_AT_ST = "ATST0A"
        const val CMD_OPEN_CONNECTION = "A00F8AFF32FF"
        const val CMD_CONNECTION_CONFIRM = "A3"
        const val CONFIRMATION_INTERVAL_MS = 600L
        const val PASSIVE_TIMEOUT_MS = 1050L
    }

    private class EcuSession(val unit: String) {
        var state: VwtpEcuStateResolver.EcuState = VwtpEcuStateResolver.EcuState.Disconnected
        var packetCounter = 0
        var responseHeader = ""
        var requestHeader = ""
        var connectionParams: VwtpCanFrame? = null
        var blockSize = 15
        var t1Ms = 200
        var t3Ms = 10
        var lastTouchMs = 0L
        var lastActiveTestMs = 0L
    }

    private val ecus = mutableMapOf<String, EcuSession>()

    /** 解析 "VWTP:02:2106" → (ecu="02", param="2106")。格式不符回傳 null。 */
    fun parseCommand(cmd: String): VwCommand? {
        val parts = cmd.split(':')
        if (parts.size != 3 || parts[0] != PREFIX) return null
        return VwCommand(parts[1], parts[2])
    }

    /** 找第一個未使用的 CRA 通道（委託 [VwtpAddressMap.findFreeChannel]）。 */
    fun getFreeCraChannel(): Int =
        VwtpAddressMap.findFreeChannel(ecus.values.mapNotNull { it.responseHeader }.toSet())

    /** 邏輯位址 → CAN 位址。 */
    fun unitToCanAddress(unit: String): String? = VwtpAddressMap.unitToCan(unit)

    /** CAN 位址 → 邏輯位址。 */
    fun canAddressToUnit(can: String): String? = VwtpAddressMap.canToUnit(can)

    /** 目前 ECU 狀態（未註冊回傳 Disconnected）。 */
    fun currentState(unit: String, nowMs: Long = clock()): VwtpEcuStateResolver.EcuState {
        val ecu = ecus[unit] ?: return VwtpEcuStateResolver.EcuState.Disconnected
        return resolveState(ecu, nowMs)
    }

    private fun resolveState(
        ecu: EcuSession,
        nowMs: Long,
    ): VwtpEcuStateResolver.EcuState = VwtpEcuStateResolver.resolve(
        ecu.state,
        nowMs - ecu.lastTouchMs,
        ecu.t1Ms,
        CONFIRMATION_INTERVAL_MS,
        PASSIVE_TIMEOUT_MS,
    )

    /**
     * 執行指令（VWTPECU.SendCommand 流程）：
     * 斷線 → ChannelSetup → WaitingForIncomingConnection → OpenConnection →
     * ConnectedWaitingForData → 連線確認（逾時才送 A3）→ SendRequest → hex 回應。
     */
    fun sendCommand(cmd: String): String {
        val nowMs = clock()
        val parsed = parseCommand(cmd) ?: return NO_DATA
        val ecu = ecus.getOrPut(parsed.ecu) { EcuSession(parsed.ecu) }

        if (resolveState(ecu, nowMs) == VwtpEcuStateResolver.EcuState.Disconnected) {
            if (!channelSetup(ecu, nowMs)) return NO_DATA
            ecu.state = VwtpEcuStateResolver.EcuState.WaitingForIncomingConnection
            ecu.lastTouchMs = nowMs
        }
        if (resolveState(ecu, nowMs) == VwtpEcuStateResolver.EcuState.WaitingForIncomingConnection) {
            if (!openConnection(ecu, nowMs)) {
                ecu.state = VwtpEcuStateResolver.EcuState.Disconnected
                return NO_DATA
            }
        }
        if (resolveState(ecu, nowMs) == VwtpEcuStateResolver.EcuState.ConnectedWaitingForConnectionTest) {
            if (sendConnectionTest(ecu)) {
                ecu.state = VwtpEcuStateResolver.EcuState.ConnectedWaitingForData
            }
        }
        if (resolveState(ecu, nowMs) == VwtpEcuStateResolver.EcuState.ConnectedWaitingForData) {
            if (!sendConnectionConfirmationIfNeeded(ecu, nowMs)) return NO_DATA
            val data = sendRequest(ecu, parsed.param)
            if (data.isEmpty()) return NO_DATA
            return data.toHex()
        }
        return NO_DATA
    }

    /** ChannelSetup：協定/header/timeout 設定 → 送通道請求幀 → 解析 0xD0 回應取得 header。 */
    private fun channelSetup(ecu: EcuSession, nowMs: Long): Boolean {
        send(CMD_AT_SP); read()
        send(CMD_AT_SH); read()
        send(CMD_AT_ST); read()

        val freeHex = getFreeCraChannel().toString(16).uppercase().padStart(3, '0')
        // Unit + "C00010" + 通道低 2 hex + "0" + 通道高 1 hex + "011"
        val data = ecu.unit + "C00010" + freeHex.substring(1) + "0" + freeHex.substring(0, 1) + "011"
        send(data)
        val frames = VwtpResponseParser.parse(read())

        val d0 = frames.firstOrNull { it.data.size == 7 && it.data[1] == 0xD0 }
        if (d0 != null) {
            ecu.responseHeader = (d0.data[3] * 256 + d0.data[2]).toString(16).uppercase().padStart(3, '0')
            ecu.requestHeader = (d0.data[5] * 256 + d0.data[4]).toString(16).uppercase().padStart(3, '0')
            return true
        }
        // 既有連線重啟分支：有參數幀且收到 0xD8 → 重送參數幀直接連上
        val params = ecu.connectionParams
        if (params != null && frames.any { it.data.size > 1 && it.data[1] == 0xD8 }) {
            send(params.toDataHex())
            ecu.lastActiveTestMs = nowMs
            ecu.state = VwtpEcuStateResolver.EcuState.ConnectedWaitingForData
            return true
        }
        return false
    }

    /** OpenConnection：送 A00F8AFF32FF，解析 0xA1 回應取得連線參數。 */
    private fun openConnection(ecu: EcuSession, nowMs: Long): Boolean {
        send(CMD_OPEN_CONNECTION)
        val frames = VwtpResponseParser.parse(read())
        val a1 = frames.firstOrNull { it.data.isNotEmpty() && it.data[0] == 0xA1 } ?: return false
        ecu.connectionParams = a1
        ecu.blockSize = a1.data.getOrElse(1) { 15 }
        ecu.t1Ms = VwtpTiming.decodeCanTiming(a1.data.getOrElse(2) { 0 })
        ecu.t3Ms = VwtpTiming.decodeCanTiming(a1.data.getOrElse(4) { 0 })
        ecu.state = VwtpEcuStateResolver.EcuState.ConnectedWaitingForData
        ecu.lastTouchMs = nowMs
        return true
    }

    /** 連線測試：重送連線參數幀並更新活動測試時間。 */
    private fun sendConnectionTest(ecu: EcuSession): Boolean {
        val params = ecu.connectionParams ?: return false
        send(params.toDataHex())
        ecu.lastActiveTestMs = clock()
        read()
        return true
    }

    /** 距上次連線確認超過 600ms 才送 A3 確認；失敗斷線。 */
    private fun sendConnectionConfirmationIfNeeded(ecu: EcuSession, nowMs: Long): Boolean {
        if (ecu.state != VwtpEcuStateResolver.EcuState.ConnectedWaitingForData) return false
        if (nowMs - ecu.lastActiveTestMs <= CONFIRMATION_INTERVAL_MS) return true
        return sendConnectionConfirmation(ecu, nowMs)
    }

    private fun sendConnectionConfirmation(ecu: EcuSession, nowMs: Long): Boolean {
        send(CMD_CONNECTION_CONFIRM)
        val frames = VwtpResponseParser.parse(read())
        if (frames.any { it.data.isNotEmpty() && it.data[0] == 0xA1 }) {
            ecu.lastActiveTestMs = nowMs
            return true
        }
        ecu.state = VwtpEcuStateResolver.EcuState.Disconnected
        return false
    }

    /** SendRequest：組裝幀 → 送 → 回應收集 → 需要時回 ACK → 組裝資料回應。 */
    private fun sendRequest(ecu: EcuSession, cmdHex: String): List<Int> {
        val requestFrames = VwtpRequestBuilder.buildFrames(cmdHex, ecu.packetCounter)
        for (frame in requestFrames) {
            ecu.packetCounter = VwtpRequestBuilder.nextPacketCounter(ecu.packetCounter)
            send(frame)
            if (frame != requestFrames.last()) read()
        }

        val resultFrames = mutableListOf<VwtpCanFrame>()
        while (true) {
            val frames = VwtpResponseParser.parse(read())
                .filter { it.canIdHex == ecu.responseHeader }
            frames.firstOrNull()?.let { sendConnectionTestIfNeeded(ecu, it) }
            if (frames.isEmpty()) break
            resultFrames += frames

            val lastFrame = resultFrames.last()
            val firstByte = lastFrame.data.firstOrNull() ?: return emptyList()
            if (!VwtpFrameClassifier.isAckRequired(firstByte)) break

            val seq = (firstByte and 0xF) + 1
            val ack = 176 + if (seq > 15) 0 else seq
            send(ack.toString(16).uppercase().padStart(2, '0'))

            if (!VwtpFrameClassifier.isEndOfMessage(firstByte)) continue
            VwtpResponseParser.parse(read()).firstOrNull()?.let { sendConnectionTestIfNeeded(ecu, it) }
            break
        }

        val result = mutableListOf<Int>()
        for (frame in resultFrames) {
            if (frame.data.size > 1 && VwtpFrameClassifier.isDataFrame(frame.data[0])) {
                result += frame.data.drop(1)
            }
        }
        return result
    }

    /** 收到 0xA3 幀 → 重送連線參數幀（VWTPECU.SendConnectionTestIfNeeded）。 */
    private fun sendConnectionTestIfNeeded(ecu: EcuSession, frame: VwtpCanFrame) {
        if (ecu.connectionParams == null) return
        if (frame.data.size == 1 && frame.data[0] == 0xA3) {
            sendConnectionTest(ecu)
        }
    }
}

// ---- hex 工具（內部共用） ----

/** 完整幀 hex（CAN ID + 資料），用於重送連線參數幀（VWTPECU.SendConnectionTest）。 */
private fun VwtpCanFrame.toDataHex(): String =
    canIdHex + data.joinToString("") { it.toString(16).padStart(2, '0').uppercase() }

private fun hexToBytes(hex: String): ByteArray {
    val clean = hex.filter { it in "0123456789abcdefABCDEF" }
    if (clean.length % 2 != 0) return ByteArray(0)
    return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

private fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

private fun List<Int>.toHex(): String =
    joinToString("") { it.toString(16).padStart(2, '0').uppercase() }

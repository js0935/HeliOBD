/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

/**
 * 追蹤 ELM327 目前的 AT 指令狀態，供指令去重（狀態已達成時跳過重送，省往返時間）。
 * 對應 Car Scanner ELMState.cs 的精簡版：每次送出 AT 指令後以 [update] 記錄現態，
 * 之後可依 [shouldSkip] 判斷某個設定指令是否已被目前狀態涵蓋而可跳過。
 */
class ElmState {

    /** 目前協定編號（0 = 自動） */
    var protocol: Int = 0

    /** 回應等待逾時（毫秒）：ATST 十六進位值 × 4，或十進位毫秒 */
    var atstMs: Int = 128

    /** CAN header（ATSH，不含空格） */
    var header: String = ""

    /** ATH：是否顯示回應標頭 */
    var displayHeaders: Boolean = false

    /** ATS：回應是否插入空格 */
    var insertSpaces: Boolean = true

    /** ATE：是否回應 echo 指令本身 */
    var displayEcho: Boolean = true

    /** ATL：是否輸出換行 */
    var lineFeeds: Boolean = true

    /** ATAT：adaptive timing 等級（0/1/2） */
    var adaptiveTimings: Int = 1

    /** ATAL：是否允許長訊息（ELM 以 CAN 多幀處理超長指令） */
    var allowLongMessages: Boolean = false

    /** ATR：是否輸出回應 */
    var responsesOn: Boolean = true

    /** tester address（ATTA / ATCER） */
    var testerAddress: String = "F1"

    /** ATCAF：CAN 自動格式化 */
    var canAutoFormat: Boolean = true

    /** ATCFC：CAN 自動流控 */
    var canFlowControl: Boolean = true

    /** ATFCSM 流控模式：0=Auto、1=Full、2=Data */
    var flowControlMode: Int = 0

    /** ATFCSH 流控接收 header */
    var flowControlHeader: String = ""

    /** ATFCSD 流控資料 */
    var flowControlData: String = ""

    /** 依剛送出的指令更新狀態（僅處理 AT 指令，其餘忽略） */
    fun update(rawCmd: String?) {
        val cmd = rawCmd ?: return
        if (cmd.length < 2) return
        if (cmd[1] != 't' && cmd[1] != 'T') return
        val f = cmd.replace(" ", "").uppercase()
        when {
            f == "ATZ" || f == "ATWS" || f == "ATD" -> reset()
            f == "ATSPA0" -> protocol = 0
            f.startsWith("ATSP") && f.length == 5 -> {
                protocol = f[4].toString().toIntOrNull(16) ?: 0
            }
            f.startsWith("ATST") && f.length == 6 -> {
                atstMs = (f.substring(4).toIntOrNull(16) ?: 0) * 4
            }
            f.startsWith("ATST") && f.length > 6 -> {
                // 十進位毫秒寫法（如 ATST 1000）
                atstMs = f.substring(4).toIntOrNull() ?: atstMs
            }
            f.startsWith("ATSH") -> header = f.substring(4)
            f.startsWith("ATTA") && f.length == 6 -> testerAddress = f.substring(4)
            f.startsWith("ATCER") && f.length == 7 -> testerAddress = f.substring(5)
            f.startsWith("ATFCSH") -> flowControlHeader = f.substring(6)
            f.startsWith("ATFCSD") -> flowControlData = f.substring(6)
            f.startsWith("ATCAF") -> canAutoFormat = f.endsWith("1")
            f.startsWith("ATCFC") -> canFlowControl = f.endsWith("1")
            f.startsWith("ATFCSM") -> flowControlMode = f.substring(6).toIntOrNull() ?: 0
            f == "ATL1" -> lineFeeds = true
            f == "ATL0" -> lineFeeds = false
            f == "ATS1" -> insertSpaces = true
            f == "ATS0" -> insertSpaces = false
            f == "ATE1" -> displayEcho = true
            f == "ATE0" -> displayEcho = false
            f == "ATH1" -> displayHeaders = true
            f == "ATH0" -> displayHeaders = false
            f == "ATAL" -> allowLongMessages = true
            f == "ATNL" -> allowLongMessages = false
            f == "ATR1" -> responsesOn = true
            f == "ATR0" -> responsesOn = false
            f == "ATAT1" -> adaptiveTimings = 1
            f == "ATAT2" -> adaptiveTimings = 2
            f == "ATAT0" -> adaptiveTimings = 0
        }
    }

    /**
     * 判斷某個設定指令是否已被目前狀態涵蓋而可跳過（去重）。
     * 對應 Car Scanner SendBeforeOrAfterCommands 的 atOptimization 跳過邏輯：
     * 例如已開 ATCFC1（canFlowControl=true）則再次 ATCFC1 可跳過。
     */
    fun shouldSkip(rawCmd: String): Boolean {
        val f = rawCmd.replace(" ", "").uppercase()
        return when {
            f == "ATCFC1" -> canFlowControl
            f == "ATCFC0" -> !canFlowControl
            f == "ATCAF1" -> canAutoFormat
            f == "ATCAF0" -> !canAutoFormat
            f == "ATFCSM0" -> flowControlMode == 0
            f == "ATFCSM1" -> flowControlMode == 1
            f == "ATFCSM2" -> flowControlMode == 2
            f == "ATL1" -> lineFeeds
            f == "ATL0" -> !lineFeeds
            f == "ATS1" -> insertSpaces
            f == "ATS0" -> !insertSpaces
            f == "ATE1" -> displayEcho
            f == "ATE0" -> !displayEcho
            f == "ATH1" -> displayHeaders
            f == "ATH0" -> !displayHeaders
            f == "ATAL" -> allowLongMessages
            f == "ATNL" -> !allowLongMessages
            f == "ATR1" -> responsesOn
            f == "ATR0" -> !responsesOn
            f.startsWith("ATSP") && f.length == 5 -> {
                val p = f[4].toString().toIntOrNull(16) ?: 0
                p == protocol
            }
            f == "ATSPA0" -> protocol == 0
            f.startsWith("ATSH") && f.length == 7 -> header == f.substring(4)
            else -> false
        }
    }

    /** 重設為 ELM 預設值（ATZ 後應回到此狀態） */
    fun reset() {
        protocol = 0
        atstMs = 128
        header = ""
        displayHeaders = false
        insertSpaces = true
        displayEcho = true
        lineFeeds = true
        adaptiveTimings = 1
        allowLongMessages = false
        responsesOn = true
        testerAddress = "F1"
        canAutoFormat = true
        canFlowControl = true
        flowControlMode = 0
        flowControlHeader = ""
        flowControlData = ""
    }
}

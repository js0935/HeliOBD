/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OBD 連線診斷記錄：每次 OBD 連線建立一個 log 檔，
 * 記錄連線期間的指令與回應（含初始化、輪詢、斷線事件），供日後排除通訊問題。
 *
 * 檔案存放於 **App 專有外部儲存**（`getExternalFilesDir/HeliOBD_LOGS`），
 * 無需任何儲存權限，Android 10+ scoped storage 也不受影響。
 * 使用者可透過「分享LOG」功能將檔案傳出。
 *
 * 最多保留 [MAX_FILES] 個，超過時刪除最舊的。
 */
object ObdLog {

    /** 子資料夾名稱（App 專有外部儲存之下） */
    const val DIR_NAME = "HeliOBD_LOGS"

    /** 最多保留的 log 檔數量 */
    const val MAX_FILES = 50

    private const val FLUSH_LINE_INTERVAL = 50

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val reusableDate = Date()
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var dirPath: String = ""

    private var lineCount = 0

    /** 取得 LOG 資料夾（App 專有外部儲存） */
    private fun getLogDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, DIR_NAME) }

    /** 連線開始：建立新 log 檔並寫入開頭。呼叫前若已有 session 會先關閉。 */
    @Synchronized
    fun start(context: Context, deviceAddress: String?) {
        stop()
        val dir = getLogDir(context) ?: return
        if (!dir.exists() && !dir.mkdirs()) return
        dirPath = dir.absolutePath
        trimOld(dir)
        val name = "HeliOBD_" + fileNameFormat.format(Date()) + ".log"
        return try {
            writer = File(dir, name).bufferedWriter(Charsets.UTF_8)
            writer?.write("[${timeFormat.format(Date())}] CONNECT START device=${deviceAddress ?: "unknown"}\n")
            lineCount = 0
        } catch (_: Exception) {
            closeWriter()
        }
    }

    /** 目前是否有啟用的 writer（避免呼叫端白白做字串插值） */
    fun isActive(): Boolean = writer != null

    /** 寫一行記錄（指令／回應／事件）；尚未開始 session 或寫入失敗時忽略。 */
    @Synchronized
    fun log(message: String) {
        val w = writer ?: return
        try {
            w.write("[${timeFormat.format(reusableDate.also { it.time = System.currentTimeMillis() })}] $message\n")
            if (++lineCount % FLUSH_LINE_INTERVAL == 0) w.flush()
        } catch (_: Exception) {
            // 記錄失敗不影響通訊主流程
        }
    }

    /** 連線結束：flush 並關閉目前 log 檔。 */
    @Synchronized
    fun stop() {
        closeWriter()
    }

    private fun closeWriter() {
        val w = writer ?: return
        writer = null
        try { w.flush() } catch (_: Exception) { }
        try { w.close() } catch (_: Exception) { }
    }

    /** 目前記錄資料夾的絕對路徑；尚未建立過 session 為空字串。 */
    fun currentDirPath(): String = dirPath

    /** 保留最新 [MAX_FILES] 個 log 檔，其餘刪除。 */
    private fun trimOld(dir: File) {
        val files = (dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return)
            .sortedBy { it.lastModified() }
        var index = 0
        while (files.size - index > MAX_FILES) {
            files[index++].delete()
        }
    }
}

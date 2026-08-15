/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OBD 連線診斷記錄：每次 OBD 藍牙連線建立一個 log 檔，
 * 記錄連線期間的指令與回應（含初始化、輪詢、斷線事件），供日後排除通訊問題。
 *
 * 檔案位於 App 專屬外部儲存的 HeliOBD 資料夾
 * （`Android/data/com.heli.obd/files/HeliOBD`），最多保留 [MAX_FILES] 個，
 * 超過時刪除最舊的。
 */
object ObdLog {

    /** 資料夾名稱 */
    const val DIR_NAME = "HeliOBD"

    /** 最多保留的 log 檔數量 */
    const val MAX_FILES = 50

    private const val FLUSH_LINE_INTERVAL = 50

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var dirPath: String = ""

    private var lineCount = 0

    /** 連線開始：建立新 log 檔並寫入開頭。呼叫前若已有 session 會先關閉。 */
    @Synchronized
    fun start(context: Context, deviceAddress: String?) {
        stop()
        val base = context.getExternalFilesDir(null) ?: return
        val dir = File(base, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return
        dirPath = dir.absolutePath
        trimOld(dir)
        val name = "HeliOBD_" + fileNameFormat.format(Date()) + ".log"
        try {
            val w = File(dir, name).bufferedWriter(Charsets.UTF_8)
            w.write("[${timeFormat.format(Date())}] CONNECT START device=${deviceAddress ?: "unknown"}\n")
            writer = w
            lineCount = 0
        } catch (_: Exception) {
            writer = null
        }
    }

    /** 寫一行記錄（指令／回應／事件）；尚未開始 session 或寫入失敗時忽略。 */
    @Synchronized
    fun log(message: String) {
        val w = writer ?: return
        try {
            w.write("[${timeFormat.format(Date())}] $message\n")
            if (++lineCount % FLUSH_LINE_INTERVAL == 0) w.flush()
        } catch (_: Exception) {
            // 記錄失敗不影響通訊主流程
        }
    }

    /** 連線結束：flush 並關閉目前 log 檔。 */
    @Synchronized
    fun stop() {
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

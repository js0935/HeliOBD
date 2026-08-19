/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OBD 連線診斷記錄：每次 OBD 連線建立一個 log 檔，
 * 記錄連線期間的指令與回應（含初始化、輪詢、斷線事件），供日後排除通訊問題。
 *
 * 檔案存放於**公開 Download 資料夾**（`/sdcard/Download/HeliOBD_LOGS`），
 * 使用者可直接從手機檔案管理 App 讀取：
 * - Android 10+（API 29）：透過 MediaStore.Downloads 寫入，免儲存權限。
 * - Android 8-9（API 26-28）：直接寫入 Download 資料夾，需 `WRITE_EXTERNAL_STORAGE`
 *   權限；未授權時退回 App 專屬外部儲存（`Android/data/.../files/HeliOBD`）。
 *
 * 最多保留 [MAX_FILES] 個，超過時刪除最舊的。
 */
object ObdLog {

    /** 公開資料夾名稱（Download 之下） */
    const val DIR_NAME = "HeliOBD_LOGS"

    /** 最多保留的 log 檔數量 */
    const val MAX_FILES = 50

    private const val FLUSH_LINE_INTERVAL = 50

    /** 未取得儲存權限時的退回資料夾名稱（App 私有區） */
    private const val FALLBACK_DIR_NAME = "HeliOBD"

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val reusableDate = Date()
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var dirPath: String = ""

    /** Android 10+ 透過 MediaStore 建立的檔案 uri（關閉時清除 pending 旗標） */
    @Volatile
    private var mediaUri: Uri? = null

    @Volatile
    private var mediaResolver: ContentResolver? = null

    private var lineCount = 0

    /** 連線開始：建立新 log 檔並寫入開頭。呼叫前若已有 session 會先關閉。 */
    @Synchronized
    fun start(context: Context, deviceAddress: String?) {
        stop()
        // 清除上次異常結束留下的 pending 旗標，確保 LOG 分享功能可找到檔案
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) clearPendingFlags(context)
        val name = "HeliOBD_" + fileNameFormat.format(Date()) + ".log"
        val ok =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                openMedia(context, name)
            } else {
                openLegacy(context, name)
            }
        if (!ok) return
        try {
            writer?.write("[${timeFormat.format(Date())}] CONNECT START device=${deviceAddress ?: "unknown"}\n")
            lineCount = 0
        } catch (_: Exception) {
            closeWriter()
        }
    }

    /** Android 10+：透過 MediaStore.Downloads 建立公開可讀的 log 檔（免儲存權限）。 */
    private fun openMedia(context: Context, name: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: return false
            val out: OutputStream = resolver.openOutputStream(uri) ?: return false
            writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
            mediaUri = uri
            mediaResolver = resolver
            dirPath = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DIR_NAME
            ).absolutePath
            trimOld(context)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Android 8-9：直接寫入 `Download/HeliOBD_LOGS`（需 `WRITE_EXTERNAL_STORAGE`），
     * 未授權時退回 App 專屬外部儲存。
     */
    private fun openLegacy(context: Context, name: String): Boolean {
        val dir: File =
            if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(base, DIR_NAME)
            } else {
                context.getExternalFilesDir(null)?.let { File(it, FALLBACK_DIR_NAME) } ?: return false
            }
        if (!dir.exists() && !dir.mkdirs()) return false
        dirPath = dir.absolutePath
        trimOldLegacy(dir)
        return try {
            writer = File(dir, name).bufferedWriter(Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
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
        // 清除 MediaStore pending 旗標，讓檔案立即出現在媒體庫／檔案管理 App
        val uri = mediaUri ?: return
        mediaUri = null
        val resolver = mediaResolver ?: return
        mediaResolver = null
        try {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (_: Exception) { }
    }

    /** 目前記錄資料夾的絕對路徑；尚未建立過 session 為空字串。 */
    fun currentDirPath(): String = dirPath

    /** Android 10+：保留最新 [MAX_FILES] 個 log 檔，其餘刪除（依 MediaStore _ID 排序）。 */
    private fun trimOld(context: Context) {
        try {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "%")
            val ids = arrayListOf<Pair<Long, String>>()
            context.contentResolver.query(collection, projection, selection, selectionArgs,
                "${MediaStore.Downloads._ID} ASC")
                ?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val name = c.getString(nameCol)
                        if (name.endsWith(".log")) {
                            ids.add(c.getLong(idCol) to name)
                        }
                    }
                }
            while (ids.size > MAX_FILES) {
                val (id, _) = ids.removeAt(0)
                context.contentResolver.delete(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, id),
                    null,
                    null
                )
            }
        } catch (_: Exception) { }
    }

    /** Android 10+：清除上次異常結束留下的 IS_PENDING 旗標，讓 LOG 分享功能可找到檔案 */
    private fun clearPendingFlags(context: Context) {
        try {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING} != 0"
            val selectionArgs = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "%")
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    context.contentResolver.update(
                        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, id),
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null, null
                    )
                }
            }
        } catch (_: Exception) { }
    }

    /** Android 8-9：保留最新 [MAX_FILES] 個 log 檔，其餘刪除。 */
    private fun trimOldLegacy(dir: File) {
        val files = (dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return)
            .sortedBy { it.lastModified() }
        var index = 0
        while (files.size - index > MAX_FILES) {
            files[index++].delete()
        }
    }
}

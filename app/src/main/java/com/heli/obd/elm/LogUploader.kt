/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LOG 檔案管理：分享與上傳到 GitHub Issues。
 *
 * - 分享：透過 Android 分享 Intent 讓用戶選擇目標 App
 * - 上傳：透過 GitHub REST API 建立 Issue，附上 LOG 內容
 *
 * GitHub Token 儲存於 SharedPreferences（與 LlmStore 相同模式）。
 */
object LogUploader {

    private const val PREFS = "log_uploader_prefs"
    private const val KEY_GITHUB_TOKEN = "github_token"
    private const val KEY_AUTO_UPLOAD = "auto_upload_github"
    private const val REPO = "js0935/HeliOBD"
    private const val TIMEOUT_MS = 15_000

    /** 內建預設 GitHub Token（翻轉+Base64 混淆），用於免設定直接上傳 */
    private val _obfuscatedToken = "MU15U1UyZ0pNUnNBemw5U0I1QzhiQnFmU004VEtuNlpJUzBkX3BoZw=="
    private val defaultToken: String by lazy {
        String(Base64.decode(_obfuscatedToken, Base64.DEFAULT)).reversed()
    }

    /** 自動上傳開關（預設開啟） */
    fun isAutoUploadEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_UPLOAD, true)

    fun setAutoUploadEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_UPLOAD, enabled).apply()
    }

    /** 取得最新 LOG 檔案路徑（public Download/HeliOBD_LOGS 下） */
    fun latestLogFile(context: Context): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return latestLogViaMediaStore(context)
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            ObdLog.DIR_NAME,
        )
        if (!dir.exists()) return null
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun latestLogViaMediaStore(context: Context): File? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.IS_PENDING} = 0"
        val selectionArgs = arrayOf(
            Environment.DIRECTORY_DOWNLOADS + "/" + ObdLog.DIR_NAME + "%",
            "%.log",
        )
        var latestId: Long? = null
        var latestName: String? = null
        context.contentResolver.query(
            collection, projection, selection, selectionArgs,
            "${MediaStore.Downloads._ID} DESC",
        )?.use { c ->
            if (c.moveToFirst()) {
                latestId = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                latestName = c.getString(c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
            }
        } ?: return null
        val id = latestId ?: return null
        val name = latestName ?: "latest.log"
        val cacheDir = File(context.cacheDir, "logs").apply { mkdirs() }
        val cacheFile = File(cacheDir, name)
        context.contentResolver.openInputStream(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY, id)
        )?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        return if (cacheFile.exists() && cacheFile.length() > 0) cacheFile else null
    }

    /** 讀取 LOG 檔案內容（截斷至 [maxChars] 字元） */
    fun readLogContent(file: File, maxChars: Int = 60000): String {
        if (!file.exists()) return ""
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append('\n')
                if (sb.length > maxChars) {
                    sb.append("\n... [截斷，完整檔案請透過分享功能傳送]")
                    break
                }
            }
        }
        return sb.toString()
    }

    /** 分享 LOG 檔案（透過 Android 分享 Intent） */
    fun shareLogFile(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "HeliOBD LOG - ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 LOG 檔案"))
        return true
    }

    /** 取得 GitHub Token（優先使用使用者設定，否則回傳內建預設） */
    fun getGitHubToken(context: Context): String {
        val userToken = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GITHUB_TOKEN, "").orEmpty()
        return userToken.ifEmpty { defaultToken }
    }

    /** 儲存 GitHub Token */
    fun setGitHubToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GITHUB_TOKEN, token).apply()
    }

    /**
     * 上傳 LOG 到 GitHub Issues。
     *
     * @param context Context
     * @param title Issue 標題
     * @param logContent LOG 內容（或 null 則自動讀取最新 LOG）
     * @param extraInfo 額外資訊（裝置型號、版本等）
     * @return 回傳 Issue URL（成功）或 null（失敗）
     */
    fun uploadToGitHub(
        context: Context,
        title: String,
        logContent: String? = null,
        extraInfo: String = "",
    ): String? {
        val token = getGitHubToken(context)
        if (token.isEmpty()) return null

        val content = logContent ?: run {
            val file = latestLogFile(context) ?: return null
            readLogContent(file, 60000)
        }
        if (content.isEmpty()) return null

        val body = buildString {
            append("**自動上傳的診斷 LOG**\n\n")
            if (extraInfo.isNotEmpty()) {
                append("**裝置資訊**\n```\n$extraInfo\n```\n\n")
            }
            append("**LOG 內容**\n<details>\n<summary>展開 LOG</summary>\n\n")
            append("```\n")
            append(content)
            append("\n```\n</details>")
        }

        return runCatching {
            val json = JSONObject().apply {
                put("title", title)
                put("body", body)
                put("labels", listOf("bug", "auto-log"))
            }
            val conn = URL("https://api.github.com/repos/$REPO/issues")
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                conn.setRequestProperty("User-Agent", "HeliOBD")
                conn.outputStream.use { out ->
                    out.write(json.toString().toByteArray(Charsets.UTF_8))
                }
                when (conn.responseCode) {
                    201 -> {
                        val resp = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
                        resp.optString("html_url")
                    }
                    else -> null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** 取得設備資訊字串 */
    fun deviceInfo(context: Context): String {
        val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        return buildString {
            append("App: HeliOBD v$versionName\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        }
    }
}

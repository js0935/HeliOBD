/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
 * - 新版 LOG 存於 App 專有外部儲存（getExternalFilesDir/HeliOBD_LOGS）
 * - 舊版 LOG 在 Download/HeliOBD_LOGS，需 MANAGE_EXTERNAL_STORAGE 才能讀取
 */
object LogUploader {

    private const val PREFS = "log_uploader_prefs"
    private const val KEY_GITHUB_TOKEN = "github_token"
    private const val KEY_AUTO_UPLOAD = "auto_upload_github"
    private const val REPO = "js0935/HeliOBD"
    private const val TIMEOUT_MS = 15_000

    private val _obfuscatedToken = "MU15U1UyZ0pNUnNBemw5U0I1QzhiQnFmU004VEtuNlpJUzBkX3BoZw=="
    private val defaultToken: String by lazy {
        String(Base64.decode(_obfuscatedToken, Base64.DEFAULT)).reversed()
    }

    fun isAutoUploadEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_UPLOAD, true)

    fun setAutoUploadEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_UPLOAD, enabled).apply()
    }

    private fun getPrivateLogDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, ObdLog.DIR_NAME) }

    private fun getDownloadLogDir(): File? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            ObdLog.DIR_NAME,
        )
        return if (dir.exists()) dir else null
    }

    /** 檢查是否已取得管理所有檔案權限（Android 11+） */
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /** 前往設定頁請求管理所有檔案權限（Android 11+） */
    fun requestStoragePermission(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"))
        }
        return null
    }

    /** 取得最新 LOG 檔案：優先私有目錄，fallback 查 Download/HeliOBD_LOGS */
    fun latestLogFile(context: Context): File? {
        val dir = getPrivateLogDir(context)
        if (dir != null && dir.exists()) {
            val f = dir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
            if (f != null) return f
        }

        if (hasStoragePermission()) {
            val dDir = getDownloadLogDir()
            if (dDir != null) {
                val f = dDir.listFiles { file ->
                    file.isFile && (file.name.endsWith(".log") || file.name.endsWith(".log.txt"))
                }?.maxByOrNull { it.lastModified() }
                if (f != null) return f
            }
        }

        return null
    }

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

    fun getGitHubToken(context: Context): String {
        val userToken = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GITHUB_TOKEN, "").orEmpty()
        return userToken.ifEmpty { defaultToken }
    }

    fun setGitHubToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GITHUB_TOKEN, token).apply()
    }

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

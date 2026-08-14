/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自動更新：查詢 GitHub 最新 Release、比較版本、下載 APK、以 FileProvider 轉交系統安裝器。
 *
 * 版本以 Release 的 tag（v0.2.0）與 App 的 versionName（0.2.0）做語意化比較；
 * 公開 repo 使用 GitHub API 免認證，每次檢查僅數 KB。安裝流程受 Android 系統限制，
 * 需使用者於系統安裝畫面確認一次（一般手機無法靜默安裝）。
 */
object UpdateChecker {

    const val REPO = "js0935/HeliOBD"
    private const val APK_ASSET_NAME = "HeliOBD.apk"
    private const val TIMEOUT_MS = 10_000
    private const val APK_FILE_NAME = "heliobd-update.apk"
    private const val PREFS = "update_prefs"
    private const val KEY_AUTO_UPDATE = "auto_update"

    /** 自動檢查更新開關（控制啟動檢查與每日背景檢查，預設開啟） */
    fun isAutoUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_UPDATE, true)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
    }

    data class Release(
        val version: String,
        val apkUrl: String?,
        val notes: String,
    )

    /** 從版本字串擷取前三段數字段落（v0.2.0-rc1 → [0,2,0]） */
    fun versionParts(raw: String): List<Int> =
        Regex("\\d+").findAll(raw).map { it.value.toInt() }.toList().take(3)

    /** 語意化版本比較；回傳 true 表示 remote 比 local 新 */
    fun isNewer(local: String, remote: String): Boolean {
        val l = versionParts(local)
        val r = versionParts(remote)
        val n = maxOf(l.size, r.size)
        for (i in 0 until n) {
            val lv = l.getOrElse(i) { 0 }
            val rv = r.getOrElse(i) { 0 }
            if (lv != rv) return rv > lv
        }
        return false
    }

    /** 查詢 GitHub 最新 Release；失敗或無資料回傳 null（勿阻斷 App 流程） */
    fun fetchLatest(): Release? = runCatching {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "HeliOBD")
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
            val version = json.optString("tag_name").removePrefix("v")
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name") == APK_ASSET_NAME) {
                        apkUrl = a.optString("browser_download_url").ifEmpty { null }
                        break
                    }
                }
            }
            Release(version, apkUrl, json.optString("body"))
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    fun apkFile(context: Context): File = File(context.cacheDir, APK_FILE_NAME)

    /** 下載 APK 至 cache；成功回傳 true */
    fun download(context: Context, url: String): Boolean = runCatching {
        val file = apkFile(context)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "HeliOBD")
            if (conn.responseCode != 200) return false
            conn.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file.length() > 0L
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    /** 以 FileProvider 轉交系統安裝器；檔案不存在回傳 false */
    fun install(context: Context): Boolean = runCatching {
        val file = apkFile(context)
        if (!file.exists()) return false
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

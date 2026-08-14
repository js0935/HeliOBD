/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.heli.obd.MainActivity
import com.heli.obd.R
import java.util.concurrent.TimeUnit

/**
 * 每日背景檢查更新：發現新版時發系統通知，點擊後由 MainActivity 下載並安裝。
 * 通知權限（Android 13+）未授權時僅略過通知，不影響 App 本身。
 */
class UpdateCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!UpdateChecker.isAutoUpdateEnabled(ctx)) return Result.success()
        val release = UpdateChecker.fetchLatest() ?: return Result.success()
        val local = localVersion(ctx)
        if (release.apkUrl != null && UpdateChecker.isNewer(local, release.version)) {
            notifyUpdate(ctx, release)
        }
        return Result.success()
    }

    private fun localVersion(ctx: Context): String =
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
        }.getOrDefault("0")

    private fun notifyUpdate(ctx: Context, release: UpdateChecker.Release) {
        createChannel(ctx)
        val manager = NotificationManagerCompat.from(ctx)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val pi = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(MainActivity.EXTRA_UPDATE_DOWNLOAD, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(ctx.getString(R.string.update_available_title))
            .setContentText(ctx.getString(R.string.update_available_desc, release.version))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIF_ID, notification)
    }

    private fun createChannel(ctx: Context) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    companion object {
        const val CHANNEL_ID = "heli_updates"
        private const val NOTIF_ID = 0x5510
        private const val UNIQUE_WORK = "heli_update_check"

        /** 排定每日一次的背景檢查（App 啟動時呼叫，KEEP 避免重複排程） */
        fun scheduleDaily(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

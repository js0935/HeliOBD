/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.heli.obd.MainActivity
import com.heli.obd.R
import java.util.Locale

/**
 * 閾值警示監聽器：掛在 ObdManager 上，當水溫/轉速/電壓超過設定值時發出提示。
 * 閾值由 AlertsActivity 寫入 SharedPreferences（alert_prefs）。
 * 可被多個畫面重複 attach，內部只掛一個監聽執行個體。
 *
 * 注意：此單例僅持有 application context（attach 時由 MainActivity 以
 * applicationContext 傳入，此處再以 applicationContext 正規化），
 * 與應用程式同生命週期，不會造成 Activity/Fragment 洩漏。
 */
@SuppressLint("StaticFieldLeak")
object AlertMonitor {

    private const val PREFS = "alert_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VOICE = "voice"
    private const val KEY_COOLANT_MAX = "coolantMax"
    private const val KEY_RPM_MAX = "rpmMax"
    private const val KEY_VOLTAGE_MIN = "voltageMin"

    private const val ALERT_COOLDOWN_MS = 5000L

    private const val NOTIF_CHANNEL_ID = "heli_engine_alerts"
    private const val NOTIF_ID = 0x4A31

    private var attached = false
    private var context: Context? = null
    private var lastCoolantAlert = 0L
    private var lastRpmAlert = 0L
    private var lastVoltageAlert = 0L
    private var tone: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** 快取 SharedPreferences 與閾值，避免每 500ms 重複查找 */
    private var cachedPrefs: android.content.SharedPreferences? = null
    private var cachedEnabled = false
    private var cachedCoolantMax = 110
    private var cachedRpmMax = 9000
    private var cachedVoltageMin = 11.5f

    private val listener = object : ObdManager.Listener {
        override fun onStateChanged(state: ObdManager.State) {}

        override fun onLiveData(data: ObdManager.LiveData) {
            checkValues(data)
        }
    }

    fun attach(obd: ObdManager, appContext: Context) {
        if (attached) return
        attached = true
        context = appContext.applicationContext
        reloadPrefs()
        obd.addListener(listener)
    }

    fun detach(obd: ObdManager) {
        if (!attached) return
        attached = false
        obd.removeListener(listener)
        context = null
    }

    private fun reloadPrefs() {
        val ctx = context ?: return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cachedPrefs = p
        cachedEnabled = p.getBoolean(KEY_ENABLED, false)
        cachedCoolantMax = p.getInt(KEY_COOLANT_MAX, 110)
        cachedRpmMax = p.getInt(KEY_RPM_MAX, 9000)
        cachedVoltageMin = p.getFloat(KEY_VOLTAGE_MIN, 11.5f)
    }

    private fun checkValues(data: ObdManager.LiveData) {
        if (!cachedEnabled) return

        val now = System.currentTimeMillis()

        data.coolant?.let { coolant ->
            if (coolant > cachedCoolantMax && now - lastCoolantAlert > ALERT_COOLDOWN_MS) {
                lastCoolantAlert = now
                val ctx = context ?: return
                alert(ctx, ctx.getString(R.string.alert_msg_coolant, coolant))
            }
        }
        data.rpm?.let { rpm ->
            if (rpm > cachedRpmMax && now - lastRpmAlert > ALERT_COOLDOWN_MS) {
                lastRpmAlert = now
                val ctx = context ?: return
                alert(ctx, ctx.getString(R.string.alert_msg_rpm, rpm))
            }
        }
        data.voltage?.let { voltage ->
            if (voltage < cachedVoltageMin && now - lastVoltageAlert > ALERT_COOLDOWN_MS) {
                lastVoltageAlert = now
                val ctx = context ?: return
                alert(ctx, ctx.getString(R.string.alert_msg_voltage, "%.1f".format(voltage)))
            }
        }
    }

    private fun alert(ctx: Context, message: String) {
        runCatching {
            if (tone == null) {
                tone = ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80)
            }
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
        }
        runCatching {
            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        }
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        notifyAlert(ctx, message)
        speak(ctx, message)
    }

    /** 發送系統通知（音效/震動由 tone/vibrator 負責，通知本身不重複發出聲音） */
    private fun notifyAlert(ctx: Context, message: String) {
        runCatching {
            createChannel(ctx)
            val manager = NotificationManagerCompat.from(ctx)
            if (!manager.areNotificationsEnabled()) return
            // Android 13+ 通知權限屬 runtime，未授權時略過（音效/震動仍生效）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val pi = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIF_ID, notification)
        }
    }

    private fun createChannel(ctx: Context) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIF_CHANNEL_ID,
                ctx.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun speak(ctx: Context, message: String) {
        val voiceEnabled = cachedPrefs?.getBoolean(KEY_VOICE, true) ?: true
        if (!voiceEnabled) return
        runCatching {
            if (tts == null) {
                tts = TextToSpeech(ctx) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.getDefault()
                        ttsReady = true
                    }
                }
            }
            if (ttsReady) {
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "heli_alert")
            }
        }
    }

    fun release() {
        runCatching { tone?.release() }
        tone = null
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }
}

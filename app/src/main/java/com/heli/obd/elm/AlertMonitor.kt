/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.elm

import android.content.Context
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.heli.obd.R
import java.util.Locale

/**
 * 閾值警示監聽器：掛在 ObdManager 上，當水溫/轉速/電壓超過設定值時發出提示。
 * 閾值由 AlertsActivity 寫入 SharedPreferences（alert_prefs）。
 * 可被多個畫面重複 attach，內部只掛一個監聽執行個體。
 */
object AlertMonitor {

    private const val PREFS = "alert_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VOICE = "voice"
    private const val KEY_COOLANT_MAX = "coolantMax"
    private const val KEY_RPM_MAX = "rpmMax"
    private const val KEY_VOLTAGE_MIN = "voltageMin"

    private const val ALERT_COOLDOWN_MS = 5000L

    private var attached = false
    private var context: Context? = null
    private var lastCoolantAlert = 0L
    private var lastRpmAlert = 0L
    private var lastVoltageAlert = 0L
    private var tone: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val listener = object : ObdManager.Listener {
        override fun onStateChanged(state: ObdManager.State) {}

        override fun onLiveData(data: ObdManager.LiveData) {
            checkValues(data)
        }
    }

    fun attach(obd: ObdManager, appContext: Context) {
        if (attached) return
        attached = true
        context = appContext
        obd.addListener(listener)
    }

    fun detach(obd: ObdManager) {
        if (!attached) return
        attached = false
        obd.removeListener(listener)
        context = null
    }

    private fun checkValues(data: ObdManager.LiveData) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return

        val now = System.currentTimeMillis()

        data.coolant?.let { coolant ->
            val max = prefs.getInt(KEY_COOLANT_MAX, 110)
            if (coolant > max && now - lastCoolantAlert > ALERT_COOLDOWN_MS) {
                lastCoolantAlert = now
                alert(ctx, ctx.getString(R.string.alert_msg_coolant, coolant))
            }
        }
        data.rpm?.let { rpm ->
            val max = prefs.getInt(KEY_RPM_MAX, 9000)
            if (rpm > max && now - lastRpmAlert > ALERT_COOLDOWN_MS) {
                lastRpmAlert = now
                alert(ctx, ctx.getString(R.string.alert_msg_rpm, rpm))
            }
        }
        data.voltage?.let { voltage ->
            val min = prefs.getFloat(KEY_VOLTAGE_MIN, 11.5f)
            if (voltage < min && now - lastVoltageAlert > ALERT_COOLDOWN_MS) {
                lastVoltageAlert = now
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
        speak(ctx, message)
    }

    private fun speak(ctx: Context, message: String) {
        val voiceEnabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOICE, true)
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

package com.heli.obd.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.ObdManager
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 引擎聲浪：以 OBD 真實轉速（或滑桿模擬）驅動即時合成引擎排氣聲浪。
 *
 * 合成模型：基頻 = RPM/60（單缸每轉一脈衝），疊加 2/3/4/6 次諧波
 * 近似引擎點火脈衝，另加隨轉速放大的氣流雜訊；頻率平滑追蹤產生轉速
 * 爬升/下降時的滑音感。
 */
class EngineSoundActivity : AppCompatActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var rpmValue: TextView
    private lateinit var modeText: TextView
    private lateinit var rpmSeek: SeekBar
    private lateinit var rpmSeekLabel: TextView
    private lateinit var playBtn: Button

    private var audioTrack: AudioTrack? = null
    private var playThread: Thread? = null
    @Volatile private var playing = false

    /** 目標轉速（OBD 真實值或滑桿模擬值） */
    @Volatile private var targetRpm = 3000.0
    private var obdConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_engine_sound)

        rpmValue = findViewById(R.id.rpm_value)
        modeText = findViewById(R.id.mode_text)
        rpmSeek = findViewById(R.id.rpm_seek)
        rpmSeekLabel = findViewById(R.id.rpm_seek_label)
        playBtn = findViewById(R.id.btn_play)

        rpmSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !obdConnected) {
                    targetRpm = (MIN_RPM + progress).toDouble()
                    updateRpmUi()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        playBtn.setOnClickListener {
            if (playing) stopPlayback() else startPlayback()
        }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        obd.addListener(this)
        obdConnected = obd.isConnected()
        renderMode()
    }

    override fun onDestroy() {
        stopPlayback()
        obd.removeListener(this)
        super.onDestroy()
    }

    // ===== ObdManager.Listener =====

    override fun onStateChanged(state: ObdManager.State) {
        obdConnected = state == ObdManager.State.Ready
        runOnUiThread { renderMode() }
    }

    override fun onLiveData(data: ObdManager.LiveData) {
        data.rpm?.let {
            targetRpm = it.toDouble()
            runOnUiThread { updateRpmUi() }
        }
    }

    // ===== 播放 =====

    private fun startPlayback() {
        if (playing) return
        playing = true
        playBtn.setText(R.string.common_stop)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(SAMPLE_RATE * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()

        playThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ShortArray(CHUNK_SIZE)
            val noiseRng = Random(System.nanoTime())
            var freq = 50.0
            var phase = 0.0
            while (playing) {
                // 平滑追蹤：頻率快速拉近目標，產生轉速變化的滑音
                freq += (targetRpm / 60.0 - freq) * 0.08
                val w = TWO_PI * freq / SAMPLE_RATE
                for (i in buffer.indices) {
                    phase += w
                    buffer[i] = synth(phase, freq, noiseRng.nextDouble() * 2 - 1)
                }
                if (track.write(buffer, 0, buffer.size) < 0) break
            }
        }.apply { start() }
    }

    private fun stopPlayback() {
        playing = false
        playThread?.join(300)
        playThread = null
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        playBtn.setText(R.string.common_play)
    }

    /** 合成單一取樣點：基頻+諧波（引擎點火脈衝）與氣流雜訊（隨轉速放大）疊加 */
    private fun synth(phase: Double, freq: Double, noise: Double): Short {
        val v = sin(phase) * 0.50 +
            sin(phase * 2) * 0.30 +
            sin(phase * 3) * 0.16 +
            sin(phase * 4) * 0.09 +
            sin(phase * 6) * 0.05
        val breath = noise * (0.02 + (freq / 250.0) * 0.30)
        val out = (v * 0.72 + breath).coerceIn(-1.0, 1.0)
        return (out * Short.MAX_VALUE).toInt().toShort()
    }

    // ===== UI =====

    private fun renderMode() {
        rpmSeek.isEnabled = !obdConnected
        modeText.setText(
            if (obdConnected) R.string.engine_sound_mode_real
            else R.string.engine_sound_mode_sim
        )
        modeText.setTextColor(
            getColor(if (obdConnected) R.color.success else R.color.text_secondary)
        )
    }

    private fun updateRpmUi() {
        val rpm = targetRpm.toInt()
        rpmValue.text = rpm.toString()
        rpmSeekLabel.text = getString(R.string.engine_sound_rpm_value, rpm)
        if (!obdConnected) {
            rpmSeek.progress = (rpm - MIN_RPM).coerceIn(0, MAX_RPM - MIN_RPM)
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHUNK_SIZE = 1024
        private const val MIN_RPM = 1000
        private const val MAX_RPM = 12000
        private const val TWO_PI = 2.0 * PI
    }
}

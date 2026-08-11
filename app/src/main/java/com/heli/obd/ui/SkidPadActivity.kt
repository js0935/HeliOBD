/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.heli.obd.BaseActivity
import com.heli.obd.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 甩尾圓環（Skid Pad）：以手機加速度計量測橫向/縱向 G 值，
 * 並於圓環軌跡圖即時繪製 G 向量軌跡。
 */
class SkidPadActivity : BaseActivity(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private lateinit var gView: SkidPadView
    private lateinit var latGText: TextView
    private lateinit var lonGText: TextView
    private lateinit var peakLatText: TextView
    private lateinit var peakLonText: TextView
    private lateinit var peakTotalText: TextView
    private lateinit var toggleBtn: Button

    private var running = false
    private var accelX = 0f
    private var accelY = 0f
    private var peakLat = 0f
    private var peakLon = 0f
    private var peakTotal = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skid_pad)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gView = findViewById(R.id.skid_pad_view)
        latGText = findViewById(R.id.skid_lat_value)
        lonGText = findViewById(R.id.skid_lon_value)
        peakLatText = findViewById(R.id.skid_peak_lat_value)
        peakLonText = findViewById(R.id.skid_peak_lon_value)
        peakTotalText = findViewById(R.id.skid_peak_total_value)
        toggleBtn = findViewById(R.id.btn_skid_toggle)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        toggleBtn.setOnClickListener {
            if (running) stop() else start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (running) register()
    }

    override fun onPause() {
        unregister()
        super.onPause()
    }

    private fun start() {
        running = true
        peakLat = 0f
        peakLon = 0f
        peakTotal = 0f
        renderPeaks()
        gView.clear()
        toggleBtn.setText(R.string.skid_pad_stop)
        register()
    }

    private fun stop() {
        running = false
        unregister()
        toggleBtn.setText(R.string.skid_pad_start)
        latGText.text = ZERO_G
        lonGText.text = ZERO_G
        gView.clear()
    }

    private fun register() {
        val sm = sensorManager ?: return
        sm.registerListener(
            this,
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_GAME,
        )
    }

    private fun unregister() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        accelX = ALPHA * accelX + (1 - ALPHA) * event.values[0]
        accelY = ALPHA * accelY + (1 - ALPHA) * event.values[1]
        val latG = accelX / SensorManager.GRAVITY_EARTH
        val lonG = accelY / SensorManager.GRAVITY_EARTH
        val totalG = sqrt(latG * latG + lonG * lonG)

        latGText.text = String.format(Locale.US, G_FMT, latG)
        lonGText.text = String.format(Locale.US, G_FMT, lonG)
        if (abs(latG) > peakLat) peakLat = abs(latG)
        if (abs(lonG) > peakLon) peakLon = abs(lonG)
        if (totalG > peakTotal) peakTotal = totalG
        renderPeaks()
        gView.update(latG, lonG)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    private fun renderPeaks() {
        peakLatText.text = String.format(Locale.US, G_FMT, peakLat)
        peakLonText.text = String.format(Locale.US, G_FMT, peakLon)
        peakTotalText.text = String.format(Locale.US, G_FMT, peakTotal)
    }

    private companion object {
        const val ALPHA = 0.12f
        const val ZERO_G = "0.00 G"
        const val G_FMT = "%.2f G"
    }
}

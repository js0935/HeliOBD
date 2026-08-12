/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.heli.obd.BaseActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.trip.FuelCalibration
import com.heli.obd.trip.TripRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 行程回顧：記錄每次騎乘的統計數據（時長/里程/速度/轉速），並檢視歷史行程。
 */
class TripActivity : BaseActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)
    private val recorder by lazy { TripRecorder(this, obd) }

    private lateinit var recordBtn: Button
    private lateinit var statusText: TextView
    private lateinit var liveCard: LinearLayout
    private lateinit var liveDuration: TextView
    private lateinit var liveDistance: TextView
    private lateinit var liveMaxSpeed: TextView
    private lateinit var liveAvgSpeed: TextView
    private lateinit var liveFuelRate: TextView
    private lateinit var liveFuelTotal: TextView
    private lateinit var liveFuelDynamic: TextView
    private lateinit var liveFuelStatic: TextView
    private lateinit var liveIdleTime: TextView
    private lateinit var liveFuelCost: TextView
    private lateinit var fuelPriceEdit: android.widget.EditText
    private lateinit var calBtn: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var totalStatsCard: LinearLayout
    private lateinit var totalStatsText: TextView

    private val prefs by lazy { getSharedPreferences("trip_prefs", MODE_PRIVATE) }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            refreshLive()
            refreshHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip)

        recordBtn = findViewById(R.id.btn_record)
        statusText = findViewById(R.id.trip_status)
        liveCard = findViewById(R.id.live_card)
        liveDuration = findViewById(R.id.live_duration)
        liveDistance = findViewById(R.id.live_distance)
        liveMaxSpeed = findViewById(R.id.live_max_speed)
        liveAvgSpeed = findViewById(R.id.live_avg_speed)
        liveFuelRate = findViewById(R.id.live_fuel_rate)
        liveFuelTotal = findViewById(R.id.live_fuel_total)
        liveFuelDynamic = findViewById(R.id.live_fuel_dynamic)
        liveFuelStatic = findViewById(R.id.live_fuel_static)
        liveIdleTime = findViewById(R.id.live_idle_time)
        liveFuelCost = findViewById(R.id.live_fuel_cost)
        fuelPriceEdit = findViewById(R.id.fuel_price_edit)
        calBtn = findViewById(R.id.btn_calibration)
        historyContainer = findViewById(R.id.history_container)
        totalStatsCard = findViewById(R.id.total_stats_card)
        totalStatsText = findViewById(R.id.total_stats_text)

        fuelPriceEdit.setText(
            prefs.getFloat(KEY_FUEL_PRICE, 30f).let {
                if (it % 1f == 0f) it.toInt().toString() else it.toString()
            }
        )
        fuelPriceEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveFuelPrice()
        }

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        calBtn.setOnClickListener { showCalibrationDialog() }
        calBtn.text = getString(
            if (FuelCalibration.startOdo(this) != null) {
                R.string.trip_cal_active
            } else {
                R.string.trip_calibration
            }
        )
        recordBtn.setOnClickListener {
            if (recorder.isRecording()) stopRecording() else startRecording()
        }
        renderRecordState()
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
        refreshLive()
        refreshHandler.post(refreshTick)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshTick)
        super.onPause()
    }

    override fun onDestroy() {
        if (recorder.isRecording()) recorder.stop()
        super.onDestroy()
    }

    private fun startRecording() {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_LONG).show()
        }
        requestLocationIfNeeded()
        recorder.start()
        renderRecordState()
    }

    private fun requestLocationIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQ_LOCATION,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.firstOrNull()
            == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.trip_gps_on, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        val summary = recorder.stop()
        renderRecordState()
        if (summary != null) {
            Toast.makeText(
                this,
                getString(R.string.trip_saved, formatDuration(summary.durationSec), summary.distanceKm),
                Toast.LENGTH_LONG,
            ).show()
            renderHistory()
        } else {
            Toast.makeText(this, R.string.trip_no_data, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderRecordState() {
        val recording = recorder.isRecording()
        recordBtn.setText(
            if (recording) R.string.trip_record_stop else R.string.trip_record_start
        )
        recordBtn.setBackgroundResource(
            if (recording) R.drawable.bg_button else R.drawable.bg_button_accent
        )
        recordBtn.setTextColor(
            getColor(if (recording) R.color.primary else android.R.color.white)
        )
        statusText.setText(
            if (recording) R.string.trip_recording else R.string.trip_idle
        )
        statusText.setTextColor(
            getColor(if (recording) R.color.success else R.color.text_secondary)
        )
    }

    private fun refreshLive() {
        val live = recorder.liveState()
        if (live == null) {
            liveCard.visibility = View.GONE
            return
        }
        liveCard.visibility = View.VISIBLE
        liveDuration.text = formatDuration(live.durationSec)
        liveDistance.text = String.format(Locale.US, "%.1f", live.distanceKm)
        liveMaxSpeed.text = String.format(Locale.US, "%d", live.maxSpeed)
        liveAvgSpeed.text = String.format(Locale.US, "%.0f", live.avgSpeedKmh)
        liveFuelRate.text = getString(
            R.string.trip_fuel_unit, String.format(Locale.US, "%.1f", fuelConsumption(live))
        )
        liveFuelTotal.text = getString(
            R.string.trip_fuel_liter,
            String.format(Locale.US, "%.2f", live.totalFuelL * FuelCalibration.factor(this)),
        )
        liveFuelDynamic.text = String.format(Locale.US, "%.2f", live.litersDynamic)
        liveFuelStatic.text = String.format(Locale.US, "%.2f", live.litersStatic)
        liveIdleTime.text = formatDuration(live.idleTimeSec)
        liveFuelCost.text = getString(
            R.string.trip_fuel_cost_value,
            live.totalFuelL * FuelCalibration.factor(this) * currentFuelPrice(),
        )
    }

    /** 平均油耗（L/100km）：有實測油量時以 油量/里程 換算並套用校正係數，否則退回速度查表 */
    private fun fuelConsumption(trip: TripRecorder.TripSummary): Double {
        if (trip.distanceKm > 0.0 && trip.totalFuelL > 0.0) {
            return trip.totalFuelL * FuelCalibration.factor(this) / trip.distanceKm * 100.0
        }
        return recorder.fuelRate(trip.avgSpeedKmh)
    }

    private fun currentFuelPrice(): Float =
        fuelPriceEdit.text.toString().toFloatOrNull()
            ?: prefs.getFloat(KEY_FUEL_PRICE, 30f)

    private fun saveFuelPrice() {
        val price = fuelPriceEdit.text.toString().toFloatOrNull() ?: return
        if (price > 0f) {
            prefs.edit().putFloat(KEY_FUEL_PRICE, price).apply()
        }
    }

    private fun showCalibrationDialog() {
        if (FuelCalibration.startOdo(this) == null) {
            showBeginCalibration()
        } else {
            showFinishCalibration()
        }
    }

    private fun showBeginCalibration() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.trip_cal_odo_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.trip_cal_begin_title)
            .setMessage(R.string.trip_cal_begin_msg)
            .setView(box)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                val odo = input.text.toString().toFloatOrNull()
                if (odo != null && odo > 0f) {
                    FuelCalibration.begin(this, odo)
                    calBtn.text = getString(R.string.trip_cal_active)
                    Toast.makeText(
                        this,
                        getString(R.string.trip_cal_begin_done, "%.1f".format(odo)),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(this, R.string.trip_cal_invalid, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showFinishCalibration() {
        val odoInput = android.widget.EditText(this).apply {
            hint = getString(R.string.trip_cal_odo_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
        }
        val fuelInput = android.widget.EditText(this).apply {
            hint = getString(R.string.trip_cal_fuel_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(odoInput)
            addView(fuelInput)
        }
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.trip_cal_finish_title)
            .setMessage(R.string.trip_cal_finish_msg)
            .setView(box)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                val odo = odoInput.text.toString().toFloatOrNull()
                val fuel = fuelInput.text.toString().toFloatOrNull()
                if (odo != null && fuel != null && odo > 0f && fuel > 0f) {
                    val estFuel = recorder.loadTrips()
                        .filter { it.startTime >= FuelCalibration.startTime(this) }
                        .sumOf { it.totalFuelL }
                    val result = FuelCalibration.finish(this, odo, fuel, estFuel)
                    if (result == null) {
                        Toast.makeText(this, R.string.trip_cal_invalid, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    calBtn.text = getString(R.string.trip_calibration)
                    val msg = if (result.estL100 > 0.0) {
                        getString(
                            R.string.trip_cal_result,
                            "%.1f".format(result.actualL100),
                            "%.1f".format(result.estL100),
                            "%.2f".format(result.factor),
                        )
                    } else {
                        getString(
                            R.string.trip_cal_actual_only, "%.1f".format(result.actualL100)
                        )
                    }
                    AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                        .setTitle(R.string.trip_cal_finish_title)
                        .setMessage(msg)
                        .setPositiveButton(R.string.common_ok, null)
                        .show()
                    renderTotalStats(recorder.loadTrips())
                    renderHistory()
                } else {
                    Toast.makeText(this, R.string.trip_cal_invalid, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .setNeutralButton(R.string.trip_cal_cancel) { _, _ ->
                FuelCalibration.cancel(this)
                calBtn.text = getString(R.string.trip_calibration)
                Toast.makeText(this, R.string.trip_cal_cancel, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun renderHistory() {
        historyContainer.removeAllViews()
        val trips = recorder.loadTrips()
        renderTotalStats(trips)
        if (trips.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.trip_empty)
            empty.textSize = 14f
            empty.setTextColor(getColor(R.color.text_secondary))
            empty.setPadding(0, dp(10), 0, 0)
            historyContainer.addView(empty)
            return
        }
        trips.forEach { addTripCard(it) }
    }

    /** 旅程總覽：所有歷史行程的合計統計 */
    private fun renderTotalStats(trips: List<TripRecorder.TripSummary>) {
        if (trips.isEmpty()) {
            totalStatsCard.visibility = View.GONE
            return
        }
        totalStatsCard.visibility = View.VISIBLE
        val totalDistance = trips.sumOf { it.distanceKm }
        val totalDuration = trips.sumOf { it.durationSec }
        val totalFuel = trips.sumOf { it.totalFuelL * FuelCalibration.factor(this) }
        val totalCost = trips.sumOf { it.totalFuelL * FuelCalibration.factor(this) * currentFuelPrice() }
        totalStatsText.text = buildString {
            append(resources.getQuantityString(R.plurals.trip_total_count, trips.size, trips.size)).append('\n')
            append(getString(R.string.trip_total_distance, totalDistance)).append('\n')
            append(getString(R.string.trip_total_duration, formatDuration(totalDuration)))
            if (totalFuel > 0.0) {
                append('\n').append(getString(R.string.trip_total_fuel, totalFuel))
                append('\n').append(getString(R.string.trip_total_cost, totalCost))
            }
        }
    }

    private fun addTripCard(trip: TripRecorder.TripSummary) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        card.setBackgroundResource(R.drawable.bg_card)
        card.isClickable = true
        card.isFocusable = true

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(8)
        card.layoutParams = lp

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = android.view.Gravity.CENTER_VERTICAL

        val time = TextView(this)
        time.text = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            .format(Date(trip.startTime))
        time.textSize = 15f
        time.setTypeface(time.typeface, Typeface.BOLD)
        time.setTextColor(getColor(R.color.text_primary))
        time.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        head.addView(time)

        val del = TextView(this)
        del.text = getString(R.string.common_delete)
        del.textSize = 13f
        del.setTextColor(getColor(R.color.danger))
        del.setPadding(dp(10), dp(4), dp(4), dp(4))
        head.addView(del)
        card.addView(head)

        val info = TextView(this)
        info.text = getString(
            R.string.trip_card_info,
            formatDuration(trip.durationSec),
            "%.1f".format(trip.distanceKm),
            trip.maxSpeed,
        )
        info.textSize = 13f
        info.setTextColor(getColor(R.color.text_secondary))
        info.setPadding(0, dp(6), 0, 0)
        card.addView(info)

        if (trip.totalFuelL > 0) {
            val fuel = TextView(this)
            fuel.text = getString(R.string.trip_fuel_liter, "%.2f".format(trip.totalFuelL))
            fuel.textSize = 13f
            fuel.setTextColor(getColor(R.color.text_secondary))
            fuel.setPadding(0, dp(2), 0, 0)
            card.addView(fuel)

            if (trip.litersStatic > 0.0 || trip.litersDynamic > 0.0) {
                val split = TextView(this)
                split.text = getString(
                    R.string.trip_fuel_split, trip.litersDynamic, trip.litersStatic
                )
                split.textSize = 13f
                split.setTextColor(getColor(R.color.text_secondary))
                split.setPadding(0, dp(2), 0, 0)
                card.addView(split)
            }
        }

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(0, dp(8), 0, 0)
        card.addView(actions)

        val btnTrack = TextView(this)
        btnTrack.text = getString(R.string.trip_track)
        btnTrack.textSize = 13f
        btnTrack.setTextColor(getColor(R.color.primary))
        btnTrack.setPadding(0, dp(4), dp(12), dp(4))
        actions.addView(btnTrack)

        val btnChart = TextView(this)
        btnChart.text = getString(R.string.trip_chart)
        btnChart.textSize = 13f
        btnChart.setTextColor(getColor(R.color.primary))
        btnChart.setPadding(0, dp(4), dp(12), dp(4))
        actions.addView(btnChart)

        val btnCsv = TextView(this)
        btnCsv.text = getString(R.string.trip_export_csv)
        btnCsv.textSize = 13f
        btnCsv.setTextColor(getColor(R.color.primary))
        btnCsv.setPadding(0, dp(4), dp(4), dp(4))
        actions.addView(btnCsv)

        btnTrack.setOnClickListener { showTrack(trip) }
        btnChart.setOnClickListener { showChart(trip) }
        btnCsv.setOnClickListener { exportCsv(trip) }

        card.setOnClickListener {
            AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                .setTitle(getString(R.string.trip_detail, time.text))
                .setMessage(detailText(trip))
                .setPositiveButton(R.string.common_ok, null)
                .show()
        }
        del.setOnClickListener {
            AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                .setMessage(R.string.trip_confirm_delete)
                .setPositiveButton(R.string.common_delete) { _, _ ->
                    recorder.deleteTrip(trip.id)
                    renderHistory()
                    Toast.makeText(this, R.string.trip_deleted, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }

        historyContainer.addView(card)
    }

    private fun detailText(trip: TripRecorder.TripSummary): String = buildString {
        append(getString(R.string.trip_duration)).append("：")
            .append(formatDuration(trip.durationSec)).append('\n')
        append(getString(R.string.trip_distance)).append("：")
            .append("%.1f".format(trip.distanceKm)).append(getString(R.string.trip_km)).append('\n')
        append(getString(R.string.trip_max_speed)).append("：")
            .append(trip.maxSpeed).append(getString(R.string.common_unit_kmh)).append('\n')
        append(getString(R.string.trip_avg_speed)).append("：")
            .append("%.0f".format(trip.avgSpeedKmh)).append(getString(R.string.common_unit_kmh)).append('\n')
        append(getString(R.string.trip_max_rpm)).append("：").append(trip.maxRpm).append('\n')
        append(getString(R.string.trip_avg_rpm)).append("：").append("%.0f".format(trip.avgRpm)).append('\n')
        append(getString(R.string.trip_avg_coolant)).append("：")
            .append("%.0f".format(trip.avgCoolant)).append(getString(R.string.common_unit_celsius)).append('\n')
        if (trip.idleTimeSec > 0) {
            append(getString(R.string.trip_idle_time)).append("：")
                .append(formatDuration(trip.idleTimeSec)).append('\n')
        }
        if (trip.totalFuelL > 0) {
            append(getString(R.string.trip_fuel_total)).append("：")
                .append("%.2f".format(trip.totalFuelL * FuelCalibration.factor(this@TripActivity))).append(" L\n")
        }
        if (trip.litersDynamic > 0.0 || trip.litersStatic > 0.0) {
            append(getString(R.string.trip_fuel_dynamic)).append("：")
                .append("%.2f".format(trip.litersDynamic)).append(" L\n")
            append(getString(R.string.trip_fuel_static)).append("：")
                .append("%.2f".format(trip.litersStatic)).append(" L\n")
            append(getString(R.string.trip_fuel_cost_label)).append("（")
                .append(getString(R.string.trip_fuel_price_per_l, currentFuelPrice())).append("）：")
                .append(getString(
                    R.string.trip_fuel_cost_value,
                    trip.totalFuelL * FuelCalibration.factor(this@TripActivity) * currentFuelPrice(),
                )).append('\n')
        }
        append(getString(R.string.trip_samples)).append("：").append(trip.samples)
    }

    private fun showChart(trip: TripRecorder.TripSummary) {
        val samples = recorder.loadSamples(trip.id)
        if (samples.isEmpty()) {
            Toast.makeText(this, R.string.trip_no_samples, Toast.LENGTH_SHORT).show()
            return
        }
        val chart = TripChartView(this).apply {
            setSeries(CHART_SERIES)
            setSamples(samples.map { s ->
                linkedMapOf<String, Float>().apply {
                    put("RPM", s.rpm.toFloat())
                    put("km/h", s.speed.toFloat())
                    put("°C", s.coolant.toFloat())
                    s.voltage?.let { put("V", it) }
                    s.afr?.let { put("AFR", it) }
                    s.fuelTrim?.let { put("FT%", it) }
                }
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)
            )
        }
        val title = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            .format(Date(trip.startTime))
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(getString(R.string.trip_chart) + "｜" + title)
            .setView(chart)
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }

    private fun showTrack(trip: TripRecorder.TripSummary) {
        val samples = recorder.loadSamples(trip.id)
        if (samples.none { it.lat != 0.0 || it.lng != 0.0 }) {
            Toast.makeText(this, R.string.trip_no_track, Toast.LENGTH_SHORT).show()
            return
        }
        val track = TripTrackView(this).apply {
            setSamples(samples)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)
            )
        }
        val title = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            .format(Date(trip.startTime))
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(getString(R.string.trip_track) + "｜" + title)
            .setView(track)
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }

    private fun exportCsv(trip: TripRecorder.TripSummary) {
        val file = recorder.exportCsv(trip.id)
        if (file == null) {
            Toast.makeText(this, R.string.trip_no_samples, Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.trip_export_csv)))
        Toast.makeText(this, getString(R.string.trip_exported, file.name), Toast.LENGTH_SHORT).show()
    }

    private fun formatDuration(totalSec: Int): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            getString(R.string.trip_duration_hms, h, m, s)
        } else {
            getString(R.string.trip_duration_ms, m, s)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_LOCATION = 200
        private const val KEY_FUEL_PRICE = "fuel_price"

        /** 行程曲線檢視序列（每序列自訂範圍，支援負值如 Fuel Trim） */
        private val CHART_SERIES = listOf(
            TripChartView.Series("RPM", 0f, 12000f, Color.rgb(0, 180, 216)),
            TripChartView.Series("km/h", 0f, 200f, Color.rgb(16, 185, 129)),
            TripChartView.Series("°C", 0f, 140f, Color.rgb(239, 68, 68)),
            TripChartView.Series("AFR", 10f, 20f, Color.rgb(168, 85, 247)),
            TripChartView.Series("FT%", -25f, 25f, Color.rgb(6, 182, 212)),
            TripChartView.Series("V", 10f, 16f, Color.rgb(245, 158, 11)),
        )
    }
}

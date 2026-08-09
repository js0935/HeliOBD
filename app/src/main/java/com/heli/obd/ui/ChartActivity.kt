package com.heli.obd.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.pid.PidStore

/**
 * 歷史數據重疊圖：以 ObdManager 的歷史 ring buffer 繪製多 PID 折線（含自訂 PID），
 * 每系列各自自動縮放、依單位制換算，可勾選要繪製的訊號。
 */
class ChartActivity : AppCompatActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var chartView: DataChartView
    private val titleOf = mutableMapOf<String, String>()
    private val colorOf = mutableMapOf<String, Int>()
    private var selectedKeys = linkedSetOf<String>()

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            updateChart()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chart)

        chartView = findViewById(R.id.chart_view)
        (MonitorTiles.builtin(this) + MonitorTiles.custom(PidStore(this).load())).forEach {
            titleOf[it.key] = it.title
            colorOf[it.key] = it.color
        }
        selectedKeys = obd.historyKeys().take(MAX_SERIES).toCollection(linkedSetOf())

        findViewById<Button>(R.id.btn_chart_select).setOnClickListener { showSelectDialog() }
        handler.post(refreshTick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTick)
        super.onDestroy()
    }

    private fun updateChart() {
        val unitSystem = UnitSystem.load(this)
        val available = obd.historyKeys()
        val series = selectedKeys.filter { it in available }.map { key ->
            DataChartView.Series(
                name = titleOf[key] ?: key,
                color = colorOf[key] ?: DataChartView.PALETTE[0],
                points = obd.historySeries(key).map { MonitorTiles.convert(key, unitSystem, it) },
            )
        }
        chartView.setSeries(series)
    }

    private fun showSelectDialog() {
        val available = obd.historyKeys().toList()
        if (available.isEmpty()) {
            Toast.makeText(this, R.string.chart_no_data, Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(available.size) { available[it] in selectedKeys }
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.chart_select_signals)
            .setMultiChoiceItems(available.map { titleOf[it] ?: it }.toTypedArray(), checked) { _, i, c ->
                checked[i] = c
            }
            .setPositiveButton(R.string.common_ok) { _, _ ->
                selectedKeys = available.indices
                    .filter { checked[it] }
                    .map { available[it] }
                    .take(MAX_SERIES)
                    .toCollection(linkedSetOf())
                updateChart()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    companion object {
        private const val REFRESH_MS = 1000L
        private const val MAX_SERIES = 6
    }
}

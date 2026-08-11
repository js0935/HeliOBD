/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.heli.obd.elm.AlertMonitor
import com.heli.obd.elm.DemoConfig
import com.heli.obd.elm.ObdManager
import com.heli.obd.ui.AccelerationActivity
import com.heli.obd.ui.AiDiagnoseActivity
import com.heli.obd.ui.AlertsActivity
import com.heli.obd.ui.CompareActivity
import com.heli.obd.ui.CustomPidActivity
import com.heli.obd.ui.DataLoggerActivity
import com.heli.obd.ui.DtcActivity
import com.heli.obd.ui.DrivingScoreActivity
import com.heli.obd.ui.DynoActivity
import com.heli.obd.ui.EcuScanActivity
import com.heli.obd.ui.EngineSoundActivity
import com.heli.obd.ui.FeaturePlaceholderActivity
import com.heli.obd.ui.HudActivity
import com.heli.obd.ui.HealthCheckActivity
import com.heli.obd.ui.LiveFuelActivity
import com.heli.obd.ui.MaintenanceActivity
import com.heli.obd.ui.O2EvapActivity
import com.heli.obd.ui.ObdMonitorActivity
import com.heli.obd.ui.RealtimeChartActivity
import com.heli.obd.ui.SettingsActivity
import com.heli.obd.ui.SkidPadActivity
import com.heli.obd.ui.SmogCheckActivity
import com.heli.obd.ui.StageTestActivity
import com.heli.obd.ui.TerminalActivity
import com.heli.obd.ui.TripActivity
import com.heli.obd.ui.VehicleReportActivity
import com.heli.obd.ui.VehiclesActivity
import com.heli.obd.ui.VwtpSensorsActivity

/**
 * 主畫面：HeliOBD 功能入口。
 *
 * 即時數據、故障碼為 OBD 基礎功能；六個 PLUS 功能本體逐版完善中。
 */
class MainActivity : BaseActivity() {

    private lateinit var obdStatusText: TextView

    /** 入口定義 */
    private data class Entry(val icon: Int, val titleRes: Int, val descRes: Int)

    private val entries = listOf(
        Entry(R.drawable.ic_obd, R.string.nav_obd, R.string.obd_desc),
        Entry(R.drawable.ic_dtc, R.string.nav_dtc, R.string.dtc_desc),
        Entry(R.drawable.ic_obd, R.string.feat_ecu_scan, R.string.feat_desc_ecu_scan),
        Entry(R.drawable.ic_alert, R.string.feat_o2_evap, R.string.feat_desc_o2_evap),
        Entry(R.drawable.ic_pid, R.string.feat_stage_test, R.string.feat_desc_stage_test),
        Entry(R.drawable.ic_sound, R.string.feat_engine_sound, R.string.feat_desc_engine_sound),
        Entry(R.drawable.ic_ai, R.string.feat_ai_diag, R.string.feat_desc_ai_diag),
        Entry(R.drawable.ic_trip, R.string.feat_trip_review, R.string.feat_desc_trip_review),
        Entry(R.drawable.ic_compare, R.string.feat_data_compare, R.string.feat_desc_data_compare),
    Entry(R.drawable.ic_multi, R.string.feat_multi_car, R.string.feat_desc_multi_car),
    Entry(R.drawable.ic_hud, R.string.feat_hud, R.string.feat_desc_hud),
        Entry(R.drawable.ic_alert, R.string.feat_alerts, R.string.feat_desc_alerts),
        Entry(R.drawable.ic_pid, R.string.feat_custom_pid, R.string.feat_desc_custom_pid),
        Entry(R.drawable.ic_pid, R.string.feat_vwtp, R.string.feat_desc_vwtp),
        Entry(R.drawable.ic_accel, R.string.feat_accel, R.string.feat_desc_accel),
    Entry(R.drawable.ic_chart, R.string.feat_dyno, R.string.feat_desc_dyno),
    Entry(R.drawable.ic_fuel, R.string.feat_live_fuel, R.string.feat_desc_live_fuel),
    Entry(R.drawable.ic_chart, R.string.feat_data_logger, R.string.feat_desc_data_logger),
        Entry(R.drawable.ic_accel, R.string.feat_skid_pad, R.string.feat_desc_skid_pad),
        Entry(R.drawable.ic_chart, R.string.feat_chart, R.string.feat_desc_chart),
        Entry(R.drawable.ic_fuel, R.string.feat_fuel, R.string.feat_desc_fuel),
        Entry(R.drawable.ic_score, R.string.feat_score, R.string.feat_desc_score),
        Entry(R.drawable.ic_maintenance, R.string.feat_maintenance, R.string.feat_desc_maintenance),
        Entry(R.drawable.ic_alert, R.string.feat_health_check, R.string.feat_desc_health_check),
        Entry(R.drawable.ic_alert, R.string.feat_smog_check, R.string.feat_desc_smog_check),
        Entry(R.drawable.ic_trip, R.string.feat_report, R.string.feat_desc_report),
        Entry(R.drawable.ic_demo, R.string.feat_demo, R.string.feat_desc_demo),
        Entry(R.drawable.ic_obd, R.string.feat_connection, R.string.feat_desc_connection),
        Entry(R.drawable.ic_pid, R.string.feat_terminal, R.string.feat_desc_terminal),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        obdStatusText = findViewById(R.id.obd_status)

        // 模擬模式預設關閉：每次啟動一律重置為關閉，不自動恢復上次的 Demo 狀態
        DemoConfig.setEnabled(this, false)
        ObdManagerHolder.obd(this).setDemoMode(false)
        AlertMonitor.attach(ObdManagerHolder.obd(this), applicationContext)

        buildFeatureGrid()
        refreshStatus()
    }

    override fun onDestroy() {
        AlertMonitor.detach(ObdManagerHolder.obd(this))
        AlertMonitor.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildFeatureGrid() {
        val container = findViewById<LinearLayout>(R.id.feature_container)
        val inflater = LayoutInflater.from(this)
        val rows = entries.chunked(3)

        rows.forEachIndexed { rowIndex, rowEntries ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowEntries.forEachIndexed { index, entry ->
                val item = inflater.inflate(R.layout.item_feature, row, false)
                item.findViewById<ImageView>(R.id.feature_icon).setImageResource(entry.icon)
                item.findViewById<TextView>(R.id.feature_title).setText(entry.titleRes)
                item.findViewById<TextView>(R.id.feature_desc).setText(entry.descRes)
                item.setOnClickListener { onEntryClick(entry) }

                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (index < rowEntries.lastIndex) lp.marginEnd = dp(12)
                row.addView(item, lp)
            }
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (rowIndex < rows.lastIndex) rowLp.bottomMargin = dp(12)
            container.addView(row, rowLp)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun onEntryClick(entry: Entry) {
        if (entry.titleRes == R.string.feat_demo) {
            toggleDemoMode()
            return
        }
        val target: Class<*> = when (entry.titleRes) {
            R.string.nav_obd -> ObdMonitorActivity::class.java
            R.string.nav_dtc -> DtcActivity::class.java
            R.string.feat_ecu_scan -> EcuScanActivity::class.java
            R.string.feat_o2_evap -> O2EvapActivity::class.java
            R.string.feat_stage_test -> StageTestActivity::class.java
            R.string.feat_engine_sound -> EngineSoundActivity::class.java
            R.string.feat_ai_diag -> AiDiagnoseActivity::class.java
            R.string.feat_trip_review -> TripActivity::class.java
            R.string.feat_data_compare -> CompareActivity::class.java
            R.string.feat_multi_car -> VehiclesActivity::class.java
            R.string.feat_hud -> HudActivity::class.java
            R.string.feat_alerts -> AlertsActivity::class.java
            R.string.feat_custom_pid -> CustomPidActivity::class.java
            R.string.feat_vwtp -> VwtpSensorsActivity::class.java
            R.string.feat_accel -> AccelerationActivity::class.java
    R.string.feat_dyno -> DynoActivity::class.java
    R.string.feat_live_fuel -> LiveFuelActivity::class.java
    R.string.feat_data_logger -> DataLoggerActivity::class.java
            R.string.feat_skid_pad -> SkidPadActivity::class.java
            R.string.feat_chart -> RealtimeChartActivity::class.java
            R.string.feat_fuel -> TripActivity::class.java
            R.string.feat_score -> DrivingScoreActivity::class.java
            R.string.feat_maintenance -> MaintenanceActivity::class.java
            R.string.feat_health_check -> HealthCheckActivity::class.java
            R.string.feat_smog_check -> SmogCheckActivity::class.java
            R.string.feat_report -> VehicleReportActivity::class.java
            R.string.feat_connection -> SettingsActivity::class.java
            R.string.feat_terminal -> TerminalActivity::class.java
            else -> FeaturePlaceholderActivity::class.java
        }
        startActivity(
            Intent(this, target).apply {
                if (target == FeaturePlaceholderActivity::class.java) {
                    putExtra(FeaturePlaceholderActivity.EXTRA_ICON, entry.icon)
                    putExtra(FeaturePlaceholderActivity.EXTRA_TITLE, entry.titleRes)
                    putExtra(FeaturePlaceholderActivity.EXTRA_DESC, entry.descRes)
                }
            }
        )
    }

    private fun toggleDemoMode() {
        val enabled = !DemoConfig.isEnabled(this)
        DemoConfig.setEnabled(this, enabled)
        ObdManagerHolder.obd(this).setDemoMode(enabled)
        refreshStatus()
        Toast.makeText(
            this,
            getString(if (enabled) R.string.demo_on else R.string.demo_off),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun refreshStatus() {
        val obd = ObdManagerHolder.obd(this)
        when {
            obd.isDemoMode() -> {
                obdStatusText.setText(R.string.demo_status_on)
                obdStatusText.setTextColor(getColor(R.color.accent))
            }
            obd.isConnected() -> {
                obdStatusText.setText(R.string.obd_connected)
                obdStatusText.setTextColor(getColor(R.color.success))
            }
            else -> {
                obdStatusText.setText(R.string.obd_disconnected)
                obdStatusText.setTextColor(getColor(R.color.text_secondary))
            }
        }
    }

    /** ObdManager 全域單例（避免各頁面重複連線） */
    object ObdManagerHolder {
        @Volatile
        private var instance: ObdManager? = null
        fun obd(context: android.content.Context): ObdManager =
            instance ?: synchronized(this) {
                instance ?: ObdManager(context.applicationContext).also { instance = it }
            }
    }
}

/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.heli.obd.elm.AlertMonitor
import com.heli.obd.elm.DemoConfig
import com.heli.obd.elm.ObdManager
import com.heli.obd.update.UpdateCheckWorker
import com.heli.obd.update.UpdateChecker
import com.heli.obd.ui.AccelerationActivity
import com.heli.obd.ui.AiDiagnoseActivity
import com.heli.obd.ui.AlertsActivity
import com.heli.obd.ui.CompareActivity
import com.heli.obd.ui.ConnectionDiagActivity
import com.heli.obd.ui.ProDiagActivity
import com.heli.obd.ui.CustomPidActivity
import com.heli.obd.ui.DataLoggerActivity
import com.heli.obd.ui.DtcActivity
import com.heli.obd.ui.FreezeFrameActivity
import com.heli.obd.ui.VehicleInfoActivity
import com.heli.obd.ui.DrivingScoreActivity
import com.heli.obd.ui.DynoActivity
import com.heli.obd.ui.EcuScanActivity
import com.heli.obd.ui.EngineSoundActivity
import com.heli.obd.ui.FeaturePlaceholderActivity
import com.heli.obd.ui.HudActivity
import com.heli.obd.ui.HealthCheckActivity
import com.heli.obd.ui.LiveFuelActivity
import com.heli.obd.ui.LeafSoHActivity
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
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 主畫面：HeliOBD 功能入口。
 *
 * 即時數據、故障碼為 OBD 基礎功能；六個 PLUS 功能本體逐版完善中。
 */
class MainActivity : BaseActivity() {

    private lateinit var obdStatusText: TextView
    private lateinit var statusPill: LinearLayout
    private lateinit var statusDot: View
    private lateinit var dashboardStatus: TextView
    private lateinit var dashboardGrid: LinearLayout
    private lateinit var dashboardHint: TextView

    private val obd get() = ObdManagerHolder.obd(this)

    /** 即時概覽卡的四個指標 */
    private enum class DashMetric { RPM, COOLANT, VOLTAGE, LOAD }

    private val dashboardValues = mutableMapOf<DashMetric, TextView>()

    /** 入口定義 */
    private data class Entry(val icon: Int, val titleRes: Int, val descRes: Int)

    private val entries = listOf(
        Entry(R.drawable.ic_obd, R.string.nav_obd, R.string.obd_desc),
        Entry(R.drawable.ic_dtc, R.string.nav_dtc, R.string.dtc_desc),
        Entry(R.drawable.ic_dtc, R.string.feat_freeze_frame, R.string.feat_desc_freeze_frame),
        Entry(R.drawable.ic_obd, R.string.feat_vehicle_info, R.string.feat_desc_vehicle_info),
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
        Entry(R.drawable.ic_pid, R.string.feat_leaf_soh, R.string.feat_desc_leaf_soh),
        Entry(R.drawable.ic_alert, R.string.feat_health_check, R.string.feat_desc_health_check),
        Entry(R.drawable.ic_alert, R.string.feat_smog_check, R.string.feat_desc_smog_check),
        Entry(R.drawable.ic_trip, R.string.feat_report, R.string.feat_desc_report),
        Entry(R.drawable.ic_obd, R.string.conn_diag_title, R.string.conn_diag_desc),
        Entry(R.drawable.ic_obd, R.string.feat_diag_pro, R.string.feat_desc_diag_pro),
        Entry(R.drawable.ic_demo, R.string.feat_demo, R.string.feat_desc_demo),
        Entry(R.drawable.ic_obd, R.string.feat_connection, R.string.feat_desc_connection),
        Entry(R.drawable.ic_pid, R.string.feat_terminal, R.string.feat_desc_terminal),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        obdStatusText = findViewById(R.id.obd_status)
        statusPill = findViewById(R.id.status_pill)
        statusDot = findViewById(R.id.status_dot)
        dashboardStatus = findViewById(R.id.dashboard_status)
        dashboardGrid = findViewById(R.id.dashboard_grid)
        dashboardHint = findViewById(R.id.dashboard_hint)

        // 模擬模式預設關閉：每次啟動一律重置為關閉，不自動恢復上次的 Demo 狀態
        DemoConfig.setEnabled(this, false)
        ObdManagerHolder.obd(this).setDemoMode(false)
        AlertMonitor.attach(ObdManagerHolder.obd(this), applicationContext)

        buildDashboardCard()
        buildFeatureGrid()
        refreshStatus()
        UpdateCheckWorker.scheduleDaily(this)
        handleUpdateFlow()
    }

    override fun onPause() {
        obd.removeListener(dashboardListener)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        obd.addListener(dashboardListener)
        refreshStatus()
        renderDashboard(obd.state, obd.latestLiveData)
    }

    override fun onDestroy() {
        val obd = ObdManagerHolder.obd(this)
        AlertMonitor.detach(obd)
        AlertMonitor.release()
        // 僅使用者真正離開（非旋轉/回收）且仍連線時提醒拔除轉接器，避免耗損電瓶
        if (isFinishing && obd.isConnected() && !obd.isDemoMode()) {
            Toast.makeText(applicationContext, R.string.obd_remember_unplug, Toast.LENGTH_LONG).show()
        }
        super.onDestroy()
    }

    /** 功能分類（主畫面區塊標題） */
    private enum class Category(val titleRes: Int) {
        DIAGNOSIS(R.string.cat_diagnosis),
        VEHICLE_CHECK(R.string.cat_vehicle_check),
        PERFORMANCE(R.string.cat_performance),
        TRIP_DATA(R.string.cat_trip_data),
        VEHICLE_MGMT(R.string.cat_vehicle_mgmt),
        SYSTEM(R.string.cat_system),
    }

    private fun buildFeatureGrid() {
        val container = findViewById<LinearLayout>(R.id.feature_container)
        val inflater = LayoutInflater.from(this)
        // 依分類分組，每區塊前加標題；標題可點擊收合/展開，狀態記憶於 SharedPreferences
        entries.groupBy { categoryOf(it.titleRes) }.forEach { (category, items) ->
            val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val arrow = TextView(this).apply {
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
            }
            val collapsed = mainUiPrefs.getBoolean(catCollapseKey(category), false)
            if (collapsed) {
                grid.visibility = View.GONE
                arrow.text = "▸"
            } else {
                arrow.text = "▾"
            }
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(0, dp(12), 0, dp(4))
                setOnClickListener {
                    val willCollapse = grid.visibility == View.VISIBLE
                    grid.visibility = if (willCollapse) View.GONE else View.VISIBLE
                    arrow.text = if (willCollapse) "▸" else "▾"
                    mainUiPrefs.edit().putBoolean(catCollapseKey(category), willCollapse).apply()
                }
            }
            titleRow.addView(
                TextView(this).apply {
                    text = getString(category.titleRes)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            titleRow.addView(arrow)
            container.addView(titleRow)

            val rows = items.chunked(3)
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
                grid.addView(row, rowLp)
            }
            container.addView(grid)
        }
    }

    private val mainUiPrefs by lazy {
        getSharedPreferences("main_ui", Context.MODE_PRIVATE)
    }

    private fun catCollapseKey(category: Category): String = "cat_collapsed_${category.name}"

    // ===== 即時概覽卡 =====

    private val dashboardListener = object : ObdManager.Listener {
        override fun onStateChanged(state: ObdManager.State) {
            renderDashboard(state, obd.latestLiveData)
        }

        override fun onLiveData(data: ObdManager.LiveData) {
            renderDashboard(obd.state, data)
        }
    }

    private fun buildDashboardCard() {
        val values = listOf(
            DashMetric.RPM to R.string.pid_name_rpm,
            DashMetric.COOLANT to R.string.pid_name_coolant,
            DashMetric.VOLTAGE to R.string.pid_name_module_voltage,
            DashMetric.LOAD to R.string.pid_name_load,
        )
        values.chunked(2).forEachIndexed { rowIndex, rowMetrics ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowMetrics.forEachIndexed { index, (metric, labelRes) ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply { if (index == 0) marginEnd = dp(10) }
                }
                cell.addView(
                    TextView(this).apply {
                        text = getString(labelRes)
                        setTextColor(getColor(R.color.text_secondary))
                        textSize = 11f
                    }
                )
                val value = TextView(this).apply {
                    text = "—"
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                }
                dashboardValues[metric] = value
                cell.addView(value)
                row.addView(cell)
            }
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            if (rowIndex == 0) rowLp.bottomMargin = dp(10)
            dashboardGrid.addView(row, rowLp)
        }
        findViewById<View>(R.id.dashboard_card).setOnClickListener {
            startActivity(Intent(this, ObdMonitorActivity::class.java))
        }
    }

    private fun renderDashboard(state: ObdManager.State, data: ObdManager.LiveData?) {
        val ready = state == ObdManager.State.Ready
        dashboardStatus.setText(
            when {
                obd.isDemoMode() -> R.string.dashboard_status_demo
                ready -> R.string.dashboard_status_ready
                else -> R.string.dashboard_status_idle
            }
        )
        dashboardStatus.setTextColor(
            getColor(if (obd.isDemoMode() || ready) R.color.success else R.color.text_secondary)
        )
        dashboardHint.text = getString(
            when {
                obd.isDemoMode() -> R.string.dashboard_hint_demo
                ready -> R.string.dashboard_hint_ready
                else -> R.string.dashboard_hint_idle
            }
        )
        val rpm = data?.rpm
        setDashValue(DashMetric.RPM, if (rpm == null) "—" else "$rpm", rpm != null && rpm > 9000)
        val coolant = data?.coolant
        setDashValue(
            DashMetric.COOLANT,
            if (coolant == null) "—" else "$coolant°",
            coolant != null && coolant > 110,
        )
        val voltage = data?.voltage
        setDashValue(
            DashMetric.VOLTAGE,
            if (voltage == null) "—" else String.format(Locale.US, "%.1f", voltage),
            voltage != null && voltage < 11.5f,
        )
        val load = data?.load
        setDashValue(DashMetric.LOAD, if (load == null) "—" else "$load%", false)
    }

    /** 設定概覽值，超限時以紅色警示 */
    private fun setDashValue(metric: DashMetric, text: String, alarm: Boolean) {
        dashboardValues[metric]?.apply {
            this.text = text
            setTextColor(getColor(if (alarm) R.color.danger else R.color.text_primary))
        }
    }

    // ===== 自動更新 =====

    private lateinit var updateStatus: TextView
    private lateinit var updateVersion: TextView
    private lateinit var updateBtn: TextView

    /** 啟動時檢查更新；由每日通知點擊進入時直接下載安裝 */
    private fun handleUpdateFlow() {
        if (intent?.getBooleanExtra(EXTRA_UPDATE_DOWNLOAD, false) == true) {
            downloadAndInstall()
            return
        }
        updateStatus = findViewById(R.id.update_status)
        updateVersion = findViewById(R.id.update_version)
        updateBtn = findViewById(R.id.update_btn)
        checkUpdateNow()
    }

    /** 檢查 GitHub 最新版並更新主畫面更新卡片的顯示狀態 */
    private fun checkUpdateNow() {
        updateStatus.text = getString(R.string.update_ui_checking)
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
            renderUpdateBar(release)
        }
    }

    private fun renderUpdateBar(release: UpdateChecker.Release?) {
        val local = localVersion()
        updateVersion.text = getString(R.string.update_ui_version, local)
        if (release?.apkUrl != null && UpdateChecker.isNewer(local, release.version)) {
            updateStatus.text = getString(R.string.update_ui_available, release.version)
            updateBtn.text = getString(R.string.update_ui_download)
            updateBtn.setOnClickListener { downloadAndInstall() }
        } else if (release != null) {
            updateStatus.text = getString(R.string.update_ui_latest)
            updateBtn.text = getString(R.string.update_ui_check)
            updateBtn.setOnClickListener { checkUpdateNow() }
        } else {
            updateStatus.text = getString(R.string.update_ui_error)
            updateBtn.text = getString(R.string.update_ui_check)
            updateBtn.setOnClickListener { checkUpdateNow() }
        }
    }

    private fun localVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    private fun downloadAndInstall() {
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
            val url = release?.apkUrl
            if (url == null) {
                Toast.makeText(this@MainActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(this@MainActivity, R.string.update_downloading, Toast.LENGTH_SHORT).show()
            val ok = withContext(Dispatchers.IO) { UpdateChecker.download(this@MainActivity, url) }
            if (!ok) {
                Toast.makeText(this@MainActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!UpdateChecker.canInstall(this@MainActivity)) {
                promptEnableInstall()
                return@launch
            }
            if (UpdateChecker.install(this@MainActivity)) {
                Toast.makeText(this@MainActivity, R.string.update_install, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Android 8+ 首次安裝需先允許「安裝未知來源」；引導前往本機設定 */
    private fun promptEnableInstall() {
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_body)
            .setPositiveButton(R.string.update_install_permission_open) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:$packageName".toUri(),
                    )
                )
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    companion object {
        /** 由更新通知點擊帶入：直接執行下載並安裝 */
        const val EXTRA_UPDATE_DOWNLOAD = "extra_update_download"
    }

    /** 依入口標題回傳分類；未列出的功能（Demo/連線設定）歸入系統 */
    private fun categoryOf(titleRes: Int): Category = when (titleRes) {
        R.string.nav_obd, R.string.nav_dtc, R.string.feat_freeze_frame, R.string.feat_ecu_scan,
        R.string.feat_o2_evap, R.string.feat_custom_pid, R.string.feat_diag_pro,
        R.string.feat_terminal -> Category.DIAGNOSIS

        R.string.feat_vehicle_info, R.string.feat_ai_diag, R.string.feat_vwtp,
        R.string.feat_health_check, R.string.feat_smog_check, R.string.conn_diag_title ->
        Category.VEHICLE_CHECK

        R.string.feat_stage_test, R.string.feat_engine_sound, R.string.feat_hud,
        R.string.feat_accel, R.string.feat_dyno, R.string.feat_live_fuel,
        R.string.feat_skid_pad -> Category.PERFORMANCE

        R.string.feat_trip_review, R.string.feat_data_compare, R.string.feat_data_logger,
        R.string.feat_chart, R.string.feat_fuel, R.string.feat_score -> Category.TRIP_DATA

        R.string.feat_multi_car, R.string.feat_alerts, R.string.feat_maintenance,
        R.string.feat_leaf_soh, R.string.feat_report -> Category.VEHICLE_MGMT

        else -> Category.SYSTEM
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
            R.string.feat_freeze_frame -> FreezeFrameActivity::class.java
            R.string.feat_vehicle_info -> VehicleInfoActivity::class.java
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
            R.string.feat_leaf_soh -> LeafSoHActivity::class.java
            R.string.feat_health_check -> HealthCheckActivity::class.java
            R.string.feat_smog_check -> SmogCheckActivity::class.java
            R.string.feat_report -> VehicleReportActivity::class.java
            R.string.conn_diag_title -> ConnectionDiagActivity::class.java
            R.string.feat_diag_pro -> ProDiagActivity::class.java
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
                statusPill.background.setTint(getColor(R.color.pill_demo))
                statusDot.background.setTint(getColor(R.color.accent))
            }
            obd.isConnected() -> {
                obdStatusText.setText(R.string.obd_connected)
                obdStatusText.setTextColor(getColor(R.color.success))
                statusPill.background.setTint(getColor(R.color.pill_connected))
                statusDot.background.setTint(getColor(R.color.success))
            }
            else -> {
                obdStatusText.setText(R.string.obd_disconnected)
                obdStatusText.setTextColor(getColor(R.color.text_secondary))
                statusPill.background.setTint(getColor(R.color.pill_idle))
                statusDot.background.setTint(getColor(R.color.text_secondary))
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

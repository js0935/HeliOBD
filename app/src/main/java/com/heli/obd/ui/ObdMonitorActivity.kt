/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.DragEvent
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.BtPermissions
import com.heli.obd.elm.ObdDecoder
import com.heli.obd.elm.ObdManager
import com.heli.obd.elm.TransportTarget
import com.heli.obd.pid.PidStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.absoluteValue
import java.util.Locale

/**
 * 即時數據畫面：藍牙連線 ELM327 顯示轉速/車速/水溫/電壓 + 馬力/扭力估算；
 * 「自訂」勾選額外數據（內建 10 項 + 自訂 PID）以 tile 網格呈現，
 * 支援單位制切換（公制/英制）、多頁翻頁、長按拖放排序，與歷史數據圖表入口。
 */
class ObdMonitorActivity : BaseActivity(), ObdManager.Listener {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    private lateinit var statusText: TextView
    private lateinit var rpmGauge: GaugeView
    private lateinit var speedGauge: GaugeView
    private lateinit var tempGauge: GaugeView
    private lateinit var voltageGauge: GaugeView
    private lateinit var powerText: TextView
    private lateinit var torqueText: TextView
    private lateinit var fuelTrimText: TextView
    private lateinit var afrText: TextView
    private lateinit var connectBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var customizeBtn: Button
    private lateinit var unitBtn: Button
    private lateinit var chartBtn: Button
    private lateinit var pagePrevBtn: Button
    private lateinit var pageNextBtn: Button
    private lateinit var pageIndicator: TextView
    private lateinit var tileContainer: LinearLayout
    private lateinit var tileScroll: ScrollView
    private val tileViews = mutableMapOf<String, TextView>()
    private var customPids: List<PidStore.CustomPid> = emptyList()
    private var unitSystem = UnitSystem.METRIC
    private var currentPage = 0
    private var enabledKeys = linkedSetOf<String>()
    private lateinit var focusTitle: TextView
    private lateinit var focusButtonRow: LinearLayout
    private lateinit var extendedContainer: LinearLayout
    private lateinit var toggleModeBtn: Button
    private lateinit var focusRpmBtn: Button
    private lateinit var focusSpeedBtn: Button
    private lateinit var focusCoolantBtn: Button
    private lateinit var focusVoltageBtn: Button

    /** 精簡模式目前顯示的單一數據 key（rpm/speed/coolant/voltage） */
    private var focusKey = "rpm"

    /** 精簡模式開關：true = 只讀取並顯示單一數據 */
    private var focusMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_obd_monitor)

        statusText = findViewById(R.id.obd_status_text)
        rpmGauge = findViewById(R.id.gauge_rpm)
        speedGauge = findViewById(R.id.gauge_speed)
        tempGauge = findViewById(R.id.gauge_temp)
        voltageGauge = findViewById(R.id.gauge_voltage)
        powerText = findViewById(R.id.txt_power)
        torqueText = findViewById(R.id.txt_torque)
        fuelTrimText = findViewById(R.id.txt_fuel_trim)
        afrText = findViewById(R.id.txt_afr)
        connectBtn = findViewById(R.id.btn_connect)
        disconnectBtn = findViewById(R.id.btn_disconnect)
        customizeBtn = findViewById(R.id.btn_customize)
        unitBtn = findViewById(R.id.btn_unit)
        chartBtn = findViewById(R.id.btn_chart)
        pagePrevBtn = findViewById(R.id.btn_page_prev)
        pageNextBtn = findViewById(R.id.btn_page_next)
        pageIndicator = findViewById(R.id.txt_page_indicator)
        tileContainer = findViewById(R.id.tile_container)
        tileScroll = findViewById(R.id.tile_scroll)
        focusTitle = findViewById(R.id.obd_focus_title)
        focusButtonRow = findViewById(R.id.focus_button_row)
        extendedContainer = findViewById(R.id.extended_container)
        toggleModeBtn = findViewById(R.id.btn_toggle_mode)
        focusRpmBtn = findViewById(R.id.btn_focus_rpm)
        focusSpeedBtn = findViewById(R.id.btn_focus_speed)
        focusCoolantBtn = findViewById(R.id.btn_focus_coolant)
        focusVoltageBtn = findViewById(R.id.btn_focus_voltage)
        setupGauges()

        connectBtn.setOnClickListener { ensurePermissionAndConnect() }
        disconnectBtn.setOnClickListener {
            obd.disconnect()
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
        }
        customizeBtn.setOnClickListener {
            if (focusMode) toggleMode()
            showCustomizeDialog()
        }
        unitBtn.setOnClickListener { toggleUnit() }
        chartBtn.setOnClickListener { startActivity(Intent(this, ChartActivity::class.java)) }
        pagePrevBtn.setOnClickListener { goToPage(currentPage - 1, 1f) }
        pageNextBtn.setOnClickListener { goToPage(currentPage + 1, -1f) }
        focusRpmBtn.setOnClickListener { switchFocus("rpm") }
        focusSpeedBtn.setOnClickListener { switchFocus("speed") }
        focusCoolantBtn.setOnClickListener { switchFocus("coolant") }
        focusVoltageBtn.setOnClickListener { switchFocus("voltage") }
        toggleModeBtn.setOnClickListener { toggleMode() }
        setupTileDragDrop()
        setupPageSwipe()

        obd.addListener(this)
        renderState(obd.state)
        applyFocusGauge(focusKey)
        autoReconnect()
    }

    override fun onPause() {
        // 離開監控頁時恢復完整輪詢，避免影響其他頁面的 requestLiveData
        obd.setFocusKey(null)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        unitSystem = UnitSystem.load(this)
        customPids = PidStore(this).load()
        obd.setCustomPids(customPids)
        updateUnitBtn()
        renderTiles()
        obd.setFocusKey(if (focusMode) focusKey else null)
        applyFocusGauge(if (focusMode) focusKey else "rpm")
    }

    override fun onDestroy() {
        obd.removeListener(this)
        super.onDestroy()
    }

    // ===== 連線 =====

    private fun ensurePermissionAndConnect() {
        if (!locationEnabledIfNeeded()) return
        if (BtPermissions.hasAll(this)) {
            pickDevice()
        } else {
            requestPermissions(BtPermissions.required() + BtPermissions.storage(), REQ_BT_PERMISSION)
        }
    }

    /** Android 11 以下掃描藍牙需位置服務開啟，關閉時引導使用者開啟並回傳 false */
    private fun locationEnabledIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return true
        val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!enabled) {
            AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                .setTitle(R.string.obd_location_title)
                .setMessage(R.string.obd_location_message)
                .setPositiveButton(R.string.obd_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
        return enabled
    }

    private fun autoReconnect() {
        if (obd.isConnected()) return
        val target = obd.lastTarget() ?: return
        when (target) {
            is TransportTarget.Wifi -> obd.connectTarget(target) { _, _ -> }
            else -> {
                if (!BtPermissions.hasAll(this)) return
                obd.connectTarget(target) { _, _ -> }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_PERMISSION) {
            if (BtPermissions.hasAll(this)) pickDevice()
            else Toast.makeText(this, R.string.obd_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // 權限由本頁於 pickDevice 前申請
    private fun pickDevice() {
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.obd_connection_type_title)
            .setItems(
                arrayOf(
                    getString(R.string.obd_connection_type_classic),
                    getString(R.string.obd_connection_type_ble),
                    getString(R.string.obd_connection_type_wifi),
                )
            ) { _, which ->
                when (which) {
                    0 -> pickClassicDevice()
                    1 -> pickBleDevice()
                    2 -> pickWifiDevice()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    @android.annotation.SuppressLint("MissingPermission") // 權限由使用者於連線選擇前統一申請
    private fun pickClassicDevice() {
        statusText.text = getString(R.string.obd_scanning)
        obd.discover { devices ->
            if (devices.isEmpty()) {
                statusText.text = getString(R.string.obd_no_device)
                Toast.makeText(this, R.string.obd_no_device, Toast.LENGTH_LONG).show()
                return@discover
            }
            val names = devices.map { it.name ?: it.address }
            AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                .setTitle(R.string.obd_select_device)
                .setItems(names.toTypedArray()) { _, which ->
                    connectTo(devices[which])
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // 權限由使用者於連線選擇前統一申請
    private fun pickBleDevice() {
        statusText.text = getString(R.string.obd_scanning)
        obd.discoverBle { devices ->
            if (devices.isEmpty()) {
                statusText.text = getString(R.string.obd_no_device)
                Toast.makeText(this, R.string.obd_no_device, Toast.LENGTH_LONG).show()
                return@discoverBle
            }
            val names = devices.map { it.name ?: it.address }
            AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                .setTitle(R.string.obd_select_device)
                .setItems(names.toTypedArray()) { _, which ->
                    connectToBle(devices[which])
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    private fun pickWifiDevice() {
        val saved = (obd.lastTarget() as? TransportTarget.Wifi)
        val input = EditText(this).apply {
            setText(saved?.let { "${it.host}:${it.port}" } ?: DEFAULT_WIFI_ADDRESS)
            hint = DEFAULT_WIFI_ADDRESS
        }
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.obd_wifi_title)
            .setView(input)
            .setPositiveButton(R.string.common_ok) { _, _ ->
                val (host, port) = parseWifiAddress(input.text.toString())
                if (host == null) {
                    Toast.makeText(this, R.string.obd_wifi_invalid, Toast.LENGTH_LONG).show()
                } else {
                    connectToWifi(host, port)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun connectTo(device: BluetoothDevice) {
        statusText.text = getString(R.string.obd_connecting)
        obd.connect(device) { success, message ->
            if (!success) {
                showConnectGuide(message)
            } else {
                checkSuspiciousAdapter()
            }
        }
    }

    private fun connectToBle(device: BluetoothDevice) {
        statusText.text = getString(R.string.obd_connecting)
        obd.connectBle(device) { success, message ->
            if (!success) {
                showConnectGuide(message)
            } else {
                checkSuspiciousAdapter()
            }
        }
    }

    private fun connectToWifi(host: String, port: Int) {
        statusText.text = getString(R.string.obd_connecting)
        obd.connectWifi(host, port) { success, message ->
            if (!success) {
                showConnectGuide(message)
            } else {
                checkSuspiciousAdapter()
            }
        }
    }

    /** 解析使用者輸入的 `IP:port`；空白 port 或格式錯誤回傳 (null, 0) */
    private fun parseWifiAddress(text: String): Pair<String?, Int> {
        val t = text.trim()
        val host = t.substringBefore(':', missingDelimiterValue = "")
        if (host.isEmpty()) return null to 0
        val portStr = t.substringAfter(':', missingDelimiterValue = DEFAULT_WIFI_PORT.toString())
        val port = portStr.toIntOrNull() ?: return null to 0
        if (port !in 1..65535) return null to 0
        return host to port
    }

    /** 連線失敗引導：依序檢查插頭／點火／其他 App／通訊協定，避免新手卡關 */
    private fun showConnectGuide(message: String?) {
        val detail = message?.let { getString(R.string.obd_connect_failed, it) }
            ?: getString(R.string.obd_init_failed)
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.obd_connect_guide_title)
            .setMessage(getString(R.string.obd_connect_guide_body, detail))
            .setPositiveButton(R.string.obd_connect_guide_retry) { _, _ -> pickDevice() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 連線成功後於背景偵測山寨晶片（ATI 讀取會阻塞，不可在主執行緒執行） */
    private fun checkSuspiciousAdapter() {
        lifecycleScope.launch {
            val suspicious = withContext(Dispatchers.IO) { obd.isSuspiciousAdapter() }
            if (suspicious) {
                Toast.makeText(this@ObdMonitorActivity, R.string.obd_adapter_suspicious, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ===== ObdManager.Listener =====

    override fun onStateChanged(state: ObdManager.State) {
        renderState(state)
    }

    override fun onLiveData(data: ObdManager.LiveData) {
        if (focusMode) {
            updateFocusValue(data)
            return
        }
        data.rpm?.let { rpmGauge.setValue(it.toFloat()) }
        data.speed?.let { speedGauge.setValue(unitSystem.speed(it.toFloat())) }
        data.coolant?.let { tempGauge.setValue(unitSystem.temp(it.toFloat())) }
        data.voltage?.let { voltageGauge.setValue(it) }
        renderPower(data)
        renderFuelTrim(data.fuelTrim)
        renderAfr(data.afr)
        updateTiles(data)
    }

    // ===== 單位制 =====

    private fun toggleUnit() {
        unitSystem = if (unitSystem == UnitSystem.METRIC) UnitSystem.IMPERIAL else UnitSystem.METRIC
        UnitSystem.save(this, unitSystem)
        updateUnitBtn()
        applyUnitToGauges()
        renderTiles()
        if (focusMode) applyFocusGauge(focusKey)
    }

    private fun updateUnitBtn() {
        unitBtn.text = getString(
            if (unitSystem == UnitSystem.METRIC) R.string.monitor_unit_metric else R.string.monitor_unit_imperial
        )
    }

    private fun applyUnitToGauges() {
        val imperial = unitSystem == UnitSystem.IMPERIAL
        speedGauge.setUnit(unitSystem.speedUnit())
        speedGauge.setRange(maxValue = if (imperial) 124f else 200f)
        tempGauge.setUnit(unitSystem.tempUnit())
        tempGauge.setRange(
            maxValue = if (imperial) 284f else 140f,
            redFromValue = if (imperial) 230f else 110f,
        )
    }

    // ===== 自訂顯示 =====

    private fun showCustomizeDialog() {
        val tiles = MonitorTiles.builtin(this) + MonitorTiles.custom(customPids)
        if (tiles.isEmpty()) return
        val enabled = MonitorTiles.loadEnabled(this)
        val checked = BooleanArray(tiles.size) { tiles[it].key in enabled }
        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(R.string.monitor_tile_title)
            .setMultiChoiceItems(tiles.map { it.title }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.common_ok) { _, _ ->
                val keys = tiles.indices.filter { checked[it] }.map { tiles[it].key }
                MonitorTiles.saveEnabled(this, keys)
                currentPage = 0
                renderTiles()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun renderTiles() {
        tileContainer.removeAllViews()
        tileViews.clear()
        val tiles = MonitorTiles.builtin(this) + MonitorTiles.custom(customPids)
        enabledKeys = MonitorTiles.loadEnabled(this)
        val shown = tiles.filter { it.key in enabledKeys }
        val pages = (shown.size + PAGE_SIZE - 1) / PAGE_SIZE
        currentPage = currentPage.coerceIn(0, (pages - 1).coerceAtLeast(0))
        val pageTiles = shown.drop(currentPage * PAGE_SIZE).take(PAGE_SIZE)

        pageTiles.chunked(3).forEach { rowTiles ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowTiles.forEach { tile -> row.addView(createTileView(tile)) }
            repeat(3 - rowTiles.size) {
                row.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                    }
                )
            }
            tileContainer.addView(row)
        }

        val hasPages = pages > 1
        pagePrevBtn.visibility = if (hasPages) View.VISIBLE else View.GONE
        pageNextBtn.visibility = if (hasPages) View.VISIBLE else View.GONE
        pageIndicator.text = if (hasPages) {
            getString(R.string.monitor_page, currentPage + 1, pages)
        } else {
            getString(R.string.monitor_drag_hint)
        }
    }

    private fun createTileView(tile: MonitorTiles.Tile): View {
        val view = LayoutInflater.from(this).inflate(R.layout.tile_item, tileContainer, false)
        view.tag = tile.key
        view.findViewById<TextView>(R.id.tile_title).apply {
            text = tile.title
            setTextColor(tile.color)
        }
        val unitView = view.findViewById<TextView>(R.id.tile_unit)
        val unit = tile.unitOf(unitSystem)
        if (unit.isNotEmpty()) unitView.text = unit
        else unitView.visibility = View.GONE
        tileViews[tile.key] = view.findViewById(R.id.tile_value)
        view.setOnLongClickListener {
            view.startDragAndDrop(
                ClipData.newPlainText("tile", tile.key),
                View.DragShadowBuilder(view),
                view,
                0,
            )
            true
        }
        return view
    }

    private fun setupTileDragDrop() {
        tileContainer.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED, DragEvent.ACTION_DRAG_ENTERED,
                DragEvent.ACTION_DRAG_LOCATION,
                -> {
                    highlightTarget(findTileAt(event.x, event.y))
                    true
                }
                DragEvent.ACTION_DROP -> {
                    val draggedKey = event.clipData.getItemAt(0).text.toString()
                    reorderKey(draggedKey, findTileAt(event.x, event.y))
                    clearDragHighlight()
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    clearDragHighlight()
                    true
                }
                else -> false
            }
        }
    }

    private fun highlightTarget(targetKey: String?) {
        for (i in 0 until tileContainer.childCount) {
            val row = tileContainer.getChildAt(i) as LinearLayout
            for (j in 0 until row.childCount) {
                val child = row.getChildAt(j)
                child.isActivated = child.tag == targetKey
            }
        }
    }

    private fun clearDragHighlight() {
        for (i in 0 until tileContainer.childCount) {
            val row = tileContainer.getChildAt(i) as LinearLayout
            for (j in 0 until row.childCount) row.getChildAt(j).isActivated = false
        }
    }

    private fun findTileAt(x: Float, y: Float): String? {
        var offsetY = 0f
        for (i in 0 until tileContainer.childCount) {
            val row = tileContainer.getChildAt(i) as LinearLayout
            val top = offsetY
            val bottom = top + row.height
            if (y in top..bottom) {
                var offsetX = 0f
                for (j in 0 until row.childCount) {
                    val child = row.getChildAt(j)
                    val left = offsetX
                    val right = left + child.width
                    if (x in left..right) return child.tag as? String
                    offsetX += child.width
                }
                return null
            }
            offsetY += row.height
        }
        return null
    }

    private fun reorderKey(draggedKey: String, targetKey: String?) {
        if (targetKey == draggedKey) return
        val order = enabledKeys.toMutableList()
        val from = order.indexOf(draggedKey)
        if (from < 0) return
        order.removeAt(from)
        val insertAt = if (targetKey == null) order.size else order.indexOf(targetKey).let {
            if (it < 0) order.size else it
        }
        order.add(insertAt, draggedKey)
        enabledKeys = LinkedHashSet(order)
        MonitorTiles.saveEnabled(this, order)
        renderTiles()
    }

    private fun pageCount(): Int {
        val tiles = MonitorTiles.builtin(this) + MonitorTiles.custom(customPids)
        return (tiles.count { it.key in enabledKeys } + PAGE_SIZE - 1) / PAGE_SIZE
    }

    private fun goToPage(newPage: Int, direction: Float = 0f) {
        val target = newPage.coerceIn(0, (pageCount() - 1).coerceAtLeast(0))
        if (target == currentPage) return
        currentPage = target
        renderTiles()
        val shift = direction * 56f * resources.displayMetrics.density
        tileContainer.alpha = 0f
        tileContainer.translationX = shift
        tileContainer.animate().alpha(1f).translationX(0f).setDuration(200L).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPageSwipe() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (abs(velocityX) > SWIPE_MIN_VELOCITY && abs(velocityX) > abs(velocityY)) {
                    if (velocityX < 0) goToPage(currentPage + 1, -1f)
                    else goToPage(currentPage - 1, 1f)
                    return true
                }
                return false
            }
        })
        tileScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tileScroll.performClick()
            }
            detector.onTouchEvent(event)
            false
        }
    }

    private fun updateTiles(data: ObdManager.LiveData) {
        val tiles = MonitorTiles.builtin(this) + MonitorTiles.custom(customPids)
        tiles.forEach { tile ->
            tileViews[tile.key]?.let { tv ->
                tv.text = tile.valueOf(unitSystem, data)?.let { formatValue(it) } ?: "—"
            }
        }
    }

    private fun formatValue(v: Float): String =
        if (v % 1.0f == 0.0f) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

    // ===== 固定儀表 =====

    private fun renderPower(data: ObdManager.LiveData) {
        data.torqueNm?.let {
            torqueText.text = String.format(
                Locale.US,
                if (unitSystem == UnitSystem.IMPERIAL) "%.1f lb-ft" else "%.1f Nm",
                unitSystem.torque(it),
            )
        } ?: run { torqueText.text = "—" }

        val rpm = data.rpm
        val kw = if (rpm != null) {
            data.torqueNm?.let { ObdDecoder.powerKw(rpm, it) }
                ?: data.maf?.let { ObdDecoder.powerKwFromMaf(it) }
        } else {
            data.maf?.let { ObdDecoder.powerKwFromMaf(it) }
        }
        powerText.text = if (kw != null) {
            String.format(Locale.US, "%.1f kW / %.1f HP", kw, ObdDecoder.kwToHp(kw))
        } else {
            "—"
        }
    }

    private fun renderFuelTrim(value: Float?) {
        fuelTrimText.text = value?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "—"
        fuelTrimText.setTextColor(
            getColor(
                when {
                    value == null -> R.color.text_primary
                    value.absoluteValue <= 5f -> R.color.success
                    value.absoluteValue <= 10f -> R.color.lock
                    else -> R.color.danger
                }
            )
        )
    }

    private fun renderAfr(value: Float?) {
        afrText.text = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        afrText.setTextColor(
            getColor(
                when {
                    value == null -> R.color.text_primary
                    value in 13.5f..15.0f -> R.color.success
                    value in 12.0f..13.5f || value in 15.0f..16.5f -> R.color.lock
                    else -> R.color.danger
                }
            )
        )
    }

    // ===== 精簡模式（單一數據快速讀取） =====

    private fun switchFocus(key: String) {
        focusKey = key
        if (focusMode) {
            obd.setFocusKey(key)
            applyFocusGauge(key)
        }
    }

    private fun toggleMode() {
        focusMode = !focusMode
        if (focusMode) {
            obd.setFocusKey(focusKey)
            applyFocusGauge(focusKey)
            extendedContainer.visibility = View.GONE
            focusButtonRow.visibility = View.VISIBLE
            toggleModeBtn.text = getString(R.string.monitor_full_display)
        } else {
            obd.setFocusKey(null)
            applyFocusGauge("rpm")
            extendedContainer.visibility = View.VISIBLE
            focusButtonRow.visibility = View.GONE
            toggleModeBtn.text = getString(R.string.monitor_focus_display)
            renderTiles()
        }
    }

    /** 依精簡模式選項調整主錶標題／顏色／單位／範圍（focusKey 變數由呼叫端維護） */
    private fun applyFocusGauge(key: String) {
        val imperial = unitSystem == UnitSystem.IMPERIAL
        when (key) {
            "speed" -> {
                focusTitle.text = getString(R.string.obd_speed)
                rpmGauge.setColor(0xFFF1C40F.toInt())
                rpmGauge.setUnit(unitSystem.speedUnit())
                rpmGauge.setRange(maxValue = if (imperial) 124f else 200f)
            }
            "coolant" -> {
                focusTitle.text = getString(R.string.obd_temp)
                rpmGauge.setColor(0xFFE74C3C.toInt())
                rpmGauge.setUnit(unitSystem.tempUnit())
                rpmGauge.setRange(
                    maxValue = if (imperial) 284f else 140f,
                    redFromValue = if (imperial) 230f else 110f,
                )
            }
            "voltage" -> {
                focusTitle.text = getString(R.string.obd_voltage)
                rpmGauge.setColor(0xFF3498DB.toInt())
                rpmGauge.setUnit("V")
                rpmGauge.setRange(maxValue = 16f, redBelowValue = 11.5f)
            }
            else -> {
                focusTitle.text = getString(R.string.obd_rpm)
                rpmGauge.setColor(0xFF2ECC71.toInt())
                rpmGauge.setUnit("RPM")
                rpmGauge.setRange(maxValue = 12000f, redFromValue = 9000f)
            }
        }
        updateFocusButtons(key)
    }

    private fun updateFocusButtons(key: String) {
        fun mark(btn: Button, selected: Boolean) {
            btn.setBackgroundResource(if (selected) R.drawable.bg_button else R.drawable.bg_card)
        }
        mark(focusRpmBtn, key == "rpm")
        mark(focusSpeedBtn, key == "speed")
        mark(focusCoolantBtn, key == "coolant")
        mark(focusVoltageBtn, key == "voltage")
    }

    private fun updateFocusValue(data: ObdManager.LiveData) {
        when (focusKey) {
            "speed" -> data.speed?.let { rpmGauge.setValue(unitSystem.speed(it.toFloat())) }
            "coolant" -> data.coolant?.let { rpmGauge.setValue(unitSystem.temp(it.toFloat())) }
            "voltage" -> data.voltage?.let { rpmGauge.setValue(it) }
            else -> data.rpm?.let { rpmGauge.setValue(it.toFloat()) }
        }
    }

    private fun setupGauges() {
        rpmGauge.setUnit("RPM")
        rpmGauge.setRange(maxValue = 12000f, redFromValue = 9000f)
        voltageGauge.setUnit("V")
        voltageGauge.setRange(maxValue = 16f, redBelowValue = 11.5f)
        applyUnitToGauges()
    }

    private fun renderState(state: ObdManager.State) {
        when (state) {
            ObdManager.State.Idle -> {
                statusText.text = getString(R.string.obd_disconnected)
                statusText.setTextColor(getColor(R.color.text_secondary))
                connectBtn.visibility = View.VISIBLE
                disconnectBtn.visibility = View.GONE
            }
            ObdManager.State.Connecting -> {
                statusText.text = getString(R.string.obd_connecting)
                statusText.setTextColor(getColor(R.color.lock))
                connectBtn.visibility = View.VISIBLE
                disconnectBtn.visibility = View.GONE
            }
            ObdManager.State.Ready -> {
                statusText.text = getString(R.string.obd_connected)
                statusText.setTextColor(getColor(R.color.success))
                connectBtn.visibility = View.GONE
                disconnectBtn.visibility = View.VISIBLE
            }
            is ObdManager.State.Error -> {
                statusText.text = getString(R.string.obd_disconnected)
                statusText.setTextColor(getColor(R.color.danger))
                connectBtn.visibility = View.VISIBLE
                disconnectBtn.visibility = View.GONE
            }
        }
    }

    companion object {
        private const val REQ_BT_PERMISSION = 100
        private const val PAGE_SIZE = 6
        private const val SWIPE_MIN_VELOCITY = 800f
        private const val DEFAULT_WIFI_ADDRESS = "192.168.0.10:35000"
        private const val DEFAULT_WIFI_PORT = 35000
    }
}

package com.heli.obd.ui

import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.DragEvent
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.BtPermissions
import com.heli.obd.elm.ObdDecoder
import com.heli.obd.elm.ObdManager
import com.heli.obd.pid.PidStore
import kotlin.math.abs
import kotlin.math.absoluteValue

/**
 * 即時數據畫面：藍牙連線 ELM327 顯示轉速/車速/水溫/電壓 + 馬力/扭力估算；
 * 「自訂」勾選額外數據（內建 10 項 + 自訂 PID）以 tile 網格呈現，
 * 支援單位制切換（公制/英制）、多頁翻頁、長按拖放排序，與歷史數據圖表入口。
 */
class ObdMonitorActivity : AppCompatActivity(), ObdManager.Listener {

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
        setupGauges()

        connectBtn.setOnClickListener { ensurePermissionAndConnect() }
        disconnectBtn.setOnClickListener {
            obd.disconnect()
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_SHORT).show()
        }
        customizeBtn.setOnClickListener { showCustomizeDialog() }
        unitBtn.setOnClickListener { toggleUnit() }
        chartBtn.setOnClickListener { startActivity(Intent(this, ChartActivity::class.java)) }
        pagePrevBtn.setOnClickListener { goToPage(currentPage - 1, 1f) }
        pageNextBtn.setOnClickListener { goToPage(currentPage + 1, -1f) }
        setupTileDragDrop()
        setupPageSwipe()

        obd.addListener(this)
        renderState(obd.state)
    }

    override fun onResume() {
        super.onResume()
        unitSystem = UnitSystem.load(this)
        customPids = PidStore(this).load()
        obd.setCustomPids(customPids)
        updateUnitBtn()
        renderTiles()
    }

    override fun onDestroy() {
        obd.removeListener(this)
        obd.disconnect()
        super.onDestroy()
    }

    // ===== 連線 =====

    private fun ensurePermissionAndConnect() {
        if (BtPermissions.hasAll(this)) {
            pickDevice()
        } else {
            requestPermissions(BtPermissions.required(), REQ_BT_PERMISSION)
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

    private fun pickDevice() {
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

    private fun connectTo(device: BluetoothDevice) {
        statusText.text = getString(R.string.obd_connecting)
        obd.connect(device) { success, message ->
            if (!success) {
                Toast.makeText(
                    this,
                    message?.let { getString(R.string.obd_connect_failed, it) }
                        ?: getString(R.string.obd_init_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // ===== ObdManager.Listener =====

    override fun onStateChanged(state: ObdManager.State) {
        renderState(state)
    }

    override fun onLiveData(data: ObdManager.LiveData) {
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
        if (v % 1.0f == 0.0f) v.toInt().toString() else String.format("%.1f", v)

    // ===== 固定儀表 =====

    private fun renderPower(data: ObdManager.LiveData) {
        data.torqueNm?.let {
            torqueText.text = String.format(
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
            String.format("%.1f kW / %.1f HP", kw, ObdDecoder.kwToHp(kw))
        } else {
            "—"
        }
    }

    private fun renderFuelTrim(value: Float?) {
        fuelTrimText.text = value?.let { String.format("%+.1f%%", it) } ?: "—"
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
        afrText.text = value?.let { String.format("%.1f", it) } ?: "—"
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
    }
}

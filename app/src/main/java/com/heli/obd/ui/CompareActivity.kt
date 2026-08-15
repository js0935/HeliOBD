/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.elm.ObdManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 數據對比：捕捉兩組 OBD 即時數據快照，逐項比較並顯示差異。
 */
class CompareActivity : BaseActivity() {

    private val obd get() = MainActivity.ObdManagerHolder.obd(this)

    data class Snapshot(
        val time: Long,
        val rpm: Int?,
        val speed: Int?,
        val coolant: Int?,
        val voltage: Float?,
    )

    private var snapA: Snapshot? = null
    private var snapB: Snapshot? = null

    private lateinit var snapAText: TextView
    private lateinit var snapBText: TextView
    private lateinit var compareTable: LinearLayout
    private lateinit var captureABtn: Button
    private lateinit var captureBBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compare)

        snapAText = findViewById(R.id.snap_a_text)
        snapBText = findViewById(R.id.snap_b_text)
        compareTable = findViewById(R.id.compare_table)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        captureABtn = findViewById(R.id.btn_capture_a)
        captureBBtn = findViewById(R.id.btn_capture_b)
        captureABtn.setOnClickListener { captureA() }
        captureBBtn.setOnClickListener { captureB() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener { clearAll() }
    }

    private fun captureA() = captureAsync(captureABtn) { snapA = it }

    private fun captureB() = captureAsync(captureBBtn) { snapB = it }

    private fun captureAsync(btn: Button, apply: (Snapshot) -> Unit) {
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_LONG).show()
            return
        }
        val busy = BusyUi.mark(btn, getString(R.string.busy_reading))
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { obd.requestLiveData() }
                if (data == null) {
                    Toast.makeText(this@CompareActivity, R.string.compare_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                apply(
                    Snapshot(
                        time = System.currentTimeMillis(),
                        rpm = data.rpm,
                        speed = data.speed,
                        coolant = data.coolant,
                        voltage = data.voltage,
                    )
                )
                render()
            } finally {
                busy.done()
            }
        }
    }

    private fun clearAll() {
        snapA = null
        snapB = null
        render()
    }

    private fun render() {
        snapAText.text = snapA?.let { describe(it) } ?: getString(R.string.compare_empty_a)
        snapBText.text = snapB?.let { describe(it) } ?: getString(R.string.compare_empty_b)
        renderCompareTable()
    }

    private fun describe(s: Snapshot): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(s.time))
        val rpm = s.rpm?.toString() ?: "--"
        val speed = s.speed?.toString() ?: "--"
        val coolant = s.coolant?.toString() ?: "--"
        val voltage = s.voltage?.let { "%.1f".format(it) } ?: "--"
        return getString(
            R.string.compare_snapshot_info,
            time, rpm, speed, coolant, voltage,
        )
    }

    private fun renderCompareTable() {
        compareTable.removeAllViews()
        val a = snapA ?: return
        val b = snapB ?: return
        addCompareRow(getString(R.string.obd_rpm), a.rpm, b.rpm, R.string.common_unit_rpm)
        addCompareRow(getString(R.string.obd_speed), a.speed, b.speed, R.string.common_unit_kmh)
        addCompareRow(getString(R.string.obd_temp), a.coolant, b.coolant, R.string.common_unit_celsius)
        addCompareRow(getString(R.string.obd_voltage), a.voltage, b.voltage, R.string.common_unit_volt)
    }

    private fun addCompareRow(label: String, aVal: Number?, bVal: Number?, unitRes: Int) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(10), dp(8), dp(10), dp(8))
        row.setBackgroundResource(R.drawable.bg_card)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(4)
        row.layoutParams = lp

        fun cell(weight: Float): TextView = TextView(this).apply {
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        }

        val labelView = cell(1f)
        labelView.text = label
        labelView.setTextColor(getColor(R.color.text_secondary))
        row.addView(labelView)

        val aView = cell(1f)
        aView.text = formatValue(aVal, unitRes)
        aView.setTextColor(getColor(R.color.text_primary))
        aView.setTypeface(aView.typeface, android.graphics.Typeface.BOLD)
        row.addView(aView)

        val bView = cell(1f)
        bView.text = formatValue(bVal, unitRes)
        bView.setTextColor(getColor(R.color.text_primary))
        bView.setTypeface(bView.typeface, android.graphics.Typeface.BOLD)
        row.addView(bView)

        val diffView = cell(1f)
        if (aVal != null && bVal != null && aVal is Double && bVal is Double) {
            val diff = bVal - aVal
            diffView.text = String.format(Locale.getDefault(), "%+.1f", diff)
            diffView.setTextColor(getColor(if (diff == 0.0) R.color.text_secondary else R.color.lock))
        } else if (aVal != null && bVal != null) {
            val diff = bVal.toDouble() - aVal.toDouble()
            diffView.text = String.format(Locale.getDefault(), "%+.0f", diff)
            diffView.setTextColor(getColor(if (diff == 0.0) R.color.text_secondary else R.color.lock))
        } else {
            diffView.text = "--"
            diffView.setTextColor(getColor(R.color.text_secondary))
        }
        row.addView(diffView)

        compareTable.addView(row)
    }

    private fun formatValue(v: Number?, unitRes: Int): String =
        when (v) {
            null -> "--"
            is Float, is Double -> "%.1f".format(v.toDouble()) + getString(unitRes)
            else -> "$v" + getString(unitRes)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

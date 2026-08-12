/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.heli.obd.BaseActivity
import com.heli.obd.R
import com.heli.obd.leaf.LeafBatteryStore
import com.heli.obd.leaf.LeafBatteryStore.LeafRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * EV Leaf 電池健康（SoH）追蹤：紀錄 AHr/SoH/Hx/GIDs 歷史，估算衰退速率與剩餘壽命。
 */
class LeafSoHActivity : BaseActivity() {

    private lateinit var store: LeafBatteryStore

    private lateinit var statusText: TextView
    private lateinit var sohValueText: TextView
    private lateinit var sohStateText: TextView
    private lateinit var sohDateText: TextView
    private lateinit var editKm: EditText
    private lateinit var editSoh: EditText
    private lateinit var editAhr: EditText
    private lateinit var editHx: EditText
    private lateinit var editGids: EditText
    private lateinit var editNote: EditText
    private lateinit var decayRateText: TextView
    private lateinit var estKmText: TextView
    private lateinit var historyContainer: LinearLayout

    private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leaf_soh)

        store = LeafBatteryStore(this)

        statusText = findViewById(R.id.leaf_status_text)
        sohValueText = findViewById(R.id.leaf_soh_value)
        sohStateText = findViewById(R.id.leaf_soh_state)
        sohDateText = findViewById(R.id.leaf_soh_date)
        editKm = findViewById(R.id.leaf_edit_km)
        editSoh = findViewById(R.id.leaf_edit_soh)
        editAhr = findViewById(R.id.leaf_edit_ahr)
        editHx = findViewById(R.id.leaf_edit_hx)
        editGids = findViewById(R.id.leaf_edit_gids)
        editNote = findViewById(R.id.leaf_edit_note)
        decayRateText = findViewById(R.id.leaf_decay_rate)
        estKmText = findViewById(R.id.leaf_est_km)
        historyContainer = findViewById(R.id.leaf_history_container)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_leaf_save).setOnClickListener { saveRecord() }

        statusText.text = getString(R.string.obd_disconnected)
        renderAll()
    }

    private fun renderAll() {
        val latest = store.latest()
        if (latest == null) {
            sohValueText.text = getString(R.string.leaf_soh_placeholder)
            sohStateText.text = getString(R.string.leaf_no_data)
            sohDateText.text = ""
        } else {
            sohValueText.text = String.format(Locale.US, "%s%%", formatSoH(latest.soh))
            val state = sohState(latest.soh)
            sohStateText.text = getString(state.stringRes)
            sohStateText.setTextColor(getColor(state.colorRes))
            sohValueText.setTextColor(getColor(state.colorRes))
            sohDateText.text = dateFormat.format(Date(latest.timestampMs))
        }

        // 衰退趨勢
        val decay = store.sohDecayPer10kKm()
        decayRateText.text = if (decay == null) {
            getString(R.string.leaf_decay_none)
        } else {
            getString(R.string.leaf_decay_rate, if (decay > 0) "%.2f%%".format(decay) else "0.00%")
        }
        val est = store.estimatedKmTo(70f)
        estKmText.text = if (est == null) {
            getString(R.string.leaf_est_none)
        } else {
            getString(R.string.leaf_est_km, est.toString())
        }

        renderHistory()
    }

    private fun renderHistory() {
        historyContainer.removeAllViews()
        val records = store.load().sortedByDescending { it.timestampMs }
        if (records.isEmpty()) {
            historyContainer.addView(
                TextView(this).apply {
                    text = getString(R.string.leaf_no_data)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 14f
                    setPadding(0, dp(8), 0, 0)
                }
            )
            return
        }
        records.forEach { record ->
            historyContainer.addView(historyRow(record))
        }
    }

    private fun historyRow(record: LeafRecord): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))

        val info = TextView(this)
        info.text = getString(
            R.string.leaf_history_item,
            dateFormat.format(Date(record.timestampMs)),
            record.mileageKm.toString(),
            formatSoH(record.soh),
            formatFloat(record.ahr),
        )
        info.textSize = 13f
        info.setTextColor(getColor(R.color.text_secondary))
        info.setPadding(0, 0, dp(8), 0)
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val deleteBtn = Button(this)
        deleteBtn.text = getString(R.string.leaf_delete)
        deleteBtn.textSize = 12f
        deleteBtn.setBackgroundResource(R.drawable.bg_button)
        deleteBtn.setTextColor(getColor(R.color.danger))
        deleteBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.leaf_confirm_delete)
                .setPositiveButton(R.string.common_ok) { _, _ ->
                    store.delete(record.id)
                    renderAll()
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
        row.addView(deleteBtn)
        return row
    }

    private fun saveRecord() {
        val km = editKm.text.toString().toIntOrNull() ?: 0
        val soh = editSoh.text.toString().toFloatOrNull()
        if (km <= 0 || soh == null || soh !in 0f..100f) {
            Toast.makeText(this, R.string.leaf_invalid, Toast.LENGTH_LONG).show()
            return
        }
        store.add(
            LeafRecord(
                id = System.currentTimeMillis(),
                timestampMs = System.currentTimeMillis(),
                mileageKm = km,
                soh = soh,
                ahr = editAhr.text.toString().toFloatOrNull() ?: 0f,
                hx = editHx.text.toString().toFloatOrNull() ?: 0f,
                gids = editGids.text.toString().toIntOrNull() ?: 0,
                note = editNote.text.toString().trim(),
            )
        )
        editKm.text.clear()
        editSoh.text.clear()
        editAhr.text.clear()
        editHx.text.clear()
        editGids.text.clear()
        editNote.text.clear()
        renderAll()
        Toast.makeText(this, R.string.leaf_saved, Toast.LENGTH_SHORT).show()
    }

    private fun sohState(soh: Float): StateColors = when {
        soh >= 90f -> StateColors(R.string.leaf_state_excellent, R.color.success)
        soh >= 80f -> StateColors(R.string.leaf_state_good, R.color.success)
        soh >= 70f -> StateColors(R.string.leaf_state_warn, R.color.lock)
        else -> StateColors(R.string.leaf_state_critical, R.color.danger)
    }

    private data class StateColors(val stringRes: Int, val colorRes: Int)

    private fun formatSoH(v: Float): String = "%.1f".format(v)

    private fun formatFloat(v: Float): String = if (v > 0) "%.1f".format(v) else "—"

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

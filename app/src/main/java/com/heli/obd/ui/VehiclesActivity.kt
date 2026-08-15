/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.graphics.Typeface
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
import androidx.lifecycle.lifecycleScope
import com.heli.obd.MainActivity
import com.heli.obd.R
import com.heli.obd.vehicles.VehicleBrands
import com.heli.obd.vehicles.VehicleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 多車管理：新增/編輯/刪除車籍資料，並指定目前使用的車輛。
 */
class VehiclesActivity : BaseActivity() {

    private val store by lazy { VehicleStore(this) }
    private val brands by lazy { VehicleBrands(this) }
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicles)

        container = findViewById(R.id.vehicle_container)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_add).setOnClickListener { showEditDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun renderList() {
        container.removeAllViews()
        val vehicles = store.load()
        if (vehicles.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.vehicles_empty)
            empty.textSize = 14f
            empty.setTextColor(getColor(R.color.text_secondary))
            empty.setPadding(0, dp(16), 0, 0)
            empty.gravity = Gravity.CENTER
            container.addView(empty)
            return
        }
        val currentId = store.currentId()
        vehicles.forEach { vehicle ->
            container.addView(buildCard(vehicle, vehicle.id == currentId))
        }
    }

    private fun buildCard(vehicle: VehicleStore.Vehicle, isCurrent: Boolean): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        card.setBackgroundResource(R.drawable.bg_card)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = dp(8)
        card.layoutParams = lp

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val name = TextView(this)
        name.text = vehicle.name
        name.textSize = 16f
        name.setTypeface(name.typeface, Typeface.BOLD)
        name.setTextColor(getColor(R.color.text_primary))
        name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        head.addView(name)

        if (isCurrent) {
            val badge = TextView(this)
            badge.text = getString(R.string.vehicles_current)
            badge.textSize = 12f
            badge.setPadding(dp(8), dp(3), dp(8), dp(3))
            badge.setBackgroundResource(R.drawable.bg_button_accent)
            badge.setTextColor(android.graphics.Color.WHITE)
            head.addView(badge)
        }
        card.addView(head)

        val detail = TextView(this)
        val typeLabel = when (vehicle.type) {
            VehicleStore.TYPE_CAR -> getString(R.string.vehicle_type_car)
            else -> getString(R.string.vehicle_type_motorcycle)
        }
        val brandModel = listOf(vehicle.brand, vehicle.model).filter { it.isNotBlank() }.joinToString(" ")
        val parts = listOf(typeLabel, brandModel, vehicle.engineCc, vehicle.note).filter { it.isNotBlank() }
        detail.text = if (parts.isEmpty()) "" else parts.joinToString(" ｜ ")
        detail.textSize = 13f
        detail.setTextColor(getColor(R.color.text_secondary))
        detail.setPadding(0, dp(6), 0, 0)
        card.addView(detail)

        val ops = LinearLayout(this)
        ops.orientation = LinearLayout.HORIZONTAL
        ops.gravity = Gravity.END
        ops.setPadding(0, dp(8), 0, 0)

        fun opButton(textRes: Int, colorRes: Int): TextView = TextView(this).apply {
            text = getString(textRes)
            textSize = 13f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setTextColor(getColor(colorRes))
        }

        if (!isCurrent) {
            val setCurrent = opButton(R.string.vehicles_set_current, R.color.primary)
            setCurrent.setOnClickListener {
                store.setCurrent(vehicle.id)
                renderList()
                Toast.makeText(this, R.string.vehicles_current_set, Toast.LENGTH_SHORT).show()
            }
            ops.addView(setCurrent)
        }

        val readVin = opButton(R.string.vehicles_read_vin, R.color.primary)
        readVin.setOnClickListener { readVinFor(vehicle) }
        ops.addView(readVin)

        val edit = opButton(R.string.common_edit, R.color.text_secondary)
        edit.setOnClickListener { showEditDialog(vehicle) }
        ops.addView(edit)

        val del = opButton(R.string.common_delete, R.color.danger)
        del.setOnClickListener {
            AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                .setMessage(getString(R.string.vehicles_confirm_delete, vehicle.name))
                .setPositiveButton(R.string.common_delete) { _, _ ->
                    store.delete(vehicle.id)
                    renderList()
                    Toast.makeText(this, R.string.trip_deleted, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
        ops.addView(del)

        card.addView(ops)
        return card
    }

    private fun readVinFor(vehicle: VehicleStore.Vehicle) {
        val obd = MainActivity.ObdManagerHolder.obd(this)
        if (!obd.isConnected()) {
            Toast.makeText(this, R.string.obd_disconnected, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.obd_connecting, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val vin = withContext(Dispatchers.IO) { obd.readVin() }
            if (vin == null || vin.length < 11) {
                Toast.makeText(this@VehiclesActivity, R.string.diag_vin_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            AlertDialog.Builder(this@VehiclesActivity, R.style.Theme_HeliOBD_Dialog)
                .setTitle(R.string.vehicles_read_vin)
                .setMessage(vin)
                .setPositiveButton(R.string.common_ok) { _, _ ->
                    store.upsert(vehicle.copy(note = vin))
                    renderList()
                    Toast.makeText(this@VehiclesActivity, R.string.vehicles_vin_loaded, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    private fun showEditDialog(existing: VehicleStore.Vehicle?) {
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(dp(24), dp(16), dp(24), 0)

        fun field(hintRes: Int, initial: String): EditText =
            EditText(this).apply {
                hint = getString(hintRes)
                setText(initial)
                textSize = 15f
                setSingleLine(true)
                setPadding(0, dp(8), 0, dp(4))
                setTextColor(getColor(R.color.text_primary))
                setHintTextColor(getColor(R.color.text_secondary))
            }

        var selectedType = existing?.type ?: VehicleStore.TYPE_MOTORCYCLE

        val typeRow = LinearLayout(this)
        typeRow.orientation = LinearLayout.HORIZONTAL
        typeRow.gravity = Gravity.CENTER_VERTICAL

        var carChip: TextView? = null
        var bikeChip: TextView? = null

        fun styleChip(chip: TextView, selected: Boolean) {
            chip.setBackgroundResource(if (selected) R.drawable.bg_button_accent else R.drawable.bg_card)
            chip.setTextColor(
                if (selected) android.graphics.Color.WHITE else getColor(R.color.text_secondary)
            )
        }

        fun refreshChips() {
            carChip?.let { styleChip(it, selectedType == VehicleStore.TYPE_CAR) }
            bikeChip?.let { styleChip(it, selectedType == VehicleStore.TYPE_MOTORCYCLE) }
        }

        fun makeChip(textRes: Int, key: String): TextView = TextView(this).apply {
            text = getString(textRes)
            textSize = 14f
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener {
                selectedType = key
                refreshChips()
            }
        }

        carChip = makeChip(R.string.vehicle_type_car, VehicleStore.TYPE_CAR)
        bikeChip = makeChip(R.string.vehicle_type_motorcycle, VehicleStore.TYPE_MOTORCYCLE)
        refreshChips()

        val carLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        carLp.setMargins(0, dp(8), dp(8), dp(8))
        typeRow.addView(carChip, carLp)
        typeRow.addView(bikeChip)

        form.addView(typeRow)

        var selectedBrand = existing?.brand.orEmpty()
        var selectedModel = existing?.model.orEmpty()

        fun makeRow(hintRes: Int, value: String): TextView = TextView(this).apply {
            text = if (value.isBlank()) getString(hintRes) else value
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            setTextColor(
                if (value.isBlank()) getColor(R.color.text_secondary) else getColor(R.color.text_primary)
            )
            setBackgroundResource(R.drawable.bg_row_clickable)
        }

        val brandRow = makeRow(R.string.vehicles_select_brand, selectedBrand)
        val modelRow = makeRow(R.string.vehicles_model_hint, selectedModel)

        fun refreshRows() {
            brandRow.text = selectedBrand.ifBlank { getString(R.string.vehicles_select_brand) }
            brandRow.setTextColor(
                if (selectedBrand.isBlank()) getColor(R.color.text_secondary) else getColor(R.color.text_primary)
            )
            modelRow.text = selectedModel.ifBlank { getString(R.string.vehicles_model_hint) }
            modelRow.setTextColor(
                if (selectedModel.isBlank()) getColor(R.color.text_secondary) else getColor(R.color.text_primary)
            )
        }

        fun manualInputDialog(
            titleRes: Int,
            hintRes: Int,
            current: String,
            onResult: (String) -> Unit,
        ) {
            val input = EditText(this).apply {
                hint = getString(hintRes)
                setText(current)
                textSize = 15f
                setSingleLine(true)
                setPadding(dp(24), dp(16), dp(24), 0)
                setTextColor(getColor(R.color.text_primary))
                setHintTextColor(getColor(R.color.text_secondary))
            }
            AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                .setTitle(titleRes)
                .setView(input)
                .setPositiveButton(R.string.common_save) { _, _ -> onResult(input.text.toString()) }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }

        fun pickModel(models: List<String>) {
            AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                .setTitle(R.string.vehicles_select_model)
                .setItems(models.toTypedArray()) { _, which ->
                    selectedModel = models[which]
                    refreshRows()
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }

        brandRow.setOnClickListener {
            val brandNames = brands.brandNames()
            val items = brandNames + getString(R.string.vehicles_manual_input)
            AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                .setTitle(R.string.vehicles_select_brand)
                .setItems(items.toTypedArray()) { _, which ->
                    if (which == brandNames.size) {
                        manualInputDialog(
                            R.string.vehicles_select_brand,
                            R.string.vehicles_brand_hint,
                            selectedBrand,
                        ) { value ->
                            selectedBrand = value.trim()
                            selectedModel = ""
                            refreshRows()
                        }
                    } else {
                        selectedBrand = brandNames[which]
                        selectedModel = ""
                        val models = brands.modelsOf(selectedBrand)
                        refreshRows()
                        if (models.isNotEmpty()) {
                            pickModel(models)
                        } else {
                            Toast.makeText(
                                this@VehiclesActivity,
                                R.string.vehicles_no_builtin_models,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }

        modelRow.setOnClickListener {
            if (selectedBrand.isBlank()) {
                Toast.makeText(this@VehiclesActivity, R.string.vehicles_select_brand_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val models = brands.modelsOf(selectedBrand)
            if (models.isEmpty()) {
                manualInputDialog(
                    R.string.vehicles_select_model,
                    R.string.vehicles_model_hint,
                    selectedModel,
                ) { value ->
                    selectedModel = value.trim()
                    refreshRows()
                }
            } else {
                pickModel(models)
            }
        }

        form.addView(brandRow)
        form.addView(modelRow)

        val nameField = field(R.string.vehicles_name_hint, existing?.name.orEmpty())
        val ccField = field(R.string.vehicles_cc_hint, existing?.engineCc.orEmpty())
        val noteField = field(R.string.vehicles_note_hint, existing?.note.orEmpty())

        form.addView(nameField)
        form.addView(ccField)
        form.addView(noteField)

        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(
                if (existing == null) R.string.vehicles_add
                else R.string.vehicles_edit
            )
            .setView(form)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val name = nameField.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.vehicles_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.upsert(
                    VehicleStore.Vehicle(
                        id = existing?.id ?: System.currentTimeMillis(),
                        name = name,
                        brand = selectedBrand,
                        model = selectedModel,
                        engineCc = ccField.text.toString().trim(),
                        note = noteField.text.toString().trim(),
                        type = selectedType,
                    )
                )
                renderList()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

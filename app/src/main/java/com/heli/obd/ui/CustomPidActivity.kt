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
import com.heli.obd.R
import com.heli.obd.pid.PidEvaluator
import com.heli.obd.pid.PidStore

/**
 * 自訂 PID 編輯器：新增/編輯/刪除車廠專用 PID，支援公式（A/B/C/D 為 raw 位元組）與即時預覽。
 */
class CustomPidActivity : BaseActivity() {

    private val store by lazy { PidStore(this) }
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_pid)

        container = findViewById(R.id.pid_container)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_add).setOnClickListener { showEditDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun renderList() {
        container.removeAllViews()
        val pids = store.load()
        if (pids.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.pid_empty)
            empty.textSize = 14f
            empty.setTextColor(getColor(R.color.text_secondary))
            empty.setPadding(0, dp(16), 0, 0)
            empty.gravity = Gravity.CENTER
            container.addView(empty)
            return
        }
        pids.forEach { pid ->
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
            name.text = pid.name
            name.textSize = 16f
            name.setTypeface(name.typeface, Typeface.BOLD)
            name.setTextColor(getColor(R.color.text_primary))
            name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            head.addView(name)

            fun opButton(text: String, color: Int): TextView = TextView(this).apply {
                this.text = text
                textSize = 13f
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setTextColor(getColor(color))
            }

            val del = opButton(getString(R.string.pid_delete), R.color.danger)
            del.setOnClickListener {
                AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
                    .setMessage(getString(R.string.pid_confirm_delete, pid.name))
                    .setPositiveButton(getString(R.string.pid_delete)) { _, _ ->
                        store.delete(pid.id)
                        renderList()
                    }
                    .setNegativeButton(getString(R.string.common_cancel), null)
                    .show()
            }
            head.addView(del)

            val edit = opButton(getString(R.string.pid_edit), R.color.text_secondary)
            edit.setOnClickListener { showEditDialog(pid) }
            head.addView(edit)

            card.addView(head)

            val info = TextView(this)
            info.text = "${pid.mode}${pid.pid}  ${pid.formula}${if (pid.unit.isNotBlank()) " ${pid.unit}" else ""}"
            info.textSize = 13f
            info.setTextColor(getColor(R.color.text_secondary))
            info.setPadding(0, dp(6), 0, 0)
            card.addView(info)

            container.addView(card)
        }
    }

    private fun showEditDialog(existing: PidStore.CustomPid?) {
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(dp(24), dp(16), dp(24), 0)

        fun field(hint: String, initial: String): EditText =
            EditText(this).apply {
                this.hint = hint
                setText(initial)
                textSize = 15f
                setSingleLine(true)
                setPadding(0, dp(8), 0, dp(4))
                setTextColor(getColor(R.color.text_primary))
                setHintTextColor(getColor(R.color.text_secondary))
            }

        val nameField = field(getString(R.string.pid_name_hint), existing?.name.orEmpty())
        val modeField = field(getString(R.string.pid_mode_hint), existing?.mode.orEmpty())
        val pidField = field(getString(R.string.pid_pid_hint), existing?.pid.orEmpty())
        val unitField = field(getString(R.string.pid_unit_hint), existing?.unit.orEmpty())
        val formulaField = field(getString(R.string.pid_formula_hint), existing?.formula.orEmpty())

        form.addView(nameField)
        form.addView(modeField)
        form.addView(pidField)
        form.addView(unitField)
        form.addView(formulaField)

        val preview = TextView(this)
        preview.text = getString(R.string.pid_preview_hint)
        preview.textSize = 13f
        preview.setTextColor(getColor(R.color.text_secondary))
        preview.setPadding(0, dp(10), 0, 0)
        form.addView(preview)

        val previewInput = field(getString(R.string.pid_raw_hint), "")
        form.addView(previewInput)

        AlertDialog.Builder(this, R.style.Theme_HeliOBD_Dialog)
            .setTitle(getString(if (existing == null) R.string.pid_add_title else R.string.pid_edit_title))
            .setView(form)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                val name = nameField.text.toString().trim()
                val mode = modeField.text.toString().trim()
                val pid = pidField.text.toString().trim()
                val formula = formulaField.text.toString().trim()
                val hexOk = Regex("^[0-9A-Fa-f]{2}$")
                when {
                    name.isEmpty() -> Toast.makeText(this, R.string.pid_name_required, Toast.LENGTH_SHORT).show()
                    !hexOk.matches(mode) -> Toast.makeText(this, R.string.pid_mode_invalid, Toast.LENGTH_SHORT).show()
                    !hexOk.matches(pid) -> Toast.makeText(this, R.string.pid_pid_invalid, Toast.LENGTH_SHORT).show()
                    formula.isEmpty() -> Toast.makeText(this, R.string.pid_formula_required, Toast.LENGTH_SHORT).show()
                    else -> {
                        store.upsert(
                            PidStore.CustomPid(
                                id = existing?.id ?: System.currentTimeMillis(),
                                name = name,
                                mode = mode,
                                pid = pid,
                                unit = unitField.text.toString().trim(),
                                formula = formula,
                                min = null,
                                max = null,
                            )
                        )
                        renderList()
                    }
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    previewInput.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: android.text.Editable?) {
                            val formula = formulaField.text.toString().trim()
                            val rawText = previewInput.text.toString().trim().replace(" ", "")
                            val raw = parseHex(rawText)
                            if (formula.isEmpty() || raw.isEmpty()) {
                                preview.text = getString(R.string.pid_preview_hint)
                                return
                            }
                            val result = PidEvaluator.evaluate(formula, raw)
                            preview.text = if (result == null) {
                                getString(R.string.pid_formula_error)
                            } else {
                                getString(R.string.pid_result_format, trimNum(result))
                            }
                        }
                    })
                }
            }
            .show()
    }

    private fun parseHex(text: String): IntArray {
        if (text.isEmpty() || text.length % 2 != 0) return IntArray(0)
        return (0 until text.length step 2).mapNotNull { i ->
            text.substring(i, i + 2).toIntOrNull(16)
        }.toIntArray()
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

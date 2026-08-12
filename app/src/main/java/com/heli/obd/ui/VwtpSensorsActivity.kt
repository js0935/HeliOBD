/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.heli.obd.BaseActivity
import com.heli.obd.R
import com.heli.obd.vwtp.VwtpFormulaEngine
import com.heli.obd.vwtp.VwtpFormulaStore
import com.heli.obd.vwtp.VwtpUnitSymbols
import org.json.JSONObject
import java.util.Locale

/**
 * VW TP 2.0（VAG）感測器瀏覽：載入 163 條感測器公式表，輸入 raw 位元組
 * A/B（0-255）即時計算全部感測器數值。真實讀取需 VW TP 2.0 協定硬體，
 * 此畫面以模擬輸入驗證公式引擎與感測器定義。
 */
class VwtpSensorsActivity : BaseActivity() {

    private lateinit var container: LinearLayout
    private lateinit var inputA: EditText
    private lateinit var inputB: EditText

    private var formulas: Map<Int, VwtpFormulaEngine.Formula> = emptyMap()
    private var names: Map<Int, SensorName> = emptyMap()

    /** 卡片值文字（id → TextView），重算時只更新文字不重建列表。 */
    private val valueViews = mutableMapOf<Int, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vwtp_sensors)

        container = findViewById(R.id.sensor_container)
        inputA = findViewById(R.id.input_a)
        inputB = findViewById(R.id.input_b)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_random).setOnClickListener {
            inputA.setText(String.format(Locale.US, "%d", kotlin.random.Random.nextInt(256)))
            inputB.setText(String.format(Locale.US, "%d", kotlin.random.Random.nextInt(256)))
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateValues()
        }
        inputA.addTextChangedListener(watcher)
        inputB.addTextChangedListener(watcher)

        formulas = VwtpFormulaStore(this).load()
        names = loadNames()

        if (formulas.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.vwtp_no_data)
            empty.textSize = 14f
            empty.setTextColor(getColor(R.color.text_secondary))
            empty.setPadding(0, dp(16), 0, 0)
            empty.gravity = Gravity.CENTER
            container.addView(empty)
            return
        }

        renderList()
        inputA.setText(getString(R.string.vwtp_default_input))
        inputB.setText(getString(R.string.vwtp_default_input))
    }

    /** 讀取 vwtp_sensors.json（formulaId → {de, zh} 感測器名稱）。 */
    private fun loadNames(): Map<Int, SensorName> {
        return runCatching {
            val text = assets.open(FILE_NAMES).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val keys = root.keys()
            val out = HashMap<Int, SensorName>()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = key.toIntOrNull() ?: continue
                val entry = root.optJSONObject(key) ?: continue
                out[id] = SensorName(
                    de = entry.optString("de").ifEmpty { null },
                    zh = entry.optString("zh").ifEmpty { null },
                )
            }
            out
        }.getOrElse { emptyMap() }
    }

    /** 建立全部感測器卡片（值文字暫存於 [valueViews]）。 */
    private fun renderList() {
        container.removeAllViews()
        valueViews.clear()

        val header = TextView(this)
        header.text = resources.getQuantityString(R.plurals.vwtp_sensor_count, formulas.size, formulas.size)
        header.textSize = 13f
        header.setTextColor(getColor(R.color.text_secondary))
        header.setPadding(0, dp(2), 0, dp(4))
        container.addView(header)

        formulas.keys.sorted().forEach { id ->
            container.addView(buildCard(id))
        }
    }

    private fun buildCard(id: Int): LinearLayout {
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

        // 標題列：Kennzahl 編號 + 名稱
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val badge = TextView(this)
        badge.text = getString(R.string.vwtp_kennzahl_format, id)
        badge.textSize = 12f
        badge.setPadding(dp(8), dp(2), dp(8), dp(2))
        badge.setTextColor(getColor(R.color.primary))
        badge.setBackgroundResource(R.drawable.bg_card)
        head.addView(badge)

        val name = TextView(this)
        val sn = names[id]
        name.text = sn?.zh ?: getString(R.string.vwtp_kennzahl_format, id)
        name.textSize = 16f
        name.setTextColor(getColor(R.color.text_primary))
        name.setPadding(dp(10), 0, 0, 0)
        name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        head.addView(name)
        card.addView(head)

        val de = sn?.de
        if (de != null && de != sn.zh && !de.startsWith("Kennzahl ")) {
            val sub = TextView(this)
            sub.text = de
            sub.textSize = 12f
            sub.setTextColor(getColor(R.color.text_secondary))
            sub.setPadding(dp(34), dp(2), 0, 0)
            card.addView(sub)
        }

        // 公式列：直接公式或分支清單
        val f = formulas[id]!!
        val formulaText = if (f.expr != null) {
            f.expr
        } else {
            f.branches?.joinToString("\n") { br ->
                val cond = if (br.cond == "else") getString(R.string.vwtp_else) else br.cond
                "$cond → ${br.expr}"
            } ?: "—"
        }
        val formula = TextView(this)
        formula.text = getString(R.string.vwtp_formula_label, formulaText)
        formula.textSize = 12f
        formula.setTextColor(getColor(R.color.text_secondary))
        formula.setPadding(0, dp(6), 0, 0)
        card.addView(formula)

        // 值列：計算結果 + 單位符號
        val value = TextView(this)
        value.text = "—"
        value.textSize = 14f
        value.setTextColor(getColor(R.color.accent))
        value.setPadding(0, dp(4), 0, 0)
        card.addView(value)
        valueViews[id] = value

        return card
    }

    /** 依目前 A/B 輸入重算全部感測器並更新值文字。 */
    private fun updateValues() {
        if (valueViews.isEmpty()) return
        val a = parseByte(inputA.text.toString())
        val b = parseByte(inputB.text.toString())
        if (a == null || b == null) return
        formulas.keys.forEach { id ->
            val r = VwtpFormulaEngine.evaluate(id, a, b, formulas)
            valueViews[id]?.text = formatResult(r)
        }
    }

    private fun parseByte(text: String): Int? =
        text.toIntOrNull()?.takeIf { it in 0..255 }

    private fun formatResult(r: VwtpFormulaEngine.Result?): String =
        r?.let { "${trimNum(it.value)} ${VwtpUnitSymbols.symbolOf(it.unit)}".trim() } ?: "—"

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    data class SensorName(val de: String?, val zh: String?)

    private companion object {
        const val FILE_NAMES = "vwtp_sensors.json"
    }
}

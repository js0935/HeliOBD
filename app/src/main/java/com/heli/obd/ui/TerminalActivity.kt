/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.ui

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R

/**
 * OBD 終端機：手動輸入 AT / UDS / OBD 模式指令，即時顯示 ELM327 完整原始回應。
 * 提供常用指令快速鍵與指令歷史（方向鍵上/下回填）。
 */
class TerminalActivity : BaseActivity() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var sendBtn: View
    private val history = ArrayList<String>()
    private var historyIndex = -1
    private var sending = false

    private val obd by lazy { MainActivity.ObdManagerHolder.obd(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        output = findViewById(R.id.terminal_output)
        input = findViewById(R.id.terminal_input)
        status = findViewById(R.id.terminal_status)
        scroll = findViewById(R.id.terminal_scroll)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.terminal_clear).setOnClickListener {
            output.text = ""
            appendLine(getString(R.string.terminal_welcome), secondary = true)
        }
        sendBtn = findViewById(R.id.terminal_send)
        sendBtn.setOnClickListener { send() }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                send()
                true
            } else {
                false
            }
        }
        input.setOnKeyListener { _, keyCode, _ ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveHistory(-1); true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveHistory(1); true
                }
                else -> false
            }
        }

        buildQuickKeys()
        refreshStatus()
        appendLine(getString(R.string.terminal_welcome), secondary = true)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildQuickKeys() {
        val container = findViewById<LinearLayout>(R.id.terminal_quick_keys)
        val keys = listOf("ATZ", "ATE0", "ATSP0", "ATRV", "ATH0", "010C", "03", "09 02")
        keys.forEachIndexed { index, key ->
            val chip = TextView(this).apply {
                text = key
                setTextColor(getColor(R.color.primary))
                textSize = 13f
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { input.setText(key); input.setSelection(key.length); send() }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index < keys.lastIndex) lp.marginEnd = dp(8)
            container.addView(chip, lp)
        }
    }

    private fun refreshStatus() {
        val (textRes, colorRes) = when {
            obd.isDemoMode() -> R.string.terminal_status_demo to R.color.accent
            obd.isConnected() -> R.string.terminal_status_connected to R.color.success
            else -> R.string.terminal_status_disconnected to R.color.text_secondary
        }
        status.setText(textRes)
        status.setTextColor(getColor(colorRes))
    }

    private fun send() {
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty() || sending) return
        input.setText("")
        history.add(cmd)
        historyIndex = history.size
        appendLine("> $cmd")
        sending = true
        val busy = BusyUi.mark(sendBtn, getString(R.string.busy_sending))
        Thread {
            val response = obd.sendRawCommand(cmd)
            runOnUiThread {
                sending = false
                busy.done()
                if (response == null) {
                    if (!obd.isDemoMode() && !obd.isConnected()) {
                        appendLine(getString(R.string.terminal_not_connected), secondary = true)
                    } else {
                        appendLine(getString(R.string.terminal_no_response), secondary = true)
                    }
                } else {
                    appendLine(response)
                }
            }
        }.start()
    }

    private fun moveHistory(delta: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + delta).coerceIn(0, history.size)
        if (historyIndex == history.size) {
            input.setText("")
        } else {
            input.setText(history[historyIndex])
            input.setSelection(input.length())
        }
    }

    private fun appendLine(text: String, secondary: Boolean = false) {
        val spannable = SpannableString(text + "\n")
        if (secondary) {
            spannable.setSpan(
                ForegroundColorSpan(getColor(R.color.text_secondary)),
                0,
                text.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        output.append(spannable)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

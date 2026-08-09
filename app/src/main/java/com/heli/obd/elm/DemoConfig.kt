package com.heli.obd.elm

import android.content.Context

/**
 * Demo 模擬模式全域開關（SharedPreferences 持久化）。
 * 主畫面入口切換此設定後，呼叫 ObdManager.setDemoMode() 生效。
 */
object DemoConfig {

    private const val PREFS = "heliobd_demo"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}

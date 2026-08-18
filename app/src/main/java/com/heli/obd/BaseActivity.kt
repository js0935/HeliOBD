/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 所有 Activity 的基底。
 *
 * 注意：不再全域設定 FLAG_KEEP_SCREEN_ON，以避免非必要頁面消耗電池。
 * 需要保持螢幕長亮的 Activity（如 ObdMonitorActivity、HudActivity）
 * 請在自己的 onCreate 中呼叫 `window.addFlags(FLAG_KEEP_SCREEN_ON)`。
 */
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}

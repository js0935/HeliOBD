/* 軟體屬名：禾秝軟體開發團隊 / 代碼：洪俊士 / 版本：1.0.0 */
package com.heli.obd.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity
import com.heli.obd.R

/** 啟動畫面：顯示 Logo 1 秒後進入主畫面 */
class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }, 1000)
    }
}

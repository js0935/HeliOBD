/* 軟體屬名：禾秝軟體開發團隊 / 代碼：洪俊士 / 版本：1.0.0 */
package com.heli.obd.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.heli.obd.BaseActivity
import com.heli.obd.MainActivity

/**
 * 啟動畫面：以 Jetpack SplashScreen API 顯示品牌 Logo，
 * 停留 1 秒後進入主畫面（Android 12+ 為系統原生 Splash，低版本由 compat 層繪製）。
 *
 * 此 Activity 僅負責在系統 Splash 停留期間做初始化延遲，沒有自訂啟動畫面繪製，
 * 因此採用與 Android 12+ 相容的 Jetpack SplashScreen API（見 installSplashScreen）。
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必須在 super.onCreate() 之前呼叫，讓系統在內容就緒前維持 Splash
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        var ready = false
        splash.setKeepOnScreenCondition { !ready }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                ready = true
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }, 1000)
    }
}

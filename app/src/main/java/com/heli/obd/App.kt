package com.heli.obd

import android.app.Application
import com.heli.obd.license.LicenseManager

/**
 * HeliOBD —— App 進入點。
 *
 * 授權公鑰整合方式（重要）：
 * 1. 開啟 PC 端工具 LicenseKeyGenUI → 「產生 RSA-2048 金鑰對」
 * 2. 複製「內嵌公鑰」欄位的 base64 → 貼入下方 PUBLIC_KEY_B64
 * 3. 重新編譯 App 即完成金鑰綁定（私鑰只存在你的 PC，App 內無私鑰）
 */
class App : Application() {

    /** 全域授權管理員（所有功能閘門透過它判斷） */
    lateinit var license: LicenseManager
        private set

    override fun onCreate() {
        super.onCreate()
        license = LicenseManager(this, PUBLIC_KEY_B64)
    }

    companion object {
        /** LicenseKeyGenUI 產生的公鑰 base64（SubjectPublicKeyInfo DER） */
        const val PUBLIC_KEY_B64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwgb+Euqa3a+aYvE5HDPCXa2XpumCfIfh+8CxlbdBPq19rpdKWMj10cHmcJAVaUyG2k2hr1cq7EDMH9XK/Q3ruPbAYvXw3zctqBIEQT92d+acjttpU3GY69mWmFNmXsWHQVlk3WnGFa+f5EdVtotlogB8pjTp/TUJgH+nisq0SO9hdQL8iP6hncmfWDSwwnRV3LM2dU4V/VmqLKKZKiaw3OzBLG5RUki6uGzu6CJSDx8e44ZA5f3VpmKGZfOQ3NAiQlSlAUhr8wAQdSvQSkdCOi+csuQahTeMRZpQnhMBLLiEl6XTyoY3NRZN0+SFku2yXOGtfpohm2LUNrxSjoz3uQIDAQAB"

        fun from(context: android.content.Context): App =
            context.applicationContext as App
    }
}

/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.llm

import android.content.Context
import android.util.Base64
import com.heli.obd.elm.ObdManager

/**
 * LLM API 設定持久化（存於既有 obd_prefs SharedPreferences）。
 */
object LlmStore {

    private const val KEY_BASE_URL = "llm_base_url"
    private const val KEY_API_KEY = "llm_api_key"
    private const val KEY_MODEL = "llm_model"

    const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
    const val DEFAULT_MODEL = "nvidia/nemotron-3-ultra-550b-a55b"

    /**
     * 混淆後的 API Key（翻轉 + Base64），防止 APK 被掃描工具直接撈到明文。
     * 還原流程：Base64 decode → reversed() → 明文
     */
    private val _obfuscatedKey = "NjF6RlRyUjQtYnc1eks1eThOeTUzMDUzTU5oTU9lNjhoY3BwUkN5bHNwSUJyNVFPVmpwV3NBdUk4c3ltUy0xSC1pcGF2bg=="

    private val decodedApiKey: String by lazy {
        String(Base64.decode(_obfuscatedKey, Base64.DEFAULT)).reversed()
    }

    private fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(ObdManager.PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): LlmClient.Config = LlmClient.Config(
        baseUrl = prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty(),
        apiKey = prefs(context).getString(KEY_API_KEY, decodedApiKey).orEmpty(),
        model = prefs(context).getString(KEY_MODEL, DEFAULT_MODEL).orEmpty(),
    )

    fun isConfigured(context: Context): Boolean = load(context).apiKey.isNotBlank()

    fun save(context: Context, config: LlmClient.Config) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_MODEL, config.model.trim())
            .apply()
    }
}

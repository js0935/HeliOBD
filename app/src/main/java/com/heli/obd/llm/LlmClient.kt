/*
 * 軟體屬名：禾秝軟體開發團隊
 * 代碼：洪俊士
 * 版本：1.0.0
 */
package com.heli.obd.llm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 相容 Chat Completions 客戶端。
 *
 * 預設使用 NVIDIA NIM 端點（integrate.api.nvidia.com/v1），支援 100+ 模型。
 * 相容任何 OpenAI 相容 API（官方、Azure、Groq、Ollama、自架 vLLM 等）。
 * 純 JVM 邏輯（無 Android 依賴），可在 JVM 上單元測試。
 */
object LlmClient {

    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    class LlmException(message: String) : Exception(message)

    /**
     * 送出 chat completion 請求並回傳 assistant 回覆文字。
     * 若模型回傳 `reasoning_content` 而 `content` 為空，自動 fallback。
     * @throws LlmException 網路失敗或 API 回傳錯誤
     */
    fun chat(
        config: Config,
        systemPrompt: String,
        userPrompt: String,
        timeoutMs: Int = 120_000,
    ): String {
        val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")

            val body = JSONObject()
                .put("model", config.model)
                .put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userPrompt)),
                )
                .put("temperature", 0.3)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw LlmException("HTTP $code: ${raw.take(300)}")
            }
            val json = JSONObject(raw)
            val message = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
            // content 為空時嘗試 reasoning_content（NVIDIA NIM 推理模型）
            val content = message.optString("content", "").trim()
            if (content.isNotBlank()) return content
            val reasoning = message.optString("reasoning_content", "").trim()
                .ifBlank { message.optString("reasoning", "").trim() }
            if (reasoning.isNotBlank()) return reasoning
            throw LlmException("模型回傳空回應")
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException(e.message ?: "網路連線失敗")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 建構診斷提示詞（純函數，便於測試）：
     * 以繁體中文說明 DTC 的白話意義、可能原因與建議維修方向。
     */
    fun buildDiagnosisPrompt(
        dtcCodes: List<String>,
        symptomNames: List<String>,
        liveDataSummary: String?,
    ): String {
        val sb = StringBuilder()
        if (dtcCodes.isNotEmpty()) {
            sb.append("故障碼：").append(dtcCodes.joinToString("、")).append('\n')
        }
        if (symptomNames.isNotEmpty()) {
            sb.append("症狀：").append(symptomNames.joinToString("、")).append('\n')
        }
        liveDataSummary?.let { sb.append("即時數據：").append(it).append('\n') }
        return sb.toString().trim()
    }
}

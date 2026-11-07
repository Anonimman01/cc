package com.example.aiblocker

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Минимальный клиент Groq (OpenAI-совместимый /chat/completions) без внешних библиотек. */
object GroqClient {

    data class Verdict(val channel: String, val category: String, val confidence: Double)

    private const val TAG = "AiBlocker"

    @Volatile var lastError: String = ""
    @Volatile var retryAfterSec: Int = 30
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    // Формат ответа задаём в коде, чтобы правка промпта в настройках его не сломала
    private const val FORMAT = """

Каждая карточка дана строкой вида [номер] текст. Текст может содержать название видео, имя канала, начало описания канала, длительность, число просмотров. Карточка может быть и страницей видео с описанием: тогда опирайся и на описание.
Верни ТОЛЬКО JSON-объект такого вида и ничего больше:
{"results":[{"id":номер,"channel":"имя канала или пустая строка","category":"ai_generated|mini_drama_reupload|normal","confidence":число от 0 до 1}]}
Верни по одному элементу на каждую карточку."""

    /**
     * Пробует основной ключ и модель; при ошибке (сеть, лимит, неверный ключ) повторяет запрос
     * с запасными. Пустой запасной ключ означает «использовать основной», пустая модель -
     * «использовать основную». Если запасных нет вовсе, повтора не будет.
     */
    fun classifyFallback(
        apiKey: String, model: String,
        apiKey2: String, model2: String,
        prompt: String, items: List<String>, maxLen: Int = 500
    ): Map<Int, Verdict>? {
        val first = classify(apiKey, model, prompt, items, maxLen)
        if (first != null) return first
        if (apiKey2.isBlank() && model2.isBlank()) return null
        val k = apiKey2.ifBlank { apiKey }
        val m = model2.ifBlank { model }
        if (k == apiKey && m == model) return null
        Log.d(TAG, "fallback to backup key/model")
        return classify(k, m, prompt, items, maxLen)
    }

    /**
     * Возвращает вердикты по индексу карточки или null при любой ошибке (сеть, лимит, неверный ключ).
     * maxLen: сколько символов текста одной карточки отправлять (для страниц видео больше).
     */
    fun classify(
        apiKey: String,
        model: String,
        prompt: String,
        items: List<String>,
        maxLen: Int = 500
    ): Map<Int, Verdict>? {
        try {
            val user = items.mapIndexed { i, t ->
                "[$i] " + t.replace('\n', ' ').take(maxLen)
            }.joinToString("\n")

            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt + FORMAT))
                .put(JSONObject().put("role", "user").put("content", user))
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", messages)

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "Groq HTTP ${conn.responseCode}")
                lastError = "HTTP ${conn.responseCode} ($model)"
                retryAfterSec = conn.getHeaderField("retry-after")?.toDoubleOrNull()?.toInt()?.plus(1) ?: 30
                return null
            }

            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            var content = JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            if (content.startsWith("```")) {
                content = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            }
            val arr = JSONObject(content).getJSONArray("results")
            val out = HashMap<Int, Verdict>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optInt("id", -1)
                if (id < 0) continue
                out[id] = Verdict(
                    o.optString("channel", ""),
                    o.optString("category", "normal"),
                    o.optDouble("confidence", 0.0)
                )
            }
            return out
        } catch (e: Exception) {
            Log.w(TAG, "Groq error: $e")
            lastError = e.javaClass.simpleName
            retryAfterSec = 30
            return null
        }
    }
}

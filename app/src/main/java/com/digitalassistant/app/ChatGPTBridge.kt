
package com.digitalassistant.app

import android.app.Activity
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ChatGPTBridge(
    private val activity: Activity,
    private val auth: ChatGPTAuth
) {
    private val executor = Executors.newSingleThreadExecutor()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val prefs = activity.getSharedPreferences(
        "chatgpt_auth",
        Activity.MODE_PRIVATE
    )

    private val history = JSONArray()

    private val mediaType =
        "application/json; charset=utf-8".toMediaType()

    @JavascriptInterface
    fun isConnected(): Boolean {
        return auth.isConnected()
    }

    @JavascriptInterface
    fun connectChatGPT() {
        auth.startSignIn(object : ChatGPTAuth.Callback {
            override fun onSuccess(account: String) {
                activity.runOnUiThread {
                    activityAction(
                        "onChatGPTConnected",
                        JSONObject.quote("ChatGPT подключён")
                    )
                }
            }

            override fun onError(message: String) {
                activity.runOnUiThread {
                    activityAction(
                        "onChatGPTError",
                        JSONObject.quote(message)
                    )
                }
            }
        })
    }

    @JavascriptInterface
    fun disconnectChatGPT() {
        auth.disconnect()

        synchronized(history) {
            while (history.length() > 0) {
                history.remove(0)
            }
        }

        activity.runOnUiThread {
            activityAction("onChatGPTDisconnected")
        }
    }

    @JavascriptInterface
    fun sendMessage(
        requestId: String,
        message: String,
        persona: String
    ) {
        if (message.isBlank()) {
            sendError(requestId, "Введите сообщение")
            return
        }

        if (!auth.isConnected()) {
            sendError(requestId, "Сначала подключите ChatGPT")
            return
        }

        executor.execute {
            try {
                var credentials = readCredentials()

                if (
                    System.currentTimeMillis() >=
                    credentials.optLong("expires_at", 0) - 60000
                ) {
                    credentials = refreshToken(credentials)
                    saveCredentials(credentials)
                }

                val accessToken =
                    credentials.getString("access_token")

                val model = getAvailableModel(accessToken)

                val input = JSONArray()

                synchronized(history) {
                    for (i in 0 until history.length()) {
                        input.put(history.getJSONObject(i))
                    }
                }

                input.put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", message)
                    }
                )

                val instructions =
                    "Ты — $persona, цифровой голосовой помощник. " +
                    "Отвечай по-русски, естественно и дружелюбно. " +
                    "Обычно отвечай кратко и по существу. " +
                    "Не используй Markdown, эмодзи и ссылки. " +
                    "Не выдумывай факты."

                val body = JSONObject().apply {
                    put("model", model)
                    put("instructions", instructions)
                    put("input", input)
                    put("store", false)
                    put("stream", true)
                }

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/responses")
                    .header(
                        "Authorization",
                        "Bearer $accessToken"
                    )
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()

                val answer = StringBuilder()
                var completed = false

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorText =
                            response.body?.string().orEmpty()

                        throw Exception(
                            extractError(errorText, response.code)
                        )
                    }

                    val source = response.body?.source()
                        ?: throw Exception("Пустой ответ сервера")

                    while (true) {
                        val line = source.readUtf8Line() ?: break

                        if (!line.startsWith("data:")) continue

                        val payload = line
                            .removePrefix("data:")
                            .trim()

                        if (payload.isBlank() || payload == "[DONE]") {
                            continue
                        }

                        val event = JSONObject(payload)
                        when (event.optString("type")) {
                            "response.output_text.delta" -> {
                                answer.append(
                                    event.optString("delta")
                                )
                            }

                            "response.failed" -> {
                                val error = event
                                    .optJSONObject("response")
                                    ?.optJSONObject("error")

                                throw Exception(
                                    error?.optString("message")
                                        ?: "Ошибка генерации ChatGPT"
                                )
                            }

                            "response.incomplete" -> {
                                throw Exception(
                                    "Ответ ChatGPT не был завершён"
                                )
                            }

                            "error" -> {
                                throw Exception(
                                    event.optJSONObject("error")
                                        ?.optString("message")
                                        ?: "Ошибка потока ответа"
                                )
                            }

                            "response.completed" -> {
                                completed = true
                            }
                        }
                    }
                }

                if (!completed) {
                    throw Exception(
                        "Соединение прервано до завершения ответа"
                    )
                }

                val finalAnswer = answer.toString().trim()

                if (finalAnswer.isBlank()) {
                    throw Exception("ChatGPT вернул пустой ответ")
                }

                synchronized(history) {
                    history.put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", message)
                        }
                    )

                    history.put(
                        JSONObject().apply {
                            put("role", "assistant")
                            put("content", finalAnswer)
                        }
                    )

                    while (history.length() > 12) {
                        history.remove(0)
                    }
                }

                activity.runOnUiThread {
                    activityAction(
                        "onChatGPTReply",
                        JSONObject.quote(requestId),
                        JSONObject.quote(finalAnswer)
                    )
                }

            } catch (e: Exception) {
                sendError(
                    requestId,
                    e.message ?: "Не удалось получить ответ"
                )
            }
        }
    }

    private fun getAvailableModel(token: String): String {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw Exception(
                    extractError(text, response.code)
                )
            }

            val data = JSONObject(text)
            val models = data.optJSONArray("models")
                ?: throw Exception("Список моделей недоступен")

            for (i in 0 until models.length()) {
                val item = models.optJSONObject(i) ?: continue

                if (item.optString("visibility") == "list") {
                    val slug = item.optString("slug")

                    if (slug.isNotBlank()) return slug
                }
            }

            throw Exception(
                "Для этого аккаунта не найдено доступных моделей"
            )
        }
    }

    private fun refreshToken(old: JSONObject): JSONObject {
        val refresh = old.optString("refresh_token")

        if (refresh.isBlank()) {
            throw Exception(
                "Сессия истекла. Подключите ChatGPT заново."
            )
        }

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", old.getString("client_id"))
            .add("refresh_token", refresh)
            .add("resource", "https://api.openai.com/v1")
            .build()

        val request = Request.Builder()
            .url("https://auth.openai.com/api/accounts/oauth/token")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw Exception(
                    "Не удалось обновить сессию. " +
                    "Подключите ChatGPT заново."
                )
            }

            val fresh = JSONObject(text)

            old.put(
                "access_token",
                fresh.getString("access_token")
            )

            if (fresh.has("refresh_token")) {
                old.put(
                    "refresh_token",
                    fresh.getString("refresh_token")
                )
            }

            old.put(
                "expires_at",
                System.currentTimeMillis() +
                    fresh.optLong("expires_in", 3600) * 1000
            )

            return old
        }
    }

    private fun readCredentials(): JSONObject {
        val encrypted = prefs.getString("credentials", null)
            ?: throw Exception("ChatGPT не подключён")

        val combined = Base64.decode(
            encrypted,
            Base64.NO_WRAP
        )

        if (combined.size <= 12) {
            throw Exception("Повреждены данные авторизации")
        }

        val iv = combined.copyOfRange(0, 12)
        val data = combined.copyOfRange(12, combined.size)

        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val key = keyStore.getKey(
            "digital_face_chatgpt_key",
            null
        ) as? SecretKey
            ?: throw Exception("Ключ защищённого хранилища не найден")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, iv)
        )

        return JSONObject(
            String(
                cipher.doFinal(data),
                Charsets.UTF_8
            )
        )
    }

    private fun saveCredentials(data: JSONObject) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val key = keyStore.getKey(
            "digital_face_chatgpt_key",
            null
        ) as? SecretKey
            ?: throw Exception("Ключ защищённого хранилища не найден")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val encrypted = cipher.doFinal(
            data.toString().toByteArray(Charsets.UTF_8)
        )

        val combined = cipher.iv + encrypted

        prefs.edit()
            .putString(
                "credentials",
                Base64.encodeToString(
                    combined,
                    Base64.NO_WRAP
                )
            )
            .apply()
    }

    private fun extractError(
        text: String,
        status: Int
    ): String {
        return try {
            val json = JSONObject(text)
            json.optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: json.optString("detail")
                    .takeIf { it.isNotBlank() }
                ?: "Ошибка сервера: $status"
        } catch (_: Exception) {
            "Ошибка сервера: $status"
        }
    }

    private fun sendError(id: String, message: String) {
        activity.runOnUiThread {
            activityAction(
                "onChatGPTError",
                JSONObject.quote(message),
                JSONObject.quote(id)
            )
        }
    }

    private fun activityAction(
        function: String,
        vararg arguments: String
    ) {
        val args = arguments.joinToString(",")
        val script = "window.$function($args);"

        val field = activity.javaClass
            .getDeclaredField("webView")

        field.isAccessible = true

        val webView = field.get(activity) as? WebView
        webView?.evaluateJavascript(script, null)
    }

    fun close() {
        executor.shutdownNow()
    }
}

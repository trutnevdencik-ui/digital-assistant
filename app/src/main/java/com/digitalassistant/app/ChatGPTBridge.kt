package com.digitalassistant.app

import android.app.Activity
import android.util.Base64
import android.webkit.JavascriptInterface
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
        .readTimeout(90, TimeUnit.SECONDS)
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
            activityAction("onChatGPTDisconnected", "true")
        }
    }

    @JavascriptInterface
    fun sendMessage(requestId: String, message: String, persona: String) {
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
                val credentials = readCredentials()
                var accessToken = credentials.getString("access_token")

                val expiresAt = credentials.optLong("expires_at", 0)

                if (System.currentTimeMillis() >= expiresAt - 60000) {
                    credentials = refreshToken(credentials)
                    accessToken = credentials.getString("access_token")
                    saveCredentials(credentials)
                }

                val messages = synchronized(history) {
                    history.put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", message)
                        }
                    )

                    while (history.length() > 12) {
                        history.remove(0)
                    }

                    JSONArray(history.toString())
                }

                val instructions =
                    "Ты — $persona, цифровой голосовой помощник. " +
                    "Отвечай по-русски, естественно и дружелюбно. " +
                    "Обычно отвечай кратко, по существу, без Markdown, " +
                    "эмодзи и ссылок. Не выдумывай факты."

                val input = JSONArray()

                for (i in 0 until messages.length()) {
                    val item = messages.getJSONObject(i)

                    input.put(
                        JSONObject().apply {
                            put("role", item.getString("role"))
                            put("content", item.getString("content"))
                        }
                    )
                }

                val body = JSONObject().apply {
                    put("model", "gpt-5")
                    put("instructions", instructions)
                    put("input", input)
                    put("store", false)
                    put("stream", false)
                }

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/responses")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(mediaType))
                    .build()

                val answer: String

                client.newCall(request).execute().use { response ->
                    val result = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        val error = JSONObject(result)
                            .optJSONObject("error")
                            ?.optString("message")
                            ?: "Ошибка ChatGPT: ${response.code}"

                        throw Exception(error)
                    }

                    answer = extractAnswer(JSONObject(result))
                }

                if (answer.isBlank()) {
                    throw Exception("ChatGPT вернул пустой ответ")
                }

                synchronized(history) {
                    history.put(
                        JSONObject().apply {
                            put("role", "assistant")
                            put("content", answer)
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
                        JSONObject.quote(answer)
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

    private fun extractAnswer(data: JSONObject): String {
        val direct = data.optString("output_text")
        if (direct.isNotBlank()) return direct.trim()

        val output = data.optJSONArray("output")
            ?: throw Exception("В ответе отсутствует текст")

        val result = StringBuilder()

        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue

            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue

                if (part.optString("type") == "output_text") {
                    result.append(part.optString("text"))
                }
            }
        }

        return result.toString().trim()
    }

    private fun refreshToken(old: JSONObject): JSONObject {
        val refresh = old.optString("refresh_token")
        if (refresh.isBlank()) {
            throw Exception("Нужно заново подключить ChatGPT")
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
            val result = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw Exception(
                    "Сессия ChatGPT истекла. Подключите аккаунт заново."
                )
            }

            val fresh = JSONObject(result)

            old.put("access_token", fresh.getString("access_token"))

            if (fresh.has("refresh_token")) {
                old.put("refresh_token", fresh.getString("refresh_token"))
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

        val combined = Base64.decode(encrypted, Base64.NO_WRAP)

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
            ?: throw Exception("Не найден ключ защищённого хранилища")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, iv)
        )

        return JSONObject(
            String(cipher.doFinal(data), Charsets.UTF_8)
        )
    }

    private fun saveCredentials(data: JSONObject) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val key = keyStore.getKey(
            "digital_face_chatgpt_key",
            null
        ) as? SecretKey
            ?: throw Exception("Не найден ключ защищённого хранилища")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val encrypted = cipher.doFinal(
            data.toString().toByteArray(Charsets.UTF_8)
        )

        val combined = cipher.iv + encrypted

        prefs.edit()
            .putString(
                "credentials",
                Base64.encodeToString(combined, Base64.NO_WRAP)
            )
            .apply()
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

        // MainActivity подключит WebView и вызов этого метода.
        val webViewField = activity.javaClass
            .getDeclaredField("webView")

        webViewField.isAccessible = true

        val webView = webViewField.get(activity)
            as? android.webkit.WebView

        webView?.evaluateJavascript(script, null)
    }

    fun close() {
        executor.shutdownNow()
    }
}

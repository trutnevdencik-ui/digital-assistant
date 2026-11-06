
package com.digitalassistant.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ChatGPTAuth(private val activity: Activity) {

    companion object {
        private const val AUTH_URL =
            "https://auth.openai.com/api/accounts/authorize"
        private const val TOKEN_URL =
            "https://auth.openai.com/api/accounts/oauth/token"
        private const val JWKS_URL =
            "https://auth.openai.com/.well-known/jwks.json"
        private const val RESOURCE =
            "https://api.openai.com/v1"
        private const val KEY_ALIAS =
            "digital_face_chatgpt_key"
        private const val DYNAMIC_CLIENT_ID =
            "dynamic_agent_client"
    }

    private val executor = Executors.newSingleThreadExecutor()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val preferences = activity.getSharedPreferences(
        "chatgpt_auth",
        Activity.MODE_PRIVATE
    )

    interface Callback {
        fun onSuccess(account: String)
        fun onError(message: String)
    }

    private data class Attempt(
        val state: String,
        val nonce: String,
        val verifier: String,
        val redirectUri: String,
        val clientId: String,
        val isNew: Boolean,
        val idTokenHint: String?
    )

    fun isConnected(): Boolean {
        return try {
            val data = readCredentials() ?: return false

            data.optString("access_token").isNotBlank() &&
                data.optString("client_id").isNotBlank() &&
                data.optString("subject").isNotBlank() &&
                data.optString("scope")
                    .split(" ")
                    .contains("chatgpt.tokens.use.direct")
        } catch (_: Exception) {
            false
        }
    }

    fun startSignIn(callback: Callback) {
        executor.execute {
            var server: ServerSocket? = null

            try {
                val hostId = preferences.getString(
                    "host_id",
                    null
                ) ?: (
                    "urn:uuid:" + UUID.randomUUID().toString()
                ).also {
                    preferences.edit()
                        .putString("host_id", it)
                        .commit()
                }

                val savedClientId = preferences.getString(
                    "registered_client_id",
                    null
                )?.takeIf {
                    it.isNotBlank() &&
                        it != DYNAMIC_CLIENT_ID
                }

                val previous = try {
                    readCredentials()
                } catch (_: Exception) {
                    null
                }

                val state = randomValue(32)
                val nonce = randomValue(32)
                val verifier = randomValue(64)

                val challenge = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256")
                        .digest(
                            verifier.toByteArray(Charsets.US_ASCII)
                        ),
                    Base64.URL_SAFE or
                        Base64.NO_PADDING or
                        Base64.NO_WRAP
                )

                server = ServerSocket(
                    0,
                    1,
                    InetAddress.getByName("127.0.0.1")
                )

                server.soTimeout = 180000

                val redirectUri =
                    "http://127.0.0.1:${server.localPort}/auth/callback"

                val isNew = savedClientId == null

                val clientId =
                    savedClientId ?: DYNAMIC_CLIENT_ID

                val attempt = Attempt(
                    state = state,
                    nonce = nonce,
                    verifier = verifier,
                    redirectUri = redirectUri,
                    clientId = clientId,
                    isNew = isNew,
                    idTokenHint = previous
                        ?.optString("id_token")
                        ?.takeIf { it.isNotBlank() }
                )

                val params = linkedMapOf(
                    "client_id" to attempt.clientId,
                    "ext_agent_host_id" to hostId,
                    "response_type" to "code",
                    "redirect_uri" to redirectUri,
                    "scope" to (
                        "openid profile email offline_access " +
                            "resource.invoke chatgpt.tokens.use.direct"
                    ),
                    "resource" to RESOURCE,
                    "state" to state,
                    "nonce" to nonce,
                    "code_challenge_method" to "S256",
                    "code_challenge" to challenge
                )

                if (isNew) {
                    params["agent_name_hint"] = "Цифровое лицо"
                } else if (!attempt.idTokenHint.isNullOrBlank()) {
                    params["id_token_hint"] = attempt.idTokenHint
                }

                val authorizationUrl =
                    AUTH_URL + "?" +
                        params.entries.joinToString("&") {
                            Uri.encode(it.key) +
                                "=" +
                                Uri.encode(it.value)
                        }

                val listener = server

                activity.runOnUiThread {
                    try {
                        activity.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(authorizationUrl)
                            )
                        )
                    } catch (e: Exception) {
                        callback.onError(
                            "Не удалось открыть браузер: " +
                                (e.message ?: "ошибка")
                        )
                    }
                }

                val socket = listener.accept()
                socket.soTimeout = 15000

                val reader = socket.getInputStream().bufferedReader()

                val requestLine = reader.readLine()
                    ?: throw Exception(
                        "Браузер не вернул ответ авторизации"
                    )

                val requestPath = requestLine
                    .split(" ")
                    .getOrNull(1)
                    ?: throw Exception(
                        "Неверный адрес возврата"
                    )

                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }

                val callbackUri = Uri.parse(
                    "http://127.0.0.1$requestPath"
                )

                val returnedState =
                    callbackUri.getQueryParameter("state")

                val error =
                    callbackUri.getQueryParameter("error")

                val code =
                    callbackUri.getQueryParameter("code")

                val callbackClientId =
                    callbackUri.getQueryParameter("client_id")

                val page =
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Connection: close\r\n\r\n" +
                    "<!doctype html><html lang='ru'>" +
                    "<meta charset='utf-8'>" +
                    "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<body style='font-family:Arial;text-align:center;padding:36px;background:#111;color:#fff'>" +
                    "<h2>Ответ получен</h2>" +
                    "<p>Приложение проверяет подключение. " +
                    "Вернитесь в него и дождитесь результата.</p>" +
                    "</body></html>"

                socket.getOutputStream().write(
                    page.toByteArray(Charsets.UTF_8)
                )
                socket.getOutputStream().flush()
                socket.close()

                listener.close()
                server = null

                if (returnedState != state) {
                    throw Exception(
                        "Проверка безопасности не пройдена. " +
                            "Начните вход заново."
                    )
                }

                if (!error.isNullOrBlank()) {
                    throw Exception(
                        if (error == "access_denied") {
                            "Вы отменили разрешение на подключение."
                        } else {
                            "OpenAI отклонил авторизацию: $error"
                        }
                    )
                }

                if (code.isNullOrBlank()) {
                    throw Exception(
                        "OpenAI не вернул код авторизации. " +
                            "Попробуйте ещё раз."
                    )
                }

                val issuedClientId = if (isNew) {
                    callbackClientId?.takeIf {
                        it.isNotBlank() &&
                            it != DYNAMIC_CLIENT_ID
                    } ?: throw Exception(
                        "OpenAI не вернул идентификатор регистрации. " +
                            "Вход не завершён."
                    )
                } else {
                    if (
                        !callbackClientId.isNullOrBlank() &&
                        callbackClientId != clientId
                    ) {
                        throw Exception(
                            "OpenAI вернул другой идентификатор регистрации."
                        )
                    }

                    clientId
                }

                // Сохраняем выданный ID до обмена одноразового кода.
                preferences.edit()
                    .putString(
                        "registered_client_id",
                        issuedClientId
                    )
                    .commit()

                val tokens = exchangeCode(
                    code,
                    verifier,
                    redirectUri,
                    issuedClientId
                )

                val idToken = tokens.optString("id_token")
                    .takeIf { it.isNotBlank() }
                    ?: throw Exception(
                        "OpenAI не вернул токен подтверждения аккаунта."
                    )

                val accessToken = tokens.optString("access_token")
                    .takeIf { it.isNotBlank() }
                    ?: throw Exception(
                        "OpenAI не вернул токен доступа."
                    )

                val scopes = tokens.optString("scope")
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }

                if (!scopes.contains("chatgpt.tokens.use.direct")) {
                    throw Exception(
                        "Аккаунт вошёл, но разрешение на использование " +
                            "плана ChatGPT не выдано."
                    )
                }

                val account = validateIdToken(
                    idToken,
                    nonce,
                    issuedClientId
                )

                if (
                    !isNew &&
                    previous != null &&
                    previous.optString("subject").isNotBlank() &&
                    previous.optString("subject") != account
                ) {
                    throw Exception(
                        "Вы вошли в другой аккаунт. " +
                            "Существующее подключение не заменено."
                    )
                }

                val credentials = JSONObject().apply {
                    put("client_id", issuedClientId)
                    put("id_token", idToken)
                    put("access_token", accessToken)
                    put(
                        "refresh_token",
                        tokens.optString("refresh_token")
                    )
                    put("subject", account)
                    put("scope", scopes.joinToString(" "))
                    put(
                        "expires_at",
                        System.currentTimeMillis() +
                            tokens.optLong("expires_in", 3600) * 1000
                    )
                }

                saveCredentials(credentials)

                if (!isConnected()) {
                    throw Exception(
                        "Авторизация прошла, но сохранить подключение " +
                            "на телефоне не удалось."
                    )
                }

                activity.runOnUiThread {
                    callback.onSuccess(account)
                }

            } catch (e: Exception) {
                activity.runOnUiThread {
                    callback.onError(
                        e.message
                            ?: "Не удалось подключить ChatGPT."
                    )
                }
            } finally {
                try {
                    server?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun exchangeCode(
        code: String,
        verifier: String,
        redirectUri: String,
        clientId: String
    ): JSONObject {

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", redirectUri)
            .add("resource", RESOURCE)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val detail = try {
                    val json = JSONObject(text)

                    json.optString("error_description")
                        .ifBlank {
                            json.optJSONObject("error")
                                ?.optString("message")
                                .orEmpty()
                        }
                        .ifBlank {
                            json.optString("error")
                        }
                } catch (_: Exception) {
                    ""
                }

                throw Exception(
                    "Ошибка входа OpenAI (${response.code})" +
                        if (detail.isNotBlank()) {
                            ": $detail"
                        } else {
                            ". Попробуйте ещё раз."
                        }
                )
            }

            return JSONObject(text)
        }
    }

    private fun validateIdToken(
        token: String,
        expectedNonce: String,
        clientId: String
    ): String {

        val jwt = SignedJWT.parse(token)
        val jwks = JWKSet.load(java.net.URL(JWKS_URL))

        val key = jwks.keys.firstOrNull {
            it.keyID == jwt.header.keyID
        } as? RSAKey
            ?: throw Exception(
                "Не найден ключ проверки безопасности OpenAI."
            )

        if (!jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))) {
            throw Exception(
                "Подпись токена OpenAI недействительна."
            )
        }

        val claims = jwt.jwtClaimsSet

        if (claims.issuer != "https://auth.openai.com") {
            throw Exception(
                "Не удалось подтвердить издателя токена."
            )
        }

        if (!claims.audience.contains(clientId)) {
            throw Exception(
                "Токен выдан для другого приложения."
            )
        }

        if (
            claims.expirationTime == null ||
            claims.expirationTime.before(Date())
        ) {
            throw Exception(
                "Срок действия токена подтверждения истёк."
            )
        }

        if (claims.getStringClaim("nonce") != expectedNonce) {
            throw Exception(
                "Проверка nonce не пройдена."
            )
        }

        return claims.subject
            ?: throw Exception(
                "В токене отсутствует идентификатор аккаунта."
            )
    }

    private fun randomValue(bytes: Int): String {
        val random = ByteArray(bytes)
        SecureRandom().nextBytes(random)

        return Base64.encodeToString(
            random,
            Base64.URL_SAFE or
                Base64.NO_PADDING or
                Base64.NO_WRAP
        )
    }

    private fun saveCredentials(data: JSONObject) {
        val encrypted = encrypt(data.toString())

        val saved = preferences.edit()
            .putString("credentials", encrypted)
            .putString(
                "registered_client_id",
                data.getString("client_id")
            )
            .commit()

        if (!saved) {
            throw Exception(
                "Android не смог сохранить данные подключения."
            )
        }
    }

    private fun readCredentials(): JSONObject? {
        val value = preferences.getString(
            "credentials",
            null
        ) ?: return null

        return JSONObject(decrypt(value))
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build()
        )

        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            getSecretKey()
        )

        val encrypted = cipher.doFinal(
            value.toByteArray(Charsets.UTF_8)
        )

        return Base64.encodeToString(
            cipher.iv + encrypted,
            Base64.NO_WRAP
        )
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(
            value,
            Base64.NO_WRAP
        )

        if (combined.size <= 12) {
            throw Exception(
                "Сохранённые данные подключения повреждены."
            )
        }

        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(
            12,
            combined.size
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.DECRYPT_MODE,
            getSecretKey(),
            GCMParameterSpec(128, iv)
        )

        return String(
            cipher.doFinal(encrypted),
            Charsets.UTF_8
        )
    }

    fun disconnect() {
        preferences.edit()
            .remove("credentials")
            .remove("registered_client_id")
            .apply()
    }

    fun close() {
        executor.shutdownNow()
    }
}

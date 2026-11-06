package com.digitalassistant.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.JavascriptInterface
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
import java.net.URLDecoder
import java.net.URLEncoder
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
    }

    private val executor = Executors.newSingleThreadExecutor()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val preferences = activity.getSharedPreferences(
        "chatgpt_auth",
        Activity.MODE_PRIVATE
    )

    interface Callback {
        fun onSuccess(account: String)
        fun onError(message: String)
    }

    private data class OAuthAttempt(
        val state: String,
        val nonce: String,
        val verifier: String,
        val redirectUri: String,
        val clientId: String
    )

    fun isConnected(): Boolean {
        return try {
            readCredentials() != null
        } catch (_: Exception) {
            false
        }
    }

    fun startSignIn(callback: Callback) {
        executor.execute {
            try {
                val hostId = preferences.getString(
                    "host_id",
                    null
                ) ?: ("urn:uuid:" + UUID.randomUUID().toString()).also {
                    preferences.edit().putString("host_id", it).apply()
                }

                val state = randomValue(32)
                val nonce = randomValue(32)
                val verifier = randomValue(64)

                val challenge = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256")
                        .digest(verifier.toByteArray(Charsets.US_ASCII)),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )

                val server = ServerSocket(
                    0,
                    1,
                    InetAddress.getByName("127.0.0.1")
                )

                server.soTimeout = 180000

                val redirectUri =
                    "http://127.0.0.1:${server.localPort}/auth/callback"

                val attempt = OAuthAttempt(
                    state = state,
                    nonce = nonce,
                    verifier = verifier,
                    redirectUri = redirectUri,
                    clientId = "dynamic_agent_client"
                )

                val params = linkedMapOf(
                    "client_id" to attempt.clientId,
                    "agent_name_hint" to "Цифровое лицо",
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

                val authorizationUrl = AUTH_URL + "?" +
                    params.entries.joinToString("&") {
                        encode(it.key) + "=" + encode(it.value)
                    }

                activity.runOnUiThread {
                    try {
                        activity.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(authorizationUrl)
                            )
                        )
                    } catch (e: Exception) {
                        server.close()
                        callback.onError(
                            "Не удалось открыть браузер: ${e.message}"
                        )
                    }
                }

                val socket = server.accept()
                val requestLine = socket.getInputStream()
                    .bufferedReader()
                    .readLine() ?: throw Exception("Пустой ответ браузера")

                val requestPath = requestLine
                    .split(" ")
                    .getOrNull(1)
                    ?: throw Exception("Неверный адрес возврата")

                val callbackUri = Uri.parse(
                    "http://127.0.0.1$requestPath"
                )

                val returnedState =
                    callbackUri.getQueryParameter("state")

                val error =
                    callbackUri.getQueryParameter("error")

                val code =
                    callbackUri.getQueryParameter("code")

                val issuedClientId =
                    callbackUri.getQueryParameter("client_id")

                val response = """
                    HTTP/1.1 200 OK
                    Content-Type: text/html; charset=utf-8
                    Connection: close

                    <!doctype html>
                    <html lang="ru">
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width">
                    <body style="font-family:sans-serif;text-align:center;padding:40px">
                    <h2>Авторизация завершена</h2>
                    <p>Можно вернуться в приложение.</p>
                    </body>
                    </html>

                """.trimIndent()

                socket.getOutputStream().write(
                    response.toByteArray(Charsets.UTF_8)
                )
                socket.close()
                server.close()

                if (returnedState != state) {
                    throw Exception("Проверка безопасности не пройдена")
                }

                if (!error.isNullOrBlank()) {
                    throw Exception(
                        if (error == "access_denied") {
                            "Вход был отменён."
                        } else {
                            "Ошибка авторизации: $error"
                        }
                    )
                }

                if (code.isNullOrBlank()) {
                    throw Exception("Браузер не вернул код авторизации")
                }

                if (issuedClientId.isNullOrBlank() ||
                    issuedClientId == "dynamic_agent_client"
                ) {
                    throw Exception(
                        "Не получен регистрационный идентификатор ChatGPT"
                    )
                }

                val tokens = exchangeCode(
                    code,
                    attempt.verifier,
                    attempt.redirectUri,
                    issuedClientId
                )

                val idToken = tokens.getString("id_token")
                val accessToken = tokens.getString("access_token")
                val refreshToken = tokens.optString("refresh_token")
                val scopes = tokens.optString("scope")
                    .split(" ")
                    .filter { it.isNotBlank() }

                if (!scopes.contains("chatgpt.tokens.use.direct")) {
                    throw Exception(
                        "Аккаунт не разрешил использование плана ChatGPT"
                    )
                }

                val account = validateIdToken(
                    idToken,
                    nonce,
                    issuedClientId
                )

                val credentials = JSONObject().apply {
                    put("client_id", issuedClientId)
                    put("id_token", idToken)
                    put("access_token", accessToken)
                    put("refresh_token", refreshToken)
                    put("subject", account)
                    put("scope", scopes.joinToString(" "))
                    put(
                        "expires_at",
                        System.currentTimeMillis() +
                            tokens.optLong("expires_in", 3600) * 1000
                    )
                }

                saveCredentials(credentials)

                activity.runOnUiThread {
                    callback.onSuccess(account)
                }

            } catch (e: Exception) {
                activity.runOnUiThread {
                    callback.onError(
                        e.message ?: "Не удалось подключить ChatGPT"
                    )
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
                throw Exception(
                    "Ошибка обмена токена: " +
                        JSONObject(text).optString(
                            "error_description",
                            response.code.toString()
                        )
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

        val key = jwks.keys
            .firstOrNull { it.keyID == jwt.header.keyID } as? RSAKey
            ?: throw Exception("Не найден ключ проверки OpenAI")

        val verifier = RSASSAVerifier(key.toRSAPublicKey())

        if (!jwt.verify(verifier)) {
            throw Exception("Недействительная подпись токена")
        }

        val claims = jwt.jwtClaimsSet

        if (claims.issuer != "https://auth.openai.com") {
            throw Exception("Неверный издатель токена")
        }

        if (!claims.audience.contains(clientId)) {
            throw Exception("Неверная аудитория токена")
        }

        if (claims.expirationTime == null ||
            claims.expirationTime.before(Date())
        ) {
            throw Exception("Срок действия токена истёк")
        }

        if (claims.getStringClaim("nonce") != expectedNonce) {
            throw Exception("Проверка nonce не пройдена")
        }

        return claims.subject
            ?: throw Exception("В токене отсутствует идентификатор аккаунта")
    }

    private fun randomValue(bytes: Int): String {
        val random = ByteArray(bytes)
        SecureRandom().nextBytes(random)

        return Base64.encodeToString(
            random,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun saveCredentials(data: JSONObject) {
        val encrypted = encrypt(data.toString())

        preferences.edit()
            .putString("credentials", encrypted)
            .putString(
                "registered_client_id",
                data.getString("client_id")
            )
            .apply()
    }

    private fun readCredentials(): JSONObject? {
        val value = preferences.getString("credentials", null)
            ?: return null

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
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())

        val encrypted = cipher.doFinal(
            value.toByteArray(Charsets.UTF_8)
        )

        val combined = cipher.iv + encrypted

        return Base64.encodeToString(
            combined,
            Base64.NO_WRAP
        )
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)

        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)

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

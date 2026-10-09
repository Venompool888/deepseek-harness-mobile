package app.deepseek.harness.mobile

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cloudflare Access Managed OAuth client using DCR, Authorization Code + PKCE, and refresh tokens. */
internal class CloudflareOAuth(context: Context) {
    data class AuthorizationRequest(val url: String, val redirectScheme: String)

    private val store = EncryptedOAuthStore(context.applicationContext)
    private val random = SecureRandom()

    @Synchronized
    fun prepareAuthorization(serverUrl: String): AuthorizationRequest {
        val resource = normalizeResource(serverUrl)
        val redirectScheme = BuildConfig.APPLICATION_ID
        val redirectUri = "$redirectScheme://oauth/callback"
        val metadata = discover(resource)
        val saved = store.read()
        val savedClient = saved.optJSONObject("client")
        val clientId = if (
            savedClient?.optString("resource") == resource &&
            savedClient.optString("redirect_uri") == redirectUri &&
            savedClient.optString("registration_endpoint") == metadata.registrationEndpoint
        ) {
            savedClient.getString("client_id")
        } else {
            registerClient(metadata.registrationEndpoint, resource, redirectUri)
        }

        var verifier: String
        var challenge: String
        do {
            verifier = randomUrlToken(32)
            challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        } while (!challenge.first().isLetterOrDigit())
        val state = randomUrlToken(24)

        val next = saved.apply {
            put("client", JSONObject().apply {
                put("resource", resource)
                put("redirect_uri", redirectUri)
                put("client_id", clientId)
                put("registration_endpoint", metadata.registrationEndpoint)
                put("token_endpoint", metadata.tokenEndpoint)
            })
            put("pending", JSONObject().apply {
                put("resource", resource)
                put("redirect_uri", redirectUri)
                put("client_id", clientId)
                put("token_endpoint", metadata.tokenEndpoint)
                put("verifier", verifier)
                put("state", state)
                put("created_at", System.currentTimeMillis())
            })
        }
        store.write(next)

        val authUrl = Uri.parse(metadata.authorizationEndpoint).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("resource", resource)
            .appendQueryParameter("state", state)
            .build()
            .toString()
        return AuthorizationRequest(authUrl, redirectScheme)
    }

    @Synchronized
    fun finishAuthorization(callback: Uri): Boolean {
        val root = store.read()
        val pending = root.optJSONObject("pending") ?: throw IOException("No OAuth request is pending")
        val expectedRedirect = Uri.parse(pending.getString("redirect_uri"))
        if (callback.scheme != expectedRedirect.scheme || callback.host != expectedRedirect.host || callback.path != expectedRedirect.path) {
            throw IOException("Unexpected OAuth redirect")
        }
        if (System.currentTimeMillis() - pending.getLong("created_at") > PENDING_LIFETIME_MS) {
            root.remove("pending")
            store.write(root)
            throw IOException("OAuth request expired")
        }
        val returnedState = callback.getQueryParameter("state")
        if (returnedState == null || returnedState != pending.getString("state")) {
            throw IOException("OAuth state check failed")
        }
        callback.getQueryParameter("error")?.let { error ->
            throw IOException(callback.getQueryParameter("error_description") ?: "OAuth error: $error")
        }
        val code = callback.getQueryParameter("code") ?: throw IOException("OAuth authorization code is missing")
        val token = postForm(
            pending.getString("token_endpoint"),
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "client_id" to pending.getString("client_id"),
                "redirect_uri" to pending.getString("redirect_uri"),
                "code_verifier" to pending.getString("verifier"),
            ),
        )
        saveToken(root, pending.getString("resource"), token)
        root.remove("pending")
        store.write(root)
        return true
    }

    /** May perform a synchronous refresh; callers must invoke this off the main thread. */
    @Synchronized
    fun accessToken(serverUrl: String): String? {
        val resource = runCatching { normalizeResource(serverUrl) }.getOrNull() ?: return null
        val root = store.read()
        val token = root.optJSONObject("token") ?: return null
        if (token.optString("resource") != resource) return null
        if (token.optLong("expires_at") > System.currentTimeMillis() + REFRESH_MARGIN_MS) {
            return token.optString("access_token").takeIf { it.isNotBlank() }
        }
        val refreshToken = token.optString("refresh_token").takeIf { it.isNotBlank() } ?: return null
        val client = root.optJSONObject("client") ?: return null
        if (client.optString("resource") != resource) return null
        return try {
            val refreshed = postForm(
                client.getString("token_endpoint"),
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to client.getString("client_id"),
                ),
            )
            saveToken(root, resource, refreshed, refreshToken)
            store.write(root)
            root.getJSONObject("token").getString("access_token")
        } catch (_: Exception) {
            root.remove("token")
            store.write(root)
            null
        }
    }

    @Synchronized
    fun clearPendingAuthorization() {
        store.read().also {
            it.remove("pending")
            store.write(it)
        }
    }

    private fun discover(resource: String): Metadata {
        val metadata = getJson("$resource/.well-known/oauth-authorization-server")
        val authorization = requireHttps(metadata.getString("authorization_endpoint"))
        val token = requireHttps(metadata.getString("token_endpoint"))
        val registration = requireHttps(metadata.getString("registration_endpoint"))
        if (Uri.parse(authorization).host != Uri.parse(token).host || Uri.parse(token).host != Uri.parse(registration).host) {
            throw IOException("OAuth metadata endpoints do not share an authority")
        }
        return Metadata(authorization, token, registration)
    }

    private fun registerClient(endpoint: String, resource: String, redirectUri: String): String {
        val payload = JSONObject().apply {
            put("client_name", "DeepSeek Harness Mobile")
            put("redirect_uris", JSONArray().put(redirectUri))
            put("token_endpoint_auth_method", "none")
            put("grant_types", JSONArray().put("authorization_code"))
            put("response_types", JSONArray().put("code"))
            put("resource", resource)
        }
        return requestJson(endpoint, "POST", payload.toString(), "application/json; charset=utf-8")
            .getString("client_id")
    }

    private fun saveToken(root: JSONObject, resource: String, response: JSONObject, oldRefreshToken: String? = null) {
        val accessToken = response.getString("access_token")
        val expiresIn = response.optLong("expires_in", 900L).coerceAtLeast(30L)
        root.put("token", JSONObject().apply {
            put("resource", resource)
            put("access_token", accessToken)
            put("refresh_token", response.optString("refresh_token").takeIf { it.isNotBlank() } ?: oldRefreshToken.orEmpty())
            put("expires_at", System.currentTimeMillis() + expiresIn * 1_000L)
        })
    }

    private fun getJson(url: String): JSONObject = requestJson(url, "GET", null, null)

    private fun postForm(url: String, values: Map<String, String>): JSONObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        return requestJson(url, "POST", body, "application/x-www-form-urlencoded")
    }

    private fun requestJson(url: String, method: String, body: String?, contentType: String?): JSONObject {
        val connection = (URL(requireHttps(url)).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DeepSeekHarnessMobile/${BuildConfig.VERSION_NAME}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("OAuth HTTP $status")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun randomUrlToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::base64Url)

    private data class Metadata(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String,
    )

    companion object {
        private const val REFRESH_MARGIN_MS = 60_000L
        private const val PENDING_LIFETIME_MS = 10 * 60_000L

        internal fun normalizeResource(value: String): String {
            val uri = Uri.parse(value.trim())
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) throw IOException("Managed OAuth requires an HTTPS server")
            return Uri.Builder().scheme("https").encodedAuthority(uri.encodedAuthority).build().toString().trimEnd('/')
        }

        private fun requireHttps(value: String): String {
            val uri = Uri.parse(value)
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) throw IOException("OAuth endpoint must use HTTPS")
            return value
        }

        private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
        private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}

private class EncryptedOAuthStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(): JSONObject {
        val iv = prefs.getString(KEY_IV, null) ?: return JSONObject()
        val ciphertext = prefs.getString(KEY_DATA, null) ?: return JSONObject()
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
            JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), Charsets.UTF_8))
        }.getOrElse {
            prefs.edit().clear().apply()
            JSONObject()
        }
    }

    fun write(value: JSONObject) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_IV, Base64.getEncoder().encodeToString(cipher.iv))
            .putString(KEY_DATA, Base64.getEncoder().encodeToString(ciphertext))
            .apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFS = "cloudflare_oauth_secure"
        private const val KEY_IV = "iv"
        private const val KEY_DATA = "data"
        private const val KEY_ALIAS = "deepseek_harness_cloudflare_oauth"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

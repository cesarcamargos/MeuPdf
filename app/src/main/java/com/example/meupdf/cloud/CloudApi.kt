package com.example.meupdf.cloud

import com.example.meupdf.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Raised when the session is missing/expired and the user must log in again. */
class CloudAuthException(message: String) : Exception(message)

/**
 * Talks to the Azure Function: email/password login and short-lived SAS issuance.
 * Caches the SAS in [CloudPrefs] and refreshes it a few minutes before expiry.
 */
class CloudApi(private val prefs: CloudPrefs) {

    private val baseUrl = BuildConfig.FUNCTION_BASE_URL.trimEnd('/')
    private val json = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun login(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("email", email).put("password", password).toString().toRequestBody(json)
            val req = Request.Builder().url("$baseUrl/api/login").post(body).build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 -> throw CloudAuthException("E-mail ou senha inválidos")
                    !resp.isSuccessful -> throw CloudAuthException("Erro do servidor (${resp.code})")
                    else -> {
                        val obj = JSONObject(resp.body?.string().orEmpty())
                        prefs.saveSession(email.trim(), obj.getString("token"), obj.getString("userId"))
                    }
                }
            }
        }.recoverCatching {
            // Distinguish connectivity problems from credential problems.
            if (it is CloudAuthException) throw it
            throw CloudAuthException("Falha de conexão ($baseUrl): ${it.message ?: it.javaClass.simpleName}")
        }
    }

    suspend fun logout() {
        prefs.clear()
    }

    /**
     * Returns a valid SAS URL, refreshing it if missing/near expiry.
     * Throws [CloudAuthException] if there's no session or the token was rejected.
     */
    suspend fun validSasUrl(): String = withContext(Dispatchers.IO) {
        prefs.cachedSas()?.let { (url, expiry) ->
            if (expiry - System.currentTimeMillis() > 5 * 60_000L) return@withContext url
        }
        val session = prefs.session() ?: throw CloudAuthException("Not logged in")
        val req = Request.Builder()
            .url("$baseUrl/api/sas")
            .addHeader("Authorization", "Bearer ${session.jwt}")
            .post(ByteArray(0).toRequestBody(json))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 401) {
                prefs.clear()
                throw CloudAuthException("Session expired")
            }
            if (!resp.isSuccessful) throw CloudAuthException("Could not get SAS (${resp.code})")
            val obj = JSONObject(resp.body?.string().orEmpty())
            val sasUrl = obj.getString("sasUrl")
            val expiresAt = runCatching { Instant.parse(obj.getString("expiresAt")).toEpochMilli() }
                .getOrDefault(System.currentTimeMillis() + 55 * 60_000L)
            prefs.saveSas(sasUrl, expiresAt)
            sasUrl
        }
    }

    suspend fun userId(): String? = prefs.session()?.userId
}

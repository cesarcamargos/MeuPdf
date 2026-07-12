package com.example.meupdf.cloud

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cloudDataStore by preferencesDataStore(name = "cloud")
private val EMAIL_KEY = stringPreferencesKey("email")
private val JWT_KEY = stringPreferencesKey("jwt")
private val USER_ID_KEY = stringPreferencesKey("user_id")
private val SAS_URL_KEY = stringPreferencesKey("sas_url")
private val SAS_EXPIRY_KEY = longPreferencesKey("sas_expiry")

data class Session(val email: String, val jwt: String, val userId: String)

/** Persists the cloud login session and the cached (short-lived) SAS URL. */
class CloudPrefs(private val context: Context) {

    val loggedIn: Flow<Boolean> = context.cloudDataStore.data.map { it[JWT_KEY] != null }

    suspend fun session(): Session? {
        val prefs = context.cloudDataStore.data.first()
        val jwt = prefs[JWT_KEY] ?: return null
        val email = prefs[EMAIL_KEY] ?: return null
        val userId = prefs[USER_ID_KEY] ?: return null
        return Session(email, jwt, userId)
    }

    suspend fun saveSession(email: String, jwt: String, userId: String) {
        context.cloudDataStore.edit {
            it[EMAIL_KEY] = email
            it[JWT_KEY] = jwt
            it[USER_ID_KEY] = userId
        }
    }

    suspend fun clear() {
        context.cloudDataStore.edit { it.clear() }
    }

    suspend fun cachedSas(): Pair<String, Long>? {
        val prefs = context.cloudDataStore.data.first()
        val url = prefs[SAS_URL_KEY] ?: return null
        val expiry = prefs[SAS_EXPIRY_KEY] ?: return null
        return url to expiry
    }

    suspend fun saveSas(url: String, expiryEpochMillis: Long) {
        context.cloudDataStore.edit {
            it[SAS_URL_KEY] = url
            it[SAS_EXPIRY_KEY] = expiryEpochMillis
        }
    }
}

package com.example.meupdf.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.syncDataStore by preferencesDataStore(name = "sync")
private val DEVICE_ID_KEY = stringPreferencesKey("device_id")

/** Per-device identity: a short stable id used to name this device's progress sidecar. */
class SyncPrefs(private val context: Context) {

    /** Returns this device's id, generating and persisting it on first use. */
    suspend fun deviceId(): String {
        context.syncDataStore.data.first()[DEVICE_ID_KEY]?.let { return it }
        val generated = UUID.randomUUID().toString().take(8)
        context.syncDataStore.edit { it[DEVICE_ID_KEY] = generated }
        return generated
    }
}

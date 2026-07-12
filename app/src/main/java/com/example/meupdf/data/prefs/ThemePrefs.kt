package com.example.meupdf.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.meupdf.ui.reader.PageColorMode
import com.example.meupdf.ui.theme.ThemeMode
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")
private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
private val LAST_OPEN_BOOK_KEY = longPreferencesKey("last_open_book_id")
private val PAGE_COLOR_MODE_KEY = stringPreferencesKey("page_color_mode")

class ThemePrefs(private val context: Context) {
    val themeMode = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.PASTEL
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[THEME_MODE_KEY] = mode.name }
    }

    val lastOpenBookId = context.dataStore.data.map { prefs -> prefs[LAST_OPEN_BOOK_KEY] }

    suspend fun setLastOpenBookId(bookId: Long?) {
        context.dataStore.edit { prefs ->
            if (bookId == null) prefs.remove(LAST_OPEN_BOOK_KEY) else prefs[LAST_OPEN_BOOK_KEY] = bookId
        }
    }

    val pageColorMode = context.dataStore.data.map { prefs ->
        prefs[PAGE_COLOR_MODE_KEY]?.let { runCatching { PageColorMode.valueOf(it) }.getOrNull() } ?: PageColorMode.NORMAL
    }

    suspend fun setPageColorMode(mode: PageColorMode) {
        context.dataStore.edit { it[PAGE_COLOR_MODE_KEY] = mode.name }
    }
}

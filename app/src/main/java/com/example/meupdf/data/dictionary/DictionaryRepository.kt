package com.example.meupdf.data.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class WordDefinition(val word: String, val partOfSpeech: String, val definition: String)

private fun posLabel(posId: String) = when (posId) {
    "n" -> "noun"
    "v" -> "verb"
    "a", "s" -> "adjective"
    "r" -> "adverb"
    else -> posId
}

class DictionaryRepository(private val context: Context) {
    @Volatile private var db: SQLiteDatabase? = null

    private suspend fun database(): SQLiteDatabase = withContext(Dispatchers.IO) {
        db ?: synchronized(this) {
            db ?: openOrCopyDatabase().also { db = it }
        }
    }

    private fun openOrCopyDatabase(): SQLiteDatabase {
        val dbFile = File(context.filesDir, "dictionary.sqlite")
        if (!dbFile.exists()) {
            context.assets.open("dictionary.sqlite").use { input ->
                dbFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    suspend fun lookup(word: String): WordDefinition? = withContext(Dispatchers.IO) {
        val cleaned = word.trim().lowercase()
        if (cleaned.isEmpty()) return@withContext null
        database().rawQuery(
            "SELECT word, pos, definition FROM entries WHERE word = ? COLLATE NOCASE ORDER BY sensenum ASC LIMIT 1",
            arrayOf(cleaned),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            WordDefinition(
                word = cursor.getString(0),
                partOfSpeech = posLabel(cursor.getString(1)),
                definition = cursor.getString(2),
            )
        }
    }
}

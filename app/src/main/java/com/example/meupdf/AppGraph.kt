package com.example.meupdf

import android.content.Context
import com.example.meupdf.cloud.CloudApi
import com.example.meupdf.cloud.CloudPrefs
import com.example.meupdf.cloud.CloudStore
import com.example.meupdf.data.LibraryRepository
import com.example.meupdf.data.dictionary.DictionaryRepository
import com.example.meupdf.data.prefs.ThemePrefs
import com.example.meupdf.pdf.PdfEngine
import com.example.meupdf.sync.ProgressStore
import com.example.meupdf.sync.SyncManager
import com.example.meupdf.sync.SyncPrefs

/** Minimal manual dependency container: one instance per process, created from [MeuPdfApplication]. */
class AppGraph(context: Context) {
    val cloudPrefs = CloudPrefs(context)
    val cloudApi = CloudApi(cloudPrefs)
    val cloudStore = CloudStore(context, cloudApi)
    val syncPrefs = SyncPrefs(context)
    val progressStore = ProgressStore(cloudStore, syncPrefs)
    val libraryRepository = LibraryRepository(context, cloudStore, progressStore)
    val syncManager = SyncManager(libraryRepository, cloudStore, progressStore)
    val dictionaryRepository = DictionaryRepository(context)
    val themePrefs = ThemePrefs(context)
    val pdfEngine = PdfEngine(context)
}

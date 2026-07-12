package com.example.meupdf

import android.content.Context
import com.example.meupdf.data.LibraryRepository
import com.example.meupdf.data.dictionary.DictionaryRepository
import com.example.meupdf.data.prefs.ThemePrefs
import com.example.meupdf.pdf.PdfEngine

/** Minimal manual dependency container: one instance per process, created from [MeuPdfApplication]. */
class AppGraph(context: Context) {
    val libraryRepository = LibraryRepository(context)
    val dictionaryRepository = DictionaryRepository(context)
    val themePrefs = ThemePrefs(context)
    val pdfEngine = PdfEngine(context)
}

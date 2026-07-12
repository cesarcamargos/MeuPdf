package com.example.meupdf.ui.reader

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meupdf.data.LibraryRepository
import com.example.meupdf.data.db.BookEntity
import com.example.meupdf.data.db.BookmarkEntity
import com.example.meupdf.data.dictionary.DictionaryRepository
import com.example.meupdf.data.dictionary.WordDefinition
import com.example.meupdf.data.prefs.ThemePrefs
import com.example.meupdf.pdf.PdfEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DefinitionUiState(val word: String, val definition: WordDefinition?, val loading: Boolean)

enum class PageColorMode { NORMAL, DARK, SEPIA }

class ReaderViewModel(
    private val bookId: Long,
    private val repo: LibraryRepository,
    private val pdfEngine: PdfEngine,
    private val dictionaryRepository: DictionaryRepository,
    private val themePrefs: ThemePrefs,
) : ViewModel() {

    private val pdfDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _book = MutableStateFlow<BookEntity?>(null)
    val book: StateFlow<BookEntity?> = _book.asStateFlow()

    val bookmarks = repo.observeBookmarks(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var currentPage by mutableStateOf(0)
        private set
    var pageCount by mutableStateOf(0)
        private set
    var fontScale by mutableStateOf(1.0f)
        private set
    var controlsVisible by mutableStateOf(false)
    var isLoading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf(false)
        private set
    var definitionState by mutableStateOf<DefinitionUiState?>(null)
        private set
    var pageColorMode by mutableStateOf(PageColorMode.NORMAL)
        private set

    private var document: PdfEngine.OpenDocument? = null
    private var initialized = false

    init {
        viewModelScope.launch {
            repo.observeBook(bookId).collect { b ->
                _book.value = b
                if (b != null && !initialized) {
                    initialized = true
                    openDocument(b)
                }
            }
        }
        viewModelScope.launch {
            pageColorMode = themePrefs.pageColorMode.first()
        }
    }

    private suspend fun openDocument(b: BookEntity) {
        withContext(pdfDispatcher) {
            runCatching { pdfEngine.open(b.uri.toUri()) }
                .onSuccess { opened ->
                    document = opened
                    pageCount = opened.pageCount
                    currentPage = b.lastPage.coerceIn(0, (opened.pageCount - 1).coerceAtLeast(0))
                    fontScale = b.fontScale
                }
                .onFailure { loadError = true }
            isLoading = false
        }
    }

    suspend fun renderPageBitmap(page: Int, widthPx: Int, heightPx: Int): Bitmap? =
        withContext(pdfDispatcher) {
            runCatching { document?.renderPage(page, widthPx, heightPx) }.getOrNull()
        }

    fun pageAspectRatio(): Float = document?.pageAspectRatio(currentPage) ?: 0.7f

    fun goToPage(page: Int) {
        val p = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (p == currentPage) return
        currentPage = p
        viewModelScope.launch { repo.updateLastPage(bookId, p) }
    }

    fun nextPage() = goToPage(currentPage + 1)
    fun prevPage() = goToPage(currentPage - 1)

    fun increaseFont() {
        fontScale = (fontScale + 0.1f).coerceAtMost(2.5f)
        persistFont()
    }

    fun decreaseFont() {
        fontScale = (fontScale - 0.1f).coerceAtLeast(0.6f)
        persistFont()
    }

    fun scaleFont(factor: Float) {
        fontScale = (fontScale * factor).coerceIn(0.6f, 2.5f)
        persistFont()
    }

    private fun persistFont() {
        viewModelScope.launch { repo.updateFontScale(bookId, fontScale) }
    }

    fun toggleControls() {
        controlsVisible = !controlsVisible
    }

    fun toggleBookmarkForCurrentPage() {
        viewModelScope.launch { repo.toggleBookmark(bookId, currentPage) }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch { repo.deleteBookmark(bookmark) }
    }

    fun jumpToBookmark(bookmark: BookmarkEntity) = goToPage(bookmark.page)

    fun onWordLongPress(xPx: Float, yPx: Float, widthPx: Int, heightPx: Int) {
        val doc = document ?: return
        viewModelScope.launch {
            val hit = withContext(pdfDispatcher) {
                runCatching { doc.wordAt(currentPage, xPx, yPx, widthPx, heightPx) }.getOrNull()
            } ?: return@launch
            definitionState = DefinitionUiState(hit.word, null, loading = true)
            val definition = dictionaryRepository.lookup(hit.word)
            definitionState = DefinitionUiState(hit.word, definition, loading = false)
        }
    }

    fun dismissDefinition() {
        definitionState = null
    }

    fun changePageColorMode(mode: PageColorMode) {
        pageColorMode = mode
        viewModelScope.launch { themePrefs.setPageColorMode(mode) }
    }

    override fun onCleared() {
        document?.close()
    }

    class Factory(
        private val bookId: Long,
        private val repo: LibraryRepository,
        private val pdfEngine: PdfEngine,
        private val dictionaryRepository: DictionaryRepository,
        private val themePrefs: ThemePrefs,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReaderViewModel(bookId, repo, pdfEngine, dictionaryRepository, themePrefs) as T
    }
}

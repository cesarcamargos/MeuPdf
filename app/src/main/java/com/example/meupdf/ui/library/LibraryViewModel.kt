package com.example.meupdf.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meupdf.cloud.CloudApi
import com.example.meupdf.data.LibraryRepository
import com.example.meupdf.data.db.BookEntity
import com.example.meupdf.data.db.SectionEntity
import com.example.meupdf.sync.SyncManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repo: LibraryRepository,
    private val syncManager: SyncManager,
    private val cloudApi: CloudApi,
) : ViewModel() {
    val sections: StateFlow<List<SectionEntity>> = repo.observeSections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val books: StateFlow<List<BookEntity>> = repo.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun sync() {
        viewModelScope.launch { syncManager.sync() }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            cloudApi.logout()
            onDone()
        }
    }

    fun createSection(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repo.createSection(name.trim()) }
    }

    fun deleteSection(sectionId: Long) {
        viewModelScope.launch { repo.deleteSection(sectionId) }
    }

    fun renameSection(sectionId: Long, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { repo.renameSection(sectionId, newName.trim()) }
    }

    fun moveBookToSection(bookId: Long, sectionId: Long?) {
        viewModelScope.launch { repo.moveBookToSection(bookId, sectionId) }
    }

    fun deleteBook(book: BookEntity) {
        viewModelScope.launch { repo.deleteBook(book) }
    }

    fun importPdf(uri: Uri, sectionId: Long?) {
        viewModelScope.launch { repo.importPdf(uri, sectionId) }
    }

    class Factory(
        private val repo: LibraryRepository,
        private val syncManager: SyncManager,
        private val cloudApi: CloudApi,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(repo, syncManager, cloudApi) as T
    }
}

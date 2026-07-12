package com.example.meupdf.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.example.meupdf.data.db.AppDatabase
import com.example.meupdf.data.db.BookEntity
import com.example.meupdf.data.db.BookmarkEntity
import com.example.meupdf.data.db.SectionEntity
import com.example.meupdf.pdf.PdfEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class LibraryRepository(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val bookDao = db.bookDao()
    private val sectionDao = db.sectionDao()
    private val bookmarkDao = db.bookmarkDao()

    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeAll()
    fun observeSections(): Flow<List<SectionEntity>> = sectionDao.observeAll()
    fun observeBook(id: Long): Flow<BookEntity?> = bookDao.observeById(id)
    suspend fun getBook(id: Long): BookEntity? = withContext(Dispatchers.IO) { bookDao.getById(id) }
    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> = bookmarkDao.observeForBook(bookId)

    suspend fun createSection(name: String): Long = withContext(Dispatchers.IO) {
        sectionDao.insert(SectionEntity(name = name))
    }

    suspend fun deleteSection(section: SectionEntity) = withContext(Dispatchers.IO) {
        sectionDao.delete(section)
    }

    suspend fun moveBookToSection(bookId: Long, sectionId: Long?) = withContext(Dispatchers.IO) {
        bookDao.updateSection(bookId, sectionId)
    }

    suspend fun deleteBook(book: BookEntity) = withContext(Dispatchers.IO) {
        book.coverPath?.let { File(it).delete() }
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                book.uri.toUri(),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        bookDao.delete(book)
    }

    suspend fun importPdf(uri: Uri, sectionId: Long?): Long = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val title = queryDisplayName(uri) ?: "Untitled"
        val engine = PdfEngine(context)
        val doc = engine.open(uri)
        val pageCount = doc.pageCount
        val coverPath = generateCover(doc)
        doc.close()
        bookDao.insert(
            BookEntity(
                title = title.removeSuffix(".pdf"),
                uri = uri.toString(),
                coverPath = coverPath,
                sectionId = sectionId,
                pageCount = pageCount,
            ),
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && nameIndex >= 0) return it.getString(nameIndex)
        }
        return null
    }

    private fun generateCover(doc: PdfEngine.OpenDocument): String? {
        val bitmap = doc.renderCoverThumbnail() ?: return null
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(coversDir, "cover_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        }
        bitmap.recycle()
        return file.absolutePath
    }

    suspend fun updateLastPage(bookId: Long, page: Int) = withContext(Dispatchers.IO) {
        bookDao.updateLastPage(bookId, page)
    }

    suspend fun updateFontScale(bookId: Long, fontScale: Float) = withContext(Dispatchers.IO) {
        bookDao.updateFontScale(bookId, fontScale)
    }

    suspend fun toggleBookmark(bookId: Long, page: Int) = withContext(Dispatchers.IO) {
        val existing = bookmarkDao.findByPage(bookId, page)
        if (existing != null) {
            bookmarkDao.delete(existing)
        } else {
            bookmarkDao.insert(BookmarkEntity(bookId = bookId, page = page, label = "Page ${page + 1}"))
        }
    }

    suspend fun deleteBookmark(bookmark: BookmarkEntity) = withContext(Dispatchers.IO) {
        bookmarkDao.delete(bookmark)
    }
}

package com.example.meupdf.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.example.meupdf.cloud.CloudStore
import com.example.meupdf.data.db.AppDatabase
import com.example.meupdf.data.db.BookEntity
import com.example.meupdf.data.db.BookmarkEntity
import com.example.meupdf.data.db.SectionEntity
import com.example.meupdf.pdf.PdfEngine
import com.example.meupdf.sync.MergedState
import com.example.meupdf.sync.ProgressStore
import com.example.meupdf.sync.SyncBook
import com.example.meupdf.sync.SyncSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class LibraryRepository(
    private val context: Context,
    private val cloudStore: CloudStore,
    private val progressStore: ProgressStore,
) {
    private val db = AppDatabase.getInstance(context)
    private val bookDao = db.bookDao()
    private val sectionDao = db.sectionDao()
    private val bookmarkDao = db.bookmarkDao()

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null

    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeAll()
    fun observeSections(): Flow<List<SectionEntity>> = sectionDao.observeAll()
    fun observeBook(id: Long): Flow<BookEntity?> = bookDao.observeById(id)
    suspend fun getBook(id: Long): BookEntity? = withContext(Dispatchers.IO) { bookDao.getById(id) }
    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> = bookmarkDao.observeForBook(bookId)

    // ---- Sections ----

    suspend fun createSection(name: String): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = sectionDao.insert(
            SectionEntity(name = name, syncId = UUID.randomUUID().toString(), updatedAt = now),
        )
        schedulePush()
        id
    }

    suspend fun deleteSection(sectionId: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Detach its books (they become uncategorized) so nothing references a tombstoned section.
        bookDao.getAll().filter { it.sectionId == sectionId }.forEach {
            bookDao.updateSectionSynced(it.id, null, now)
        }
        sectionDao.softDelete(sectionId, now)
        schedulePush()
    }

    suspend fun renameSection(sectionId: Long, newName: String) = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return@withContext
        val section = sectionDao.getById(sectionId) ?: return@withContext
        sectionDao.update(section.copy(name = trimmed, updatedAt = System.currentTimeMillis()))
        schedulePush()
    }

    suspend fun moveBookToSection(bookId: Long, sectionId: Long?) = withContext(Dispatchers.IO) {
        bookDao.updateSectionSynced(bookId, sectionId, System.currentTimeMillis())
        schedulePush()
    }

    // ---- Books ----

    suspend fun deleteBook(book: BookEntity) = withContext(Dispatchers.IO) {
        book.coverPath?.let { File(it).delete() }
        bookDao.softDelete(book.id, System.currentTimeMillis())
        schedulePush()
    }

    /** Live URI to read this book on this device: cached copy, else downloaded from Blob. */
    suspend fun resolveOpenUri(book: BookEntity): Uri? = withContext(Dispatchers.IO) {
        val hash = book.contentHash
        if (hash != null && cloudStore.isReady()) {
            val file = cloudStore.downloadToCache("books/$hash.pdf", "book_$hash.pdf")
            if (file != null) return@withContext file.toUri()
        }
        book.uri.takeIf { it.isNotBlank() }?.toUri()
    }

    suspend fun importPdf(sourceUri: Uri, sectionId: Long?): Long = withContext(Dispatchers.IO) {
        val copied = copyToCacheAndHash(sourceUri) ?: return@withContext -1L
        val (localFile, hash) = copied

        // Dedupe: same content already in the library. Repair/revive it — a book from the old
        // local-only version (or a re-added one after delete) needs its cover regenerated and
        // its PDF + cover uploaded so it actually syncs.
        bookDao.getByHash(hash)?.let { existing ->
            val coverMissing = existing.coverPath == null || !File(existing.coverPath).exists()
            val needsRepair = existing.deleted || existing.pendingUpload || existing.uri.isNotBlank() || coverMissing
            if (!needsRepair) return@withContext existing.id

            val engine = PdfEngine(context)
            val doc = runCatching { engine.open(localFile.toUri()) }.getOrNull()
            val pageCount = doc?.pageCount ?: existing.pageCount
            val coverPath = if (coverMissing) doc?.let { generateCover(it) } ?: existing.coverPath else existing.coverPath
            doc?.close()
            bookDao.update(
                existing.copy(
                    deleted = false,
                    uri = "",
                    coverPath = coverPath,
                    pageCount = pageCount,
                    pendingUpload = true,
                    progressUpdatedAt = System.currentTimeMillis(),
                ),
            )
            uploadBookFiles(existing.id)
            pushLocalStateNow()
            return@withContext existing.id
        }

        val title = (queryDisplayName(sourceUri) ?: "Untitled").removeSuffix(".pdf")
        val engine = PdfEngine(context)
        val doc = engine.open(localFile.toUri())
        val pageCount = doc.pageCount
        val coverPath = generateCover(doc)
        doc.close()

        val now = System.currentTimeMillis()
        val bookId = bookDao.insert(
            BookEntity(
                title = title,
                uri = "",
                coverPath = coverPath,
                sectionId = sectionId,
                pageCount = pageCount,
                contentHash = hash,
                progressUpdatedAt = now,
                pendingUpload = true,
            ),
        )
        uploadBookFiles(bookId)
        pushLocalStateNow()
        bookId
    }

    /** Uploads a book's PDF (from cache) and cover to Blob; clears pendingUpload on success. */
    suspend fun uploadBookFiles(bookId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!cloudStore.isReady()) return@withContext false
        val book = bookDao.getById(bookId) ?: return@withContext false
        val hash = book.contentHash ?: return@withContext false
        val pdf = File(File(context.cacheDir, "cloud"), "book_$hash.pdf")
        if (!pdf.exists()) return@withContext false
        val pdfOk = cloudStore.putFile("books/$hash.pdf", pdf, "application/pdf")
        val coverOk = book.coverPath?.let { path ->
            val cover = File(path)
            if (cover.exists()) cloudStore.putFile("covers/$hash.jpg", cover, "image/jpeg") else true
        } ?: true
        if (pdfOk && coverOk) {
            bookDao.setPendingUpload(bookId, false)
            true
        } else {
            false
        }
    }

    /** Retries any book whose upload previously failed (called by the background worker). */
    suspend fun uploadPending() = withContext(Dispatchers.IO) {
        bookDao.getPendingUpload().forEach { uploadBookFiles(it.id) }
    }

    /**
     * Migrates books from the old local-only version (they still carry a legacy `uri` and were
     * never uploaded) into the cloud: reads the bytes from the legacy URI, (re)generates the cover,
     * uploads both, then clears `uri` so the migration runs only once per book. Books whose source
     * URI is no longer readable are left as-is (the user can re-import them).
     */
    suspend fun repairLegacyBooks() = withContext(Dispatchers.IO) {
        if (!cloudStore.isReady()) return@withContext
        var changed = false
        for (book in bookDao.getAll()) {
            if (book.deleted || book.uri.isBlank()) continue
            val copied = runCatching { copyToCacheAndHash(book.uri.toUri()) }.getOrNull() ?: continue
            val (localFile, hash) = copied
            val engine = PdfEngine(context)
            val doc = runCatching { engine.open(localFile.toUri()) }.getOrNull()
            val pageCount = doc?.pageCount ?: book.pageCount
            val coverMissing = book.coverPath == null || !File(book.coverPath).exists()
            val coverPath = if (coverMissing) doc?.let { generateCover(it) } ?: book.coverPath else book.coverPath
            doc?.close()
            // Keep the legacy uri until the upload succeeds, so we can retry from it next time.
            bookDao.update(
                book.copy(contentHash = hash, coverPath = coverPath, pageCount = pageCount, pendingUpload = true),
            )
            if (uploadBookFiles(book.id)) {
                bookDao.getById(book.id)?.let { bookDao.update(it.copy(uri = "")) }
            }
            changed = true
        }
        if (changed) pushLocalStateNow()
    }

    // ---- Reading progress (writes Room + schedules a sidecar push) ----

    suspend fun updateLastPage(bookId: Long, page: Int) = withContext(Dispatchers.IO) {
        bookDao.updateLastPageSynced(bookId, page, System.currentTimeMillis())
        schedulePush()
    }

    suspend fun updateFontScale(bookId: Long, fontScale: Float) = withContext(Dispatchers.IO) {
        bookDao.updateFontScaleSynced(bookId, fontScale, System.currentTimeMillis())
        schedulePush()
    }

    suspend fun toggleBookmark(bookId: Long, page: Int) = withContext(Dispatchers.IO) {
        val existing = bookmarkDao.findByPage(bookId, page)
        if (existing != null) {
            bookmarkDao.delete(existing)
        } else {
            bookmarkDao.insert(BookmarkEntity(bookId = bookId, page = page, label = "Page ${page + 1}"))
        }
        bookDao.updateProgressTimestamp(bookId, System.currentTimeMillis())
        schedulePush()
    }

    suspend fun deleteBookmark(bookmark: BookmarkEntity) = withContext(Dispatchers.IO) {
        bookmarkDao.delete(bookmark)
        bookDao.updateProgressTimestamp(bookmark.bookId, System.currentTimeMillis())
        schedulePush()
    }

    // ---- Sync engine hooks ----

    /** Reconciles the local Room cache with the merged cloud state (last-write-wins). */
    suspend fun applyMergedState(merged: MergedState) = withContext(Dispatchers.IO) {
        // Sections first, so books can resolve their section.
        for (s in merged.sections.values) {
            val local = sectionDao.getBySyncId(s.syncId)
            if (local == null) {
                if (!s.deleted) {
                    sectionDao.insert(
                        SectionEntity(
                            name = s.name, orderIndex = s.orderIndex, syncId = s.syncId,
                            updatedAt = s.updatedAt, deleted = false,
                        ),
                    )
                }
            } else if (s.updatedAt > local.updatedAt) {
                sectionDao.update(
                    local.copy(name = s.name, orderIndex = s.orderIndex, updatedAt = s.updatedAt, deleted = s.deleted),
                )
            }
        }

        val sectionIdBySyncId = sectionDao.getAll()
            .mapNotNull { sec -> sec.syncId?.let { it to sec.id } }
            .toMap()

        for (b in merged.books.values) {
            val local = bookDao.getByHash(b.hash)
            val localSectionId = b.sectionSyncId?.let { sectionIdBySyncId[it] }
            if (local == null) {
                if (b.deleted) continue
                val cover = cloudStore.downloadToCache("covers/${b.hash}.jpg", "cover_${b.hash}.jpg")
                val newId = bookDao.insert(
                    BookEntity(
                        title = b.title, uri = "", coverPath = cover?.absolutePath,
                        sectionId = localSectionId, pageCount = b.pageCount, contentHash = b.hash,
                        lastPage = b.lastPage, fontScale = b.fontScale, progressUpdatedAt = b.updatedAt,
                        deleted = false,
                    ),
                )
                b.bookmarks.forEach { (page, label) ->
                    bookmarkDao.insert(BookmarkEntity(bookId = newId, page = page, label = label))
                }
            } else if (b.updatedAt > local.progressUpdatedAt) {
                // Fetch the cover if this device doesn't have it yet (e.g. it was uploaded later).
                val coverMissing = local.coverPath == null || !File(local.coverPath).exists()
                val coverPath = if (coverMissing) {
                    cloudStore.downloadToCache("covers/${b.hash}.jpg", "cover_${b.hash}.jpg")?.absolutePath ?: local.coverPath
                } else {
                    local.coverPath
                }
                bookDao.update(
                    local.copy(
                        title = b.title, sectionId = localSectionId, pageCount = b.pageCount,
                        lastPage = b.lastPage, fontScale = b.fontScale, progressUpdatedAt = b.updatedAt,
                        deleted = b.deleted, coverPath = coverPath,
                    ),
                )
                bookmarkDao.deleteAllForBook(local.id)
                b.bookmarks.forEach { (page, label) ->
                    bookmarkDao.insert(BookmarkEntity(bookId = local.id, page = page, label = label))
                }
            }
        }
    }

    private fun schedulePush() {
        pushJob?.cancel()
        pushJob = repoScope.launch {
            delay(1500)
            runCatching { pushLocalState() }
        }
    }

    /** Forces an immediate sidecar write (used by the sync engine and on import). */
    suspend fun pushLocalStateNow() {
        pushJob?.cancel()
        runCatching { pushLocalState() }
    }

    private suspend fun pushLocalState() {
        if (!cloudStore.isReady()) return
        ensureSectionSyncIds()
        val allSections = sectionDao.getAll()
        val syncSections = allSections.mapNotNull { s ->
            s.syncId?.let { SyncSection(it, s.name, s.orderIndex, s.updatedAt, s.deleted) }
        }
        val syncIdBySectionId = allSections.mapNotNull { s -> s.syncId?.let { s.id to it } }.toMap()
        val syncBooks = bookDao.getAll()
            .filter { it.contentHash != null }
            .map { b ->
                SyncBook(
                    hash = b.contentHash!!,
                    title = b.title,
                    sectionSyncId = b.sectionId?.let { syncIdBySectionId[it] },
                    pageCount = b.pageCount,
                    lastPage = b.lastPage,
                    fontScale = b.fontScale,
                    bookmarks = bookmarkDao.getForBook(b.id).map { it.page to it.label },
                    updatedAt = b.progressUpdatedAt,
                    deleted = b.deleted,
                )
            }
        progressStore.writeLocalState(syncSections, syncBooks)
    }

    private suspend fun ensureSectionSyncIds() {
        sectionDao.getAll().filter { it.syncId == null }.forEach {
            sectionDao.update(
                it.copy(syncId = UUID.randomUUID().toString(), updatedAt = maxOf(it.updatedAt, System.currentTimeMillis())),
            )
        }
    }

    // ---- Helpers ----

    private fun copyToCacheAndHash(sourceUri: Uri): Pair<File, String>? {
        val dir = File(context.cacheDir, "cloud").apply { mkdirs() }
        val tmp = File.createTempFile("import", null, dir)
        val md = MessageDigest.getInstance("SHA-256")
        val ok = runCatching {
            context.contentResolver.openInputStream(sourceUri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                    }
                }
            }
        }.isSuccess
        if (!ok) {
            tmp.delete()
            return null
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        val dest = File(dir, "book_$hash.pdf")
        if (dest.exists()) tmp.delete() else tmp.renameTo(dest)
        return dest to hash
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
}

package com.example.meupdf.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SectionDao {
    @Query("SELECT * FROM sections WHERE deleted = 0 ORDER BY orderIndex ASC, name ASC")
    fun observeAll(): Flow<List<SectionEntity>>

    @Query("SELECT * FROM sections")
    suspend fun getAll(): List<SectionEntity>

    @Query("SELECT * FROM sections WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): SectionEntity?

    @Query("SELECT * FROM sections WHERE id = :id")
    suspend fun getById(id: Long): SectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(section: SectionEntity): Long

    @Update
    suspend fun update(section: SectionEntity)

    @Query("UPDATE sections SET deleted = 1, updatedAt = :ts WHERE id = :id")
    suspend fun softDelete(id: Long, ts: Long)
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE deleted = 0 ORDER BY dateAdded DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE contentHash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): BookEntity?

    @Query("SELECT * FROM books")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM books WHERE pendingUpload = 1")
    suspend fun getPendingUpload(): List<BookEntity>

    @Query("UPDATE books SET pendingUpload = :pending WHERE id = :bookId")
    suspend fun setPendingUpload(bookId: Long, pending: Boolean)

    @Query("UPDATE books SET deleted = 1, progressUpdatedAt = :ts WHERE id = :bookId")
    suspend fun softDelete(bookId: Long, ts: Long)

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: Long): Flow<BookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("UPDATE books SET lastPage = :page WHERE id = :bookId")
    suspend fun updateLastPage(bookId: Long, page: Int)

    @Query("UPDATE books SET lastPage = :page, progressUpdatedAt = :ts WHERE id = :bookId")
    suspend fun updateLastPageSynced(bookId: Long, page: Int, ts: Long)

    @Query("UPDATE books SET fontScale = :fontScale WHERE id = :bookId")
    suspend fun updateFontScale(bookId: Long, fontScale: Float)

    @Query("UPDATE books SET fontScale = :fontScale, progressUpdatedAt = :ts WHERE id = :bookId")
    suspend fun updateFontScaleSynced(bookId: Long, fontScale: Float, ts: Long)

    @Query("UPDATE books SET lastPage = :lastPage, fontScale = :fontScale, progressUpdatedAt = :ts WHERE id = :bookId")
    suspend fun applySyncedProgress(bookId: Long, lastPage: Int, fontScale: Float, ts: Long)

    @Query("UPDATE books SET progressUpdatedAt = :ts WHERE id = :bookId")
    suspend fun updateProgressTimestamp(bookId: Long, ts: Long)

    @Query("UPDATE books SET sectionId = :sectionId WHERE id = :bookId")
    suspend fun updateSection(bookId: Long, sectionId: Long?)

    @Query("UPDATE books SET sectionId = :sectionId, progressUpdatedAt = :ts WHERE id = :bookId")
    suspend fun updateSectionSynced(bookId: Long, sectionId: Long?, ts: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY page ASC")
    fun observeForBook(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY page ASC")
    suspend fun getForBook(bookId: Long): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND page = :page")
    suspend fun deleteByPage(bookId: Long, page: Int)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: Long)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND page = :page LIMIT 1")
    suspend fun findByPage(bookId: Long, page: Int): BookmarkEntity?
}

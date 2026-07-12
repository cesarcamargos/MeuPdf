package com.example.meupdf.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sections")
data class SectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val orderIndex: Int = 0,
    val syncId: String? = null,           // stable cross-device id (UUID)
    val updatedAt: Long = 0,              // for last-write-wins merge
    val deleted: Boolean = false,         // tombstone so deletions propagate
)

@Entity(
    tableName = "books",
    foreignKeys = [
        ForeignKey(
            entity = SectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sectionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("sectionId")],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val uri: String,
    val coverPath: String?,
    val sectionId: Long?,
    val lastPage: Int = 0,
    val pageCount: Int = 0,
    val fontScale: Float = 1.0f,
    val dateAdded: Long = System.currentTimeMillis(),
    val contentHash: String? = null,      // SHA-256 of the PDF bytes; stable across devices (cloud key)
    val relativePath: String? = null,     // legacy: file name inside the old synced SAF tree (unused in cloud mode)
    val progressUpdatedAt: Long = 0,      // last-write-wins timestamp for this book's progress
    val deleted: Boolean = false,         // tombstone so deletions propagate across devices
    val pendingUpload: Boolean = false,   // PDF/cover not yet uploaded to the cloud (retry in background)
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val page: Int,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
)

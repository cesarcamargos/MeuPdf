package com.example.meupdf.sync

import com.example.meupdf.cloud.CloudStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A section as carried in the sync sidecar (identity = syncId). */
data class SyncSection(
    val syncId: String,
    val name: String,
    val orderIndex: Int,
    val updatedAt: Long,
    val deleted: Boolean,
)

/** A book + its reading progress as carried in the sync sidecar (identity = content hash). */
data class SyncBook(
    val hash: String,
    val title: String,
    val sectionSyncId: String?,
    val pageCount: Int,
    val lastPage: Int,
    val fontScale: Float,
    val bookmarks: List<Pair<Int, String>>,
    val updatedAt: Long,
    val deleted: Boolean,
)

data class MergedState(
    val sections: Map<String, SyncSection>,  // by syncId
    val books: Map<String, SyncBook>,         // by content hash
)

/**
 * Reads/merges/writes the per-device `progress/<deviceId>.json` sidecars in Blob storage.
 * Each device writes ONLY its own sidecar (its full view of the library), so there are no
 * write conflicts. Merge is last-write-wins per entity, tombstones (`deleted`) included.
 */
class ProgressStore(
    private val cloudStore: CloudStore,
    private val syncPrefs: SyncPrefs,
) {
    private val writeMutex = Mutex()

    private companion object {
        const val DIR = "progress/"
        const val VERSION = 2
    }

    private suspend fun localSidecarPath() = "$DIR${syncPrefs.deviceId()}.json"

    /** Merge of every sidecar: for each section/book keep the entry with the greatest updatedAt. */
    suspend fun readMerged(): MergedState = withContext(Dispatchers.IO) {
        val sections = HashMap<String, SyncSection>()
        val books = HashMap<String, SyncBook>()
        for (name in cloudStore.list(DIR)) {
            if (!name.endsWith(".json")) continue
            val text = cloudStore.getText(name) ?: continue
            val parsed = runCatching { parse(text) }.getOrNull() ?: continue
            for (s in parsed.sections.values) {
                val cur = sections[s.syncId]
                if (cur == null || s.updatedAt > cur.updatedAt) sections[s.syncId] = s
            }
            for (b in parsed.books.values) {
                val cur = books[b.hash]
                if (cur == null || b.updatedAt > cur.updatedAt) books[b.hash] = b
            }
        }
        MergedState(sections, books)
    }

    /** Rewrites this device's sidecar with its full current view of the library. */
    suspend fun writeLocalState(sections: List<SyncSection>, books: List<SyncBook>) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            cloudStore.putText(localSidecarPath(), build(syncPrefs.deviceId(), sections, books))
        }
    }

    private fun parse(text: String): MergedState {
        val root = JSONObject(text)
        val sections = HashMap<String, SyncSection>()
        root.optJSONArray("sections")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val syncId = s.optString("syncId").takeIf { it.isNotEmpty() } ?: continue
                sections[syncId] = SyncSection(
                    syncId = syncId,
                    name = s.optString("name", ""),
                    orderIndex = s.optInt("orderIndex", 0),
                    updatedAt = s.optLong("updatedAt", 0),
                    deleted = s.optBoolean("deleted", false),
                )
            }
        }
        val books = HashMap<String, SyncBook>()
        root.optJSONObject("books")?.let { obj ->
            for (hash in obj.keys()) {
                val b = obj.getJSONObject(hash)
                val bookmarks = mutableListOf<Pair<Int, String>>()
                b.optJSONArray("bookmarks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val bm = arr.getJSONObject(i)
                        bookmarks += bm.getInt("page") to bm.optString("label", "Page ${bm.getInt("page") + 1}")
                    }
                }
                books[hash] = SyncBook(
                    hash = hash,
                    title = b.optString("title", ""),
                    sectionSyncId = b.optString("sectionSyncId").takeIf { it.isNotEmpty() && it != "null" },
                    pageCount = b.optInt("pageCount", 0),
                    lastPage = b.optInt("lastPage", 0),
                    fontScale = b.optDouble("fontScale", 1.0).toFloat(),
                    bookmarks = bookmarks,
                    updatedAt = b.optLong("updatedAt", 0),
                    deleted = b.optBoolean("deleted", false),
                )
            }
        }
        return MergedState(sections, books)
    }

    private fun build(deviceId: String, sections: List<SyncSection>, books: List<SyncBook>): String {
        val sectionsArr = JSONArray()
        for (s in sections) {
            sectionsArr.put(
                JSONObject()
                    .put("syncId", s.syncId)
                    .put("name", s.name)
                    .put("orderIndex", s.orderIndex)
                    .put("updatedAt", s.updatedAt)
                    .put("deleted", s.deleted),
            )
        }
        val booksObj = JSONObject()
        for (b in books) {
            val bookmarksArr = JSONArray()
            for ((page, label) in b.bookmarks) {
                bookmarksArr.put(JSONObject().put("page", page).put("label", label))
            }
            booksObj.put(
                b.hash,
                JSONObject()
                    .put("title", b.title)
                    .put("sectionSyncId", b.sectionSyncId ?: JSONObject.NULL)
                    .put("pageCount", b.pageCount)
                    .put("lastPage", b.lastPage)
                    .put("fontScale", b.fontScale.toDouble())
                    .put("bookmarks", bookmarksArr)
                    .put("updatedAt", b.updatedAt)
                    .put("deleted", b.deleted),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("deviceId", deviceId)
            .put("sections", sectionsArr)
            .put("books", booksObj)
            .toString(2)
    }
}

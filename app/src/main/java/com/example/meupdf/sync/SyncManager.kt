package com.example.meupdf.sync

import com.example.meupdf.cloud.CloudStore
import com.example.meupdf.data.LibraryRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reconciles the local Room cache with the cloud:
 *  1. retries any pending uploads,
 *  2. pulls & merges every device's sidecar and applies the newest state locally,
 *  3. pushes this device's current state back up.
 * Safe to call repeatedly (on start and on library resume); never throws.
 */
class SyncManager(
    private val repository: LibraryRepository,
    private val cloudStore: CloudStore,
    private val progressStore: ProgressStore,
) {
    private val runMutex = Mutex()

    suspend fun sync() {
        if (runMutex.isLocked) return
        runMutex.withLock {
            runCatching { syncInternal() }
        }
    }

    private suspend fun syncInternal() {
        if (!cloudStore.isReady()) return
        repository.uploadPending()
        repository.repairLegacyBooks()
        val merged = progressStore.readMerged()
        repository.applyMergedState(merged)
        repository.pushLocalStateNow()
    }
}

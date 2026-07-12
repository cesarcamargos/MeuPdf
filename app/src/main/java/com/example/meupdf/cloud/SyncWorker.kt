package com.example.meupdf.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.meupdf.MeuPdfApplication

/** Periodic background reconciliation with the cloud (pull newest state, retry pending uploads). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as MeuPdfApplication).graph
        return runCatching { graph.syncManager.sync() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}

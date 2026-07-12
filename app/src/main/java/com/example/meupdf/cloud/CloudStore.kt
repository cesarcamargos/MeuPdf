package com.example.meupdf.cloud

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Blob storage access over the SAS URL issued by the Function. Everything lives under
 * `users/<userId>/`. PDFs and covers are cached locally under cacheDir once downloaded.
 */
class CloudStore(
    private val context: Context,
    private val api: CloudApi,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private val octet = "application/octet-stream".toMediaType()

    suspend fun isReady(): Boolean = api.userId() != null

    private suspend fun userPrefix(): String {
        val userId = api.userId() ?: throw CloudAuthException("Not logged in")
        return "users/$userId/"
    }

    // sasUrl looks like: https://acct.blob.core.windows.net/meupdf?<sasQuery>
    private fun containerAndQuery(sasUrl: String): Pair<String, String> {
        val idx = sasUrl.indexOf('?')
        return sasUrl.substring(0, idx) to sasUrl.substring(idx + 1)
    }

    private suspend fun blobUrl(relPath: String): String {
        val (container, query) = containerAndQuery(api.validSasUrl())
        return "$container/${userPrefix()}$relPath?$query"
    }

    // ---- Text blobs (progress sidecars) ----

    suspend fun getText(relPath: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(blobUrl(relPath)).get().build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> null
                resp.isSuccessful -> resp.body?.string()
                else -> null
            }
        }
    }

    suspend fun putText(relPath: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val body = text.toByteArray().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(blobUrl(relPath)).put(body)
            .addHeader("x-ms-blob-type", "BlockBlob").build()
        client.newCall(req).execute().use { it.isSuccessful }
    }

    // ---- Binary blobs (PDFs, covers) ----

    suspend fun putFile(relPath: String, file: File, contentType: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = file.asRequestBody(contentType.toMediaType())
            val req = Request.Builder().url(blobUrl(relPath)).put(body)
                .addHeader("x-ms-blob-type", "BlockBlob").build()
            client.newCall(req).execute().use { it.isSuccessful }
        }

    suspend fun putStream(relPath: String, input: InputStream, contentType: String): Boolean =
        withContext(Dispatchers.IO) {
            // Buffer to a temp file so OkHttp can set Content-Length (block blob PUT needs it).
            val tmp = File.createTempFile("upload", null, context.cacheDir)
            try {
                tmp.outputStream().use { out -> input.use { it.copyTo(out) } }
                putFile(relPath, tmp, contentType)
            } finally {
                tmp.delete()
            }
        }

    suspend fun exists(relPath: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(blobUrl(relPath)).head().build()
        client.newCall(req).execute().use { it.isSuccessful }
    }

    suspend fun delete(relPath: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(blobUrl(relPath)).delete().build()
        client.newCall(req).execute().use { it.isSuccessful || it.code == 404 }
    }

    /** Downloads to a cache file; returns it (or null on failure). Skips the network if cached. */
    suspend fun downloadToCache(relPath: String, cacheName: String): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "cloud").apply { mkdirs() }
        val dest = File(dir, cacheName)
        if (dest.exists() && dest.length() > 0) return@withContext dest
        val req = Request.Builder().url(blobUrl(relPath)).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val bodyStream = resp.body?.byteStream() ?: return@withContext null
            dest.outputStream().use { out -> bodyStream.use { it.copyTo(out) } }
        }
        dest.takeIf { it.exists() && it.length() > 0 }
    }

    /** Lists blob names (relative to the user prefix) under a sub-prefix, e.g. "progress/". */
    suspend fun list(subPrefix: String): List<String> = withContext(Dispatchers.IO) {
        val (container, query) = containerAndQuery(api.validSasUrl())
        val fullPrefix = "${userPrefix()}$subPrefix"
        val encoded = URLEncoder.encode(fullPrefix, "UTF-8").replace("%2F", "/").replace("+", "%20")
        val url = "$container?restype=container&comp=list&prefix=$encoded&$query"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            val xml = resp.body?.byteStream() ?: return@withContext emptyList()
            parseBlobNames(xml).map { it.removePrefix(userPrefix()) }
        }
    }

    private fun parseBlobNames(input: InputStream): List<String> {
        val names = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Name") {
                names += parser.nextText()
            }
            event = parser.next()
        }
        return names
    }
}

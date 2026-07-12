package com.example.meupdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.harissk.pdfium.PdfiumCore
import java.io.Closeable
import java.io.IOException

data class WordHit(val word: String)

class PdfEngine(private val context: Context) {

    @Throws(IOException::class)
    fun open(uri: Uri): OpenDocument {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Unable to open file descriptor for $uri")
        val core = PdfiumCore()
        core.newDocument(pfd)
        return OpenDocument(core, pfd)
    }

    class OpenDocument(
        private val core: PdfiumCore,
        private val pfd: ParcelFileDescriptor,
    ) : Closeable {
        private val openedPages = mutableSetOf<Int>()

        val pageCount: Int get() = core.pageCount

        private fun ensurePageOpen(page: Int) {
            if (page !in openedPages) {
                core.openPage(page)
                openedPages.add(page)
            }
        }

        fun pageAspectRatio(page: Int): Float {
            val size = core.getPageSize(page)
            if (size.height == 0) return 0.7f
            return size.width.toFloat() / size.height.toFloat()
        }

        fun renderPage(page: Int, widthPx: Int, heightPx: Int): Bitmap {
            ensurePageOpen(page)
            val bitmap = Bitmap.createBitmap(
                widthPx.coerceAtLeast(1),
                heightPx.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(android.graphics.Color.WHITE)
            core.renderPageBitmap(bitmap, page, 0, 0, bitmap.width, bitmap.height)
            return bitmap
        }

        fun renderCoverThumbnail(targetWidthPx: Int = 320): Bitmap? {
            if (pageCount == 0) return null
            val aspect = pageAspectRatio(0)
            val height = (targetWidthPx / aspect).toInt().coerceAtLeast(1)
            return renderPage(0, targetWidthPx, height)
        }

        /**
         * Maps a tap in rendered-bitmap pixel space back to PDF user-space coordinates
         * (via pdfium's device->page transform, using the same box the page was rendered
         * into) to find which word, if any, was tapped.
         */
        fun wordAt(page: Int, xPx: Float, yPx: Float, renderedWidthPx: Int, renderedHeightPx: Int): WordHit? {
            ensurePageOpen(page)
            val pagePoint = core.mapDeviceCoordinateToPage(
                page, 0, 0, renderedWidthPx, renderedHeightPx, 0, xPx.toInt(), yPx.toInt(),
            )
            val tolerance = 8.0
            val charIndex = core.getCharacterIndex(
                page, pagePoint.x.toDouble(), pagePoint.y.toDouble(), tolerance, tolerance,
            )
            if (charIndex < 0) return null
            val totalChars = core.countCharactersOnPage(page)
            if (!isWordChar(core.extractCharacter(page, charIndex))) return null

            var start = charIndex
            while (start > 0 && isWordChar(core.extractCharacter(page, start - 1))) start--
            var end = charIndex
            while (end < totalChars - 1 && isWordChar(core.extractCharacter(page, end + 1))) end++

            val word = core.extractCharacters(page, start, end - start + 1)?.trim()
            return word?.takeIf { it.isNotEmpty() }?.let { WordHit(it) }
        }

        private fun isWordChar(c: Char) = c.isLetter() || c == '\''

        override fun close() {
            core.close()
            pfd.close()
        }
    }
}

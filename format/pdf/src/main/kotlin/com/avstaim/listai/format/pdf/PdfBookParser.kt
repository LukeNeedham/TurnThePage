package com.avstaim.listai.format.pdf

import android.content.Context
import android.os.Build
import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.BookMetadata
import com.avstaim.listai.format.fb2.BookParseException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

data class PdfParserLimits(
    val maxDocumentBytes: Long = 256L * 1024 * 1024,
    val maxPages: Int = 10_000,
) {
    init { require(maxDocumentBytes > 0 && maxPages > 0) }
}

/**
 * Validates a seekable, app-owned PDF and records its page dimensions, without rasterizing it.
 * The caller owns [File] lifetime; keep the private copy while its BookDocument is in use.
 * Reopening an imported file reconstructs the same stable page anchors and content hash.
 */
class PdfBookParser(
    private val context: Context,
    private val limits: PdfParserLimits = PdfParserLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun supports(fileName: String): Boolean = fileName.trim().lowercase(Locale.ROOT).endsWith(".pdf")

    suspend fun parse(file: File, fileName: String? = file.name): BookDocument = runInterruptible(ioDispatcher) {
        try {
            if (!file.isFile || file.length() == 0L) throw BookParseException.MalformedDocument()
            if (file.length() > limits.maxDocumentBytes) {
                throw BookParseException.ResourceLimitExceeded("PDF bytes", limits.maxDocumentBytes)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val header = ByteArray(5)
                val headerSize = input.read(header)
                if (headerSize != header.size || !header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) {
                    throw BookParseException.MalformedDocument()
                }
                digest.update(header, 0, headerSize)
                val buffer = ByteArray(32 * 1024)
                var total = headerSize.toLong()
                while (true) {
                    checkCancellation()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limits.maxDocumentBytes) {
                        throw BookParseException.ResourceLimitExceeded("PDF bytes", limits.maxDocumentBytes)
                    }
                    digest.update(buffer, 0, count)
                }
            }
            val sizes = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                readLegacyPdfPageSizes(context.applicationContext, file, limits.maxPages)
            } else readPdfPageSizes(file, limits.maxPages)
            val blocks = List(sizes.size / 2) { index ->
                checkCancellation()
                BookBlock.FixedPage(BlockId("pdf-page-${index + 1}"), file.absolutePath,
                    PDF_MEDIA_TYPE, index, sizes[index * 2], sizes[index * 2 + 1])
            }
            val title = fileName?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.replace(Regex("(?i)\\.pdf$"), "")?.trim()?.takeIf(String::isNotBlank) ?: "PDF"
            BookDocument(
                id = BookId("pdf-" + digest.digest().joinToString("") { "%02x".format(it) }),
                metadata = BookMetadata(title = title),
                blocks = blocks,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: BookParseException) {
            throw error
        } catch (error: SecurityException) {
            checkCancellation()
            throw BookParseException.EncryptedDocument(error)
        } catch (error: IOException) {
            checkCancellation()
            throw BookParseException.MalformedDocument(error)
        } catch (error: IllegalArgumentException) {
            checkCancellation()
            throw BookParseException.MalformedDocument(error)
        } catch (error: IllegalStateException) {
            checkCancellation()
            throw BookParseException.MalformedDocument(error)
        }
    }
}

private const val PDF_MEDIA_TYPE = "application/pdf"

private fun checkCancellation() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("PDF parsing was cancelled")
}

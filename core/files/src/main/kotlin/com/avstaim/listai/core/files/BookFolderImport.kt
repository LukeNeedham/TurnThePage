package com.avstaim.listai.core.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Walks the granted SAF tree, including nested folders, without touching source files. */
suspend fun visitBookFolder(
    resolver: ContentResolver,
    tree: Uri,
    onBook: suspend (Uri) -> Unit,
    onUnreadableFolder: suspend () -> Unit,
    onSkippedFile: suspend () -> Unit = {},
): Int = withContext(Dispatchers.IO) {
    val pending = ArrayDeque<String>()
    val visited = HashSet<String>()
    pending.add(DocumentsContract.getTreeDocumentId(tree))
    var skipped = 0
    var entries = 0
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val id = pending.removeLast()
        if (!visited.add(id)) continue
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val rows: List<Triple<String, String?, String?>> = try {
            runInterruptible {
                resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    buildList<Triple<String, String?, String?>> {
                        while (cursor.moveToNext()) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            if (++entries > 100_000) throw FolderTooLargeException()
                            add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                        }
                    }
                } ?: throw IOException("Folder is unavailable")
            }
        } catch (failure: SecurityException) {
            onUnreadableFolder(); continue
        } catch (failure: IOException) {
            if (failure is FolderTooLargeException) throw failure
            onUnreadableFolder(); continue
        }
        for ((childId, name, mimeType) in rows.sortedBy { it.second.orEmpty() }) {
            currentCoroutineContext().ensureActive()
            if (mimeType == Document.MIME_TYPE_DIR) pending.add(childId)
            else if (BookFormat.accepts(name, mimeType)) {
                onBook(DocumentsContract.buildDocumentUriUsingTree(tree, childId))
            } else { skipped++; onSkippedFile() }
        }
    }
    skipped
}

class FolderTooLargeException : IOException("Folder contains too many entries")

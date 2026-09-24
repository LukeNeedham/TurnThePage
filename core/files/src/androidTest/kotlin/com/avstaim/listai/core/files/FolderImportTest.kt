package com.avstaim.listai.core.files

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.database.AppPreferences
import com.avstaim.listai.core.database.LibraryDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real resolver/SAF URI traversal including deep folders, duplicates, corrupt files and a cycle. */
@RunWith(AndroidJUnit4::class)
class FolderImportTest {
    @Test fun recursiveImportContinuesAfterBadBookAndPreservesDuplicates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "folder-${UUID.randomUUID()}.db"
        val database = LibraryDatabase.open(context, dbName)
        val storage = PrivateBookStorage(File(context.cacheDir, "folder-${UUID.randomUUID()}"))
        val repository = LibraryRepository(context, database.library(), storage, AppPreferences(context))
        try {
            var imported = 0
            var failures = 0
            val skipped = visitBookFolder(context.contentResolver,
                DocumentsContract.buildTreeDocumentUri(FolderFixtureProvider.AUTHORITY, "root"),
                onBook = { uri ->
                    try { repository.import(context.contentResolver, uri); imported++ }
                    catch (_: com.avstaim.listai.format.fb2.BookParseException) { failures++ }
                }, onUnreadableFolder = { failures++ })
            assertEquals(3, imported)
            assertEquals(1, failures)
            assertEquals(1, skipped)
            assertEquals(setOf("One", "Two"), repository.books.first().map { it.title }.toSet())
            assertEquals(2, storage.directory.listFiles()!!.size)
        } finally { database.close(); context.deleteDatabase(dbName); storage.directory.deleteRecursively() }
    }
}

class FolderFixtureProvider : ContentProvider() {
    companion object { const val AUTHORITY = "com.avstaim.listai.core.files.test.folder" }
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val cursor = MatrixCursor(columns)
        val children = uri.lastPathSegment == "children"
        val id = DocumentsContract.getDocumentId(uri)
        val entries = if (children) when (id) {
            "root" -> listOf(Triple("one", "one.fb2", "application/octet-stream"), Triple("nested", "Nested", Document.MIME_TYPE_DIR), Triple("photo", "photo.jpg", "image/jpeg"))
            "nested" -> listOf(Triple("bad", "bad.fb2", "application/octet-stream"), Triple("deep", "Deep", Document.MIME_TYPE_DIR))
            "deep" -> listOf(Triple("two", "two.fb2", "application/octet-stream"), Triple("duplicate", "copy.fb2", "application/octet-stream"), Triple("root", "Cycle", Document.MIME_TYPE_DIR))
            else -> emptyList()
        } else listOf(Triple(id, "$id.fb2", "application/octet-stream"))
        entries.forEach { (entryId, name, mime) -> cursor.addRow(columns.map { column -> when (column) {
            Document.COLUMN_DOCUMENT_ID -> entryId
            Document.COLUMN_DISPLAY_NAME -> name
            Document.COLUMN_MIME_TYPE -> mime
            else -> null
        } }) }
        return cursor
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = DocumentsContract.getDocumentId(uri)
        val title = if (id == "two") "Two" else "One"
        val file = File(requireNotNull(context).cacheDir, "fixture-$id.fb2")
        file.writeText(if (id == "bad") "<broken/>" else """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>$title</book-title></title-info></description><body><section><p>Reading $title.</p></section></body></FictionBook>""")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "application/x-fictionbook+xml"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

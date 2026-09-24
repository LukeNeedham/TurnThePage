package com.avstaim.listai.core.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V11ShelfMigrationTest {
    @Test fun v3MigrationPreservesStateAndManualOrderSurvivesOpenAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "v11-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE books (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, authors TEXT NOT NULL, fileName TEXT NOT NULL, coverName TEXT, importedAt INTEGER NOT NULL, lastOpenedAt INTEGER, totalCharacters INTEGER NOT NULL, deleting INTEGER NOT NULL, blockId TEXT, charOffset INTEGER NOT NULL, globalOffset INTEGER NOT NULL, cover TEXT NOT NULL, fraction REAL NOT NULL, tocEntryIndex INTEGER, tocCharOffset INTEGER NOT NULL DEFAULT 0, annotationBlockId TEXT, annotationCharOffset INTEGER NOT NULL DEFAULT 0, annotationGlobalOffset INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE bookmarks (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, blockId TEXT NOT NULL, charOffset INTEGER NOT NULL, globalOffset INTEGER NOT NULL, createdAt INTEGER NOT NULL, snippet TEXT NOT NULL, FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_bookmarks_bookId ON bookmarks(bookId)")
            db.execSQL("CREATE UNIQUE INDEX index_bookmarks_bookId_blockId_charOffset ON bookmarks(bookId,blockId,charOffset)")
            for (index in 1..3) {
                db.execSQL("INSERT INTO books VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf("book-$index", "Title $index", "Author", "$index.book", null, index, index * 10,
                        1000, 0, "body", 12, 45, "OPEN", .4, 2, 4, "annotation", 6, 8))
            }
            db.execSQL("INSERT INTO bookmarks VALUES('mark','book-2','body',12,45,1,'Saved')")
            db.version = 3
        }
        var db = LibraryDatabase.open(context, name)
        try {
            val original = db.library().orderedBooks()
            assertEquals(listOf("book-3", "book-2", "book-1"), original.map { it.id })
            assertEquals("annotation", original[1].progress.annotationBlockId)
            assertEquals(6, original[1].progress.annotationCharOffset)
            db.library().moveBook("book-1", "book-3")
            db.library().markOpened("book-2", 9999)
            assertEquals(listOf("book-1", "book-3", "book-2"), db.library().observeBooks().first().map { it.id })
            db.close()
            db = LibraryDatabase.open(context, name)
            assertEquals(listOf("book-1", "book-3", "book-2"), db.library().orderedBooks().map { it.id })
            assertEquals(original[1].progress, db.library().book("book-2")!!.progress)
            assertEquals("Saved", db.library().observeBookmarks("book-2").first().single().snippet)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

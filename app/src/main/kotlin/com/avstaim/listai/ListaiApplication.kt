package com.avstaim.listai

import android.app.Application
import com.avstaim.listai.core.database.AppPreferences
import com.avstaim.listai.core.database.LibraryDatabase
import com.avstaim.listai.core.files.LibraryRepository
import com.avstaim.listai.core.files.PrivateBookStorage
import java.io.File

class ListaiApplication : Application() {
    val controller: LibraryController by lazy {
        val preferences = AppPreferences(this)
        LibraryController(this, LibraryRepository(LibraryDatabase.open(this).library(),
            PrivateBookStorage(File(filesDir, "books")), preferences), preferences)
    }
}

package com.avstaim.listai.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.avstaim.listai.core.model.ReaderPreferences
import com.avstaim.listai.core.model.ReaderTheme
import com.avstaim.listai.core.model.ShelfZoom
import kotlinx.coroutines.flow.map

private val Context.appPreferences by preferencesDataStore("app_state",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })

/** One application-context DataStore instance; book checkpoints themselves live in Room. */
class AppPreferences internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.appPreferences)
    val lastBook = store.data.map { it[LAST_BOOK] }
    val initialized = store.data.map { it[INITIALIZED] ?: false }
    /** Completion is installation-wide; replaying the guide never clears this flag. */
    val readerOnboardingCompleted = store.data.map { it[READER_ONBOARDING_COMPLETED] ?: false }
    val reader = store.data.map { values ->
        val size = values[FONT_SIZE]?.takeIf { it.isFinite() && it in 12f..32f } ?: 18f
        ReaderPreferences(size, ReaderTheme.entries.firstOrNull { it.name == values[THEME] } ?: ReaderTheme.LIGHT,
            showClock = values[SHOW_CLOCK] ?: true)
    }
    val shelfZoom = store.data.map { values ->
        ShelfZoom.entries.firstOrNull { it.name == values[SHELF_ZOOM] } ?: ShelfZoom.MEDIUM
    }

    suspend fun selectBook(id: String?) { store.edit { if (id == null) it.remove(LAST_BOOK) else it[LAST_BOOK] = id } }
    suspend fun clearBookIfSelected(id: String) { store.edit { if (it[LAST_BOOK] == id) it.remove(LAST_BOOK) } }
    suspend fun markInitialized() { store.edit { it[INITIALIZED] = true } }
    suspend fun completeReaderOnboarding() { store.edit { it[READER_ONBOARDING_COMPLETED] = true } }
    suspend fun setReaderPreferences(preferences: ReaderPreferences) {
        store.edit { values ->
            values[FONT_SIZE] = preferences.fontSizeSp
            values[THEME] = preferences.theme.name
            values[SHOW_CLOCK] = preferences.showClock
        }
    }

    suspend fun setShelfZoom(zoom: ShelfZoom) { store.edit { it[SHELF_ZOOM] = zoom.name } }

    private companion object {
        val LAST_BOOK = stringPreferencesKey("last_book")
        val INITIALIZED = booleanPreferencesKey("initialized")
        val READER_ONBOARDING_COMPLETED = booleanPreferencesKey("reader_onboarding_completed")
        val FONT_SIZE = floatPreferencesKey("reader_font_size_sp")
        val THEME = stringPreferencesKey("reader_theme")
        val SHOW_CLOCK = booleanPreferencesKey("reader_show_clock")
        val SHELF_ZOOM = stringPreferencesKey("shelf_zoom")
    }
}

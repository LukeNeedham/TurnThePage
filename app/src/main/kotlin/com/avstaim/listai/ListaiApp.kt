package com.avstaim.listai

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.feature.library.LibraryScreen
import com.avstaim.listai.feature.reader.ReaderScreen
import kotlinx.coroutines.flow.flowOf

@Composable
fun ListaiApp(
    modifier: Modifier = Modifier,
    devicePosture: DevicePosture = DevicePosture.Unknown,
    darkTheme: Boolean = false,
    onReaderVisibleChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val controller = remember(context) { (context.applicationContext as ListaiApplication).controller }
    val books by controller.books.collectAsState()
    val selection by controller.selection.collectAsState()
    val preferences by controller.readerPreferences.collectAsState()
    val busy by controller.busy.collectAsState()
    val importing by controller.importing.collectAsState()
    val error by controller.error.collectAsState()
    val bookmarkFlow = remember(selection?.opened?.book?.id) {
        selection?.opened?.book?.id?.let(controller.repository::bookmarks) ?: flowOf(emptyList())
    }
    val bookmarks by bookmarkFlow.collectAsState(initial = emptyList())
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.import(it, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
    }
    SideEffect { onReaderVisibleChange(selection != null && !busy) }
    BackHandler(enabled = selection != null) { controller.returnToShelf() }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val selected = selection
        if (busy) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else if (selected != null) {
            key(selected.opened.book.id.value, selected.session) {
                ReaderScreen(
                    document = selected.opened.document,
                    modifier = Modifier.fillMaxSize(),
                    devicePosture = devicePosture,
                    initialProgress = selected.initialProgress,
                    onProgressChanged = { controller.progressChanged(selected.opened.book.id, selected.session, it) },
                    preferences = preferences,
                    onPreferencesChange = controller::setReaderPreferences,
                    darkTheme = darkTheme,
                    bookmarks = bookmarks,
                    onAddBookmark = controller::addBookmark,
                    onDeleteBookmark = controller::deleteBookmark,
                    onBookmarkSelected = controller::jump,
                    onReturnToShelf = controller::returnToShelf,
                )
            }
        } else {
            LibraryScreen(books, importing, { picker.launch(arrayOf("*/*")) }, controller::cancelImport,
                controller::open, controller::delete)
        }
    }
    error?.let { message ->
        AlertDialog(onDismissRequest = controller::dismissError,
            title = { Text(stringResource(R.string.library_error_title)) },
            text = { Text(stringResource(message)) },
            confirmButton = { TextButton(onClick = controller::dismissError) { Text(stringResource(R.string.action_ok)) } })
    }
}

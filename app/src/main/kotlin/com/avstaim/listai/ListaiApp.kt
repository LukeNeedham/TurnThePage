package com.avstaim.listai

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookTocEntry
import com.avstaim.listai.core.model.ParagraphRole
import com.avstaim.listai.core.model.QuoteRole
import com.avstaim.listai.core.model.plainText
import com.avstaim.listai.feature.library.LibraryPlaceholderScreen
import com.avstaim.listai.feature.reader.ReaderDemoScreen
import com.avstaim.listai.format.fb2.Fb2BookParser
import com.avstaim.listai.format.fb2.BookParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AppDestination(@param:StringRes val labelRes: Int) {
    READER(R.string.destination_reader),
    LIBRARY(R.string.destination_library),
    DIAGNOSTICS(R.string.destination_diagnostics),
}

@Composable
fun ListaiApp(
    modifier: Modifier = Modifier,
    devicePosture: DevicePosture = DevicePosture.Unknown,
    readerFullscreen: Boolean = false,
    onReaderFullscreenChange: (Boolean) -> Unit = {},
) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.READER) }
    val readerFullscreenActive = destination == AppDestination.READER && readerFullscreen

    LaunchedEffect(destination) {
        if (destination != AppDestination.READER && readerFullscreen) {
            onReaderFullscreenChange(false)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = if (readerFullscreenActive) {
            WindowInsets(left = 0, top = 0, right = 0, bottom = 0)
        } else {
            androidx.compose.material3.ScaffoldDefaults.contentWindowInsets
        },
        bottomBar = {
            if (!readerFullscreenActive) {
                NavigationBar(modifier = Modifier.testTag(APP_BOTTOM_NAVIGATION_TAG)) {
                    AppDestination.entries.forEach { item ->
                        NavigationBarItem(
                            modifier = if (item == AppDestination.DIAGNOSTICS) {
                                Modifier.testTag(APP_DIAGNOSTICS_NAVIGATION_TAG)
                            } else {
                                Modifier
                            },
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { DestinationMarker(selected = destination == item) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        when (destination) {
            AppDestination.READER -> ReaderDemoScreen(
                modifier = if (readerFullscreenActive) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.padding(contentPadding)
                },
                devicePosture = devicePosture,
                isFullscreen = readerFullscreenActive,
                onFullscreenChange = onReaderFullscreenChange,
            )

            AppDestination.LIBRARY -> LibraryPlaceholderScreen(
                modifier = Modifier.padding(contentPadding),
            )

            AppDestination.DIAGNOSTICS -> DiagnosticsScreen(
                modifier = Modifier.padding(contentPadding),
                devicePosture = devicePosture,
            )
        }
    }
}

internal const val APP_BOTTOM_NAVIGATION_TAG = "app_bottom_navigation"
internal const val APP_DIAGNOSTICS_NAVIGATION_TAG = "app_diagnostics_navigation"

@Composable
private fun DestinationMarker(selected: Boolean) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(18.dp)
            .clearAndSetSemantics { }
            .border(2.dp, color, CircleShape)
            .then(
                if (selected) Modifier.background(color, CircleShape).padding(4.dp)
                else Modifier,
            ),
    )
}

@Composable
private fun DiagnosticsScreen(
    modifier: Modifier = Modifier,
    devicePosture: DevicePosture,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val packageName = context.packageName
    var previewState by remember { mutableStateOf<Stage3PreviewState>(Stage3PreviewState.Loading) }

    LaunchedEffect(resources) {
        previewState = try {
            val document = withContext(Dispatchers.IO) {
                resources.openRawResource(R.raw.stage3_preview).use { input ->
                    Fb2BookParser().parse(input, fileName = "stage3_preview.fb2")
                }
            }
            Stage3PreviewState.Ready(document)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Stage3PreviewState.Failed(
                messageRes = error.toLocalizedStage3Error(),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.diagnostics_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.diagnostics_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        DiagnosticRow(
            label = stringResource(R.string.diagnostics_package),
            value = packageName,
        )
        DiagnosticRow(
            label = stringResource(R.string.diagnostics_platform),
            value = stringResource(R.string.diagnostics_platform_value),
        )
        DiagnosticRow(
            label = stringResource(R.string.diagnostics_posture),
            value = when (devicePosture) {
                DevicePosture.Unknown -> stringResource(R.string.diagnostics_posture_unknown)
                is DevicePosture.Flat -> stringResource(R.string.diagnostics_posture_flat)
                is DevicePosture.HalfOpened -> stringResource(
                    R.string.diagnostics_posture_half_opened,
                )
            },
        )
        DiagnosticRow(
            label = stringResource(R.string.diagnostics_renderer),
            value = stringResource(R.string.diagnostics_renderer_value),
        )
        DiagnosticRow(
            label = stringResource(R.string.diagnostics_fake_book),
            value = stringResource(R.string.diagnostics_fake_book_value),
        )
        HorizontalDivider()
        Text(
            text = stringResource(R.string.diagnostics_fb2_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        when (val state = previewState) {
            Stage3PreviewState.Loading -> Text(
                text = stringResource(R.string.diagnostics_fb2_loading),
                modifier = Modifier.testTag(STAGE3_DIAGNOSTICS_LOADING_TAG),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is Stage3PreviewState.Failed -> Text(
                text = stringResource(state.messageRes),
                modifier = Modifier.testTag(STAGE3_DIAGNOSTICS_ERROR_TAG),
                color = MaterialTheme.colorScheme.error,
            )

            is Stage3PreviewState.Ready -> ParsedDocumentDiagnostics(state.document)
        }
    }
}

internal const val STAGE3_DIAGNOSTICS_LOADING_TAG = "stage3_diagnostics_loading"
internal const val STAGE3_DIAGNOSTICS_READY_TAG = "stage3_diagnostics_ready"
internal const val STAGE3_DIAGNOSTICS_ERROR_TAG = "stage3_diagnostics_error"

private sealed interface Stage3PreviewState {
    data object Loading : Stage3PreviewState

    data class Ready(val document: BookDocument) : Stage3PreviewState

    data class Failed(@param:StringRes val messageRes: Int) : Stage3PreviewState
}

@StringRes
private fun Exception.toLocalizedStage3Error(): Int = when (this) {
    is BookParseException.ArchiveContainsNoFb2 -> R.string.diagnostics_fb2_error_empty_archive
    is BookParseException.NestedArchive -> R.string.diagnostics_fb2_error_nested_archive
    is BookParseException.MissingTitle -> R.string.diagnostics_fb2_error_missing_title
    is BookParseException.MissingReadableBody -> R.string.diagnostics_fb2_error_empty_body
    is BookParseException.ResourceLimitExceeded -> R.string.diagnostics_fb2_error_too_large
    is BookParseException.UnsupportedFormat -> R.string.diagnostics_fb2_error_unsupported
    is BookParseException.MalformedDocument -> R.string.diagnostics_fb2_error_malformed
    else -> R.string.diagnostics_fb2_error_unknown
}

@Composable
private fun ParsedDocumentDiagnostics(document: BookDocument) {
    Column(
        modifier = Modifier.testTag(STAGE3_DIAGNOSTICS_READY_TAG),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DiagnosticRow(
            label = stringResource(R.string.diagnostics_fb2_book_title),
            value = document.metadata.title,
        )
        DiagnosticRow(
            label = stringResource(R.string.diagnostics_fb2_authors),
            value = document.metadata.authors.joinToString().ifBlank {
                stringResource(R.string.diagnostics_fb2_none)
            },
        )
        DiagnosticRow(
            label = stringResource(R.string.diagnostics_fb2_language),
            value = document.metadata.language ?: stringResource(R.string.diagnostics_fb2_none),
        )
        DiagnosticRow(
            label = stringResource(R.string.diagnostics_fb2_sequence),
            value = document.metadata.sequence?.let { sequence ->
                sequence.number?.let { number -> "${sequence.name} #$number" } ?: sequence.name
            } ?: stringResource(R.string.diagnostics_fb2_none),
        )
        DiagnosticRow(
            label = stringResource(R.string.diagnostics_fb2_counts),
            value = stringResource(
                R.string.diagnostics_fb2_counts_value,
                document.blocks.size,
                document.tableOfContents.countEntries(),
                document.notes.size,
                document.images.size,
            ),
        )
        document.metadata.annotation?.let { annotation ->
            DiagnosticRow(
                label = stringResource(R.string.diagnostics_fb2_annotation),
                value = annotation,
            )
        }

        DebugSectionTitle(stringResource(R.string.diagnostics_fb2_toc))
        if (document.tableOfContents.isEmpty()) {
            Text(stringResource(R.string.diagnostics_fb2_none))
        } else {
            document.tableOfContents.flattenForDebug().forEach { line ->
                Text(
                    text = stringResource(
                        R.string.diagnostics_fb2_toc_entry,
                        line.entry.title,
                        line.entry.location.blockId.value,
                    ),
                    modifier = Modifier.padding(start = (line.depth * 16).dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        DebugSectionTitle(stringResource(R.string.diagnostics_fb2_stream))
        document.blocks.forEachIndexed { index, block ->
            DebugContentRow(
                marker = "${index + 1}. ${block.debugKind()}",
                text = block.plainText(),
            )
        }

        DebugSectionTitle(stringResource(R.string.diagnostics_fb2_notes))
        if (document.notes.isEmpty()) {
            Text(stringResource(R.string.diagnostics_fb2_none))
        } else {
            document.notes.forEach { note ->
                DebugContentRow(
                    marker = note.id.value,
                    text = note.plainText(),
                )
            }
        }

        DebugSectionTitle(stringResource(R.string.diagnostics_fb2_images))
        if (document.images.isEmpty()) {
            Text(stringResource(R.string.diagnostics_fb2_none))
        } else {
            document.images.forEach { image ->
                DiagnosticRow(
                    label = image.id.value,
                    value = stringResource(
                        R.string.diagnostics_fb2_image_lazy,
                        image.mediaType ?: "application/octet-stream",
                    ),
                )
            }
        }
    }
}

@Composable
private fun DebugSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun DebugContentRow(marker: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = marker,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = text.ifBlank { stringResource(R.string.diagnostics_fb2_empty_block) },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private data class DebugTocLine(
    val depth: Int,
    val entry: BookTocEntry,
)

private fun List<BookTocEntry>.flattenForDebug(depth: Int = 0): List<DebugTocLine> = buildList {
    this@flattenForDebug.forEach { entry ->
        add(DebugTocLine(depth = depth, entry = entry))
        addAll(entry.children.flattenForDebug(depth + 1))
    }
}

private fun List<BookTocEntry>.countEntries(): Int = sumOf { entry ->
    1 + entry.children.countEntries()
}

@Composable
private fun BookBlock.debugKind(): String = when (this) {
    is BookBlock.Heading -> stringResource(R.string.diagnostics_fb2_kind_heading, level)
    is BookBlock.Paragraph -> stringResource(
        R.string.diagnostics_fb2_kind_paragraph,
        role.localizedName(),
    )
    is BookBlock.Image -> stringResource(R.string.diagnostics_fb2_kind_image, imageId.value)
    is BookBlock.Quote -> stringResource(R.string.diagnostics_fb2_kind_quote, role.localizedName())
    is BookBlock.Poem -> stringResource(R.string.diagnostics_fb2_kind_poem, stanzas.size)
    is BookBlock.Spacer -> stringResource(R.string.diagnostics_fb2_kind_spacer)
}

@Composable
private fun ParagraphRole.localizedName(): String = when (this) {
    ParagraphRole.BODY -> stringResource(R.string.diagnostics_fb2_role_body)
    ParagraphRole.SUBTITLE -> stringResource(R.string.diagnostics_fb2_role_subtitle)
    ParagraphRole.TEXT_AUTHOR -> stringResource(R.string.diagnostics_fb2_role_text_author)
    ParagraphRole.VERSE -> stringResource(R.string.diagnostics_fb2_role_verse)
}

@Composable
private fun QuoteRole.localizedName(): String = when (this) {
    QuoteRole.CITE -> stringResource(R.string.diagnostics_fb2_role_cite)
    QuoteRole.EPIGRAPH -> stringResource(R.string.diagnostics_fb2_role_epigraph)
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.38f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.62f),
            fontFamily = FontFamily.Monospace,
        )
    }
}

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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.feature.library.LibraryPlaceholderScreen
import com.avstaim.listai.feature.reader.ReaderDemoScreen

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
    val packageName = LocalContext.current.packageName

    Column(
        modifier = modifier
            .fillMaxSize()
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
    }
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

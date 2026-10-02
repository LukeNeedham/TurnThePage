package com.avstaim.listai.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme

@PreviewTest
@Preview(name = "Clock — full AM PM at enlarged font scale", widthDp = 100, heightDp = 32,
    fontScale = 2f, locale = "en")
@Composable
fun ReaderClockTwelveHourScreenshot() {
    ListaiTheme(darkTheme = true) {
        Box(Modifier.fillMaxSize().background(Color(0xFF252C33)), contentAlignment = Alignment.Center) {
            ReaderClock(active = false, color = Color(0xFF98A5B0), modifier = Modifier.size(64.dp, 12.dp),
                previewTime = "11:58 PM")
        }
    }
}

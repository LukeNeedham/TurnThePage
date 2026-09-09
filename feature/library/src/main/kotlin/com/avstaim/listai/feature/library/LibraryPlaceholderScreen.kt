package com.avstaim.listai.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LibraryPlaceholderScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Color(0xFF836953),
                Color(0xFF536B78),
                Color(0xFF786153),
            ).forEachIndexed { index, color ->
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height((72 + index * 8).dp)
                        .background(color, RoundedCornerShape(4.dp)),
                )
            }
        }

        Text(
            text = stringResource(R.string.library_placeholder_title),
            modifier = Modifier.padding(top = 28.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.library_placeholder_message),
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}


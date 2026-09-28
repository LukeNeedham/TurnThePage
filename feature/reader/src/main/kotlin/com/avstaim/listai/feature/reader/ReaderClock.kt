package com.avstaim.listai.feature.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.util.Date

/** Only this small overlay observes clock broadcasts; pages never depend on wall-clock time. */
@Composable
internal fun ReaderClock(active: Boolean, color: Color, modifier: Modifier = Modifier,
    previewTime: String? = null) {
    val context = LocalContext.current
    val view = LocalView.current
    val inspection = LocalInspectionMode.current
    // Like the printed progress footer, the clock keeps a compact physical ink size.
    // Its full localized time remains available to accessibility at every font scale.
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(.5f)
    fun time() = DateFormat.getTimeFormat(context).format(Date())
    var label by remember(context) { mutableStateOf(time()) }
    DisposableEffect(context, view, active, inspection, previewTime) {
        val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
        var registered = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { label = time() }
        }
        fun stop() {
            if (registered) { context.unregisterReceiver(receiver); registered = false }
        }
        fun start() {
            if (!registered && active && !inspection && previewTime == null) {
                label = time()
                ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_LOCALE_CHANGED)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) start()
        onDispose { lifecycle?.removeObserver(observer); stop() }
    }
    Text(previewTime ?: if (inspection) "10:08" else label, modifier.testTag("reader_clock"),
        color = color, fontSize = (10f / fontScale).sp, lineHeight = (12f / fontScale).sp, maxLines = 1,
        textAlign = TextAlign.Center)
}

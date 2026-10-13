package com.avstaim.listai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.avstaim.listai.core.adaptive.WindowManagerDevicePostureProvider
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.ReaderTheme
import com.avstaim.listai.core.model.ReaderScreenTimeoutMode

class MainActivity : ComponentActivity() {
    private var readerVisible = false
    private var foreground = false
    private var screenTimeoutMode = ReaderScreenTimeoutMode.ALWAYS_ON

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyFullscreen()
        val controller = (application as ListaiApplication).controller
        val postureProvider = WindowManagerDevicePostureProvider(this, lifecycle, lifecycleScope)
        setContent {
            val devicePosture by postureProvider.posture.collectAsState()
            val preferences by controller.readerPreferences.collectAsState()
            SideEffect {
                screenTimeoutMode = preferences.screenTimeoutMode
                updateScreenAwake()
            }
            val darkTheme = when (preferences.theme) {
                ReaderTheme.LIGHT -> false
                ReaderTheme.DARK -> true
                ReaderTheme.SYSTEM -> isSystemInDarkTheme()
            }
            ListaiTheme(darkTheme = darkTheme) {
                ListaiApp(devicePosture = devicePosture, darkTheme = darkTheme,
                    onReaderVisibleChange = { visible ->
                        readerVisible = visible
                        updateScreenAwake()
                    })
            }
        }
        if (savedInstanceState == null) consumeBookIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle this delivery without replacing the Activity's launch identity.
        // Recreation restores the selected book through the controller, while retaining
        // the original launcher intent also keeps lifecycle observers attached.
        consumeBookIntent(intent)
    }

    private fun consumeBookIntent(incoming: Intent) {
        val uris = when (incoming.action) {
            Intent.ACTION_VIEW -> listOfNotNull(incoming.data)
            Intent.ACTION_SEND -> listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> androidx.core.content.IntentCompat.getParcelableArrayListExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) (application as ListaiApplication).controller.importBooks(uris, incoming.flags)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        updateScreenAwake()
        applyFullscreen()
    }

    override fun onPause() {
        foreground = false
        updateScreenAwake()
        (application as ListaiApplication).controller.saveOnBackground()
        super.onPause()
    }

    override fun onPostResume() {
        super.onPostResume()
        reassertFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) reassertFullscreen()
    }

    private fun updateScreenAwake() {
        val keepAwake = foreground && readerVisible && screenTimeoutMode == ReaderScreenTimeoutMode.ALWAYS_ON
        val currentlyKeptAwake = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        if (keepAwake == currentlyKeptAwake) return
        if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun reassertFullscreen() {
        applyFullscreen()
        window.decorView.post { applyFullscreen() }
    }

    private fun applyFullscreen() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

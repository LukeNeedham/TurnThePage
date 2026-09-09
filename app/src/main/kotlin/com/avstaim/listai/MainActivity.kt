package com.avstaim.listai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.avstaim.listai.core.adaptive.WindowManagerDevicePostureProvider
import com.avstaim.listai.core.designsystem.ListaiTheme

class MainActivity : ComponentActivity() {
    private var readerFullscreenRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readerFullscreenRequested =
            savedInstanceState?.getBoolean(READER_FULLSCREEN_REQUESTED_KEY) ?: false
        enableEdgeToEdge()
        val postureProvider = WindowManagerDevicePostureProvider(
            activity = this,
            lifecycle = lifecycle,
            scope = lifecycleScope,
        )
        setContent {
            val devicePosture by postureProvider.posture.collectAsState()
            var readerFullscreen by rememberSaveable {
                mutableStateOf(readerFullscreenRequested)
            }

            SideEffect {
                requestReaderFullscreen(readerFullscreen)
            }

            ListaiTheme {
                ListaiApp(
                    devicePosture = devicePosture,
                    readerFullscreen = readerFullscreen,
                    onReaderFullscreenChange = { fullscreen ->
                        // Apply the window change from the input event immediately. SideEffect
                        // below remains the source-of-truth sync after recreation/restoration.
                        requestReaderFullscreen(fullscreen)
                        readerFullscreen = fullscreen
                    },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(READER_FULLSCREEN_REQUESTED_KEY, readerFullscreenRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onPostResume() {
        super.onPostResume()
        applySystemBarsVisibility(fullscreen = readerFullscreenRequested)
        reapplySystemBarsVisibilityOnNextFrame()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Losing focus must not actively show the bars: dialogs, permission surfaces and
        // system transitions can cause a brief focus loss. Re-assert the requested state
        // only after this window owns the insets again.
        if (hasFocus) {
            applySystemBarsVisibility(fullscreen = readerFullscreenRequested)
            reapplySystemBarsVisibilityOnNextFrame()
        }
    }

    private fun requestReaderFullscreen(fullscreen: Boolean) {
        readerFullscreenRequested = fullscreen
        // WindowInsetsControllerCompat keeps a hide request pending when this window does
        // not currently control an inset, then applies it when control returns.
        applySystemBarsVisibility(fullscreen = fullscreen)
        reapplySystemBarsVisibilityOnNextFrame()
    }

    private fun reapplySystemBarsVisibilityOnNextFrame() {
        window.decorView.post {
            applySystemBarsVisibility(fullscreen = readerFullscreenRequested)
        }
    }

    private fun applySystemBarsVisibility(fullscreen: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) {
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private const val READER_FULLSCREEN_REQUESTED_KEY = "reader_fullscreen_requested"

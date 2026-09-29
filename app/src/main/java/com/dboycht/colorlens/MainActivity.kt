package com.dboycht.colorlens

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.dboycht.colorlens.ui.AppGraph
import com.dboycht.colorlens.ui.CameraScreen
import com.dboycht.colorlens.ui.ColorLensTheme
import com.dboycht.colorlens.ui.CompareScreen
import com.dboycht.colorlens.ui.PhotoStore
import com.dboycht.colorlens.ui.PickerScreen
import com.dboycht.colorlens.ui.SettingsScreen
import com.dboycht.colorlens.ui.SettingsStore
import com.dboycht.colorlens.ui.SimulateScreen
import com.dboycht.colorlens.ui.Speaker
import com.dboycht.colorlens.ui.testSpeechText

/**
 * 辨色助手 — a colour-reading assistant for people with colour-vision deficiency.
 *
 * The whole app is five screens behind one bottom bar, in the order the user
 * actually works: take a photo, point at a spot, ask what it is, compare two
 * spots, see it through the other pair of eyes. There is no navigation library:
 * five destinations with no deep links or back-stack requirements do not need
 * one, and the saved tab index survives rotation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Installed before `setContent` so the composition can never observe a
        // half-built graph. Nothing here holds a Context: SettingsStore keeps the
        // SharedPreferences and Speaker keeps the application context.
        AppGraph.install(
            settings = SettingsStore(this),
            photos = PhotoStore(),
            speaker = Speaker(this),
        )

        setContent {
            val settings = AppGraph.settings.value
            ColorLensTheme(
                largeText = settings.largeText,
                highContrast = settings.highContrast,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    App()
                }
            }
        }
    }

    override fun onDestroy() {
        // The TTS engine outlives the activity otherwise, and a leaked engine
        // keeps talking after the app is gone.
        if (AppGraph.isInstalled) AppGraph.speaker.shutdown()
        super.onDestroy()
    }
}

/**
 * The five destinations.
 *
 * Icons are emoji glyphs rather than `material-icons-extended`: that artefact
 * alone adds roughly 40 MB to an APK whose entire job is to show one colour, and
 * every pixel of screen space here is better spent on the photo. The glyph is
 * decorative — the label underneath carries the meaning for screen readers.
 */
private enum class Tab(val label: String, val glyph: String) {
    CAMERA("拍照", "📷"),
    PICK("取色", "🎯"),
    COMPARE("对比", "⚖"),
    SIMULATE("模拟", "👁"),
    SETTINGS("设置", "⚙"),
}

@Composable
private fun App() {
    val settingsStore = AppGraph.settings
    val settings = settingsStore.value
    val photos = AppGraph.photos
    val speaker = AppGraph.speaker
    val context = LocalContext.current

    var tab by rememberSaveable { mutableStateOf(Tab.CAMERA) }

    // Fullscreen picking hides this bar (and the system bars) so the photo can use
    // the whole screen. It lives here rather than inside PickerScreen because the
    // bar it hides is this Scaffold's, and it is cleared whenever the user leaves
    // the picker — a hidden navigation bar on any other page would be a trap.
    var pickerFullscreen by rememberSaveable { mutableStateOf(false) }

    // Every way out of the picker restores the bar, not just the bar's own buttons:
    // 对比 and 换照片 also navigate away, and a hidden navigation bar on any other
    // page is a dead end.
    LaunchedEffect(tab) { if (tab != Tab.PICK) pickerFullscreen = false }

    fun speak(text: String) = speaker.speakIfEnabled(text, settings.speechEnabled)

    Scaffold(
        bottomBar = {
            if (!pickerFullscreen) {
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = {
                                if (entry != Tab.PICK) pickerFullscreen = false
                                tab = entry
                            },
                            icon = { Text(entry.glyph, fontSize = 18.sp) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                Tab.CAMERA -> CameraScreen(
                    onPhoto = { bitmap, source ->
                        photos.setPhoto(bitmap, source)
                        tab = Tab.PICK
                    },
                )

                Tab.PICK -> PickerScreen(
                    store = photos,
                    settings = settings,
                    fullscreen = pickerFullscreen,
                    onFullscreenChange = { pickerFullscreen = it },
                    onSpeak = ::speak,
                    onOpenCompare = { tab = Tab.COMPARE },
                    onNeedPhoto = { tab = Tab.CAMERA },
                    onRetake = { tab = Tab.CAMERA },
                    // Speech that cannot work must not look like it did: the button
                    // goes disabled and this reason appears under the photo.
                    speechNote = speaker.unavailableReason,
                )

                Tab.COMPARE -> CompareScreen(
                    store = photos,
                    settings = settings,
                    onSpeak = ::speak,
                    onPickMarker = { marker ->
                        photos.activeMarker = marker
                        tab = Tab.PICK
                    },
                    onNeedPhoto = { tab = Tab.CAMERA },
                )

                Tab.SIMULATE -> SimulateScreen(
                    store = photos,
                    settings = settings,
                    onNeedPhoto = { tab = Tab.CAMERA },
                    onSeverityChange = { value -> settingsStore.update { it.copy(severity = value) } },
                )

                Tab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    speaker = speaker,
                    onChange = { transform -> settingsStore.update(transform) },
                    // A test speaks regardless of 播报颜色: it exists to prove the phone
                    // can speak at all, and silence would be indistinguishable from the
                    // switch doing its job.
                    onTestSpeech = { speaker.speak(testSpeechText(settings)) },
                    onInstallVoice = {
                        // Not every device can service this intent. Swallowing the
                        // failure is acceptable: the settings card already told the
                        // user their phone has no Chinese voice, so nothing is
                        // silently broken — there is just nothing more to open.
                        try {
                            context.startActivity(speaker.installVoiceIntent())
                        } catch (_: ActivityNotFoundException) {
                            // No TTS installer on this device.
                        }
                    },
                )
            }
        }
    }
}

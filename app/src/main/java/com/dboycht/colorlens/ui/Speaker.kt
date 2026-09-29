package com.dboycht.colorlens.ui

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dboycht.colorlens.BuildConfig
import com.dboycht.colorlens.color.ColorNamer
import com.dboycht.colorlens.color.Rgb8
import java.util.Locale

/**
 * Text-to-speech for the colour reading.
 *
 * Speech is not a nicety here: the app is used while looking at a physical object,
 * often one-handed, so hearing the answer matters more than reading it. Three
 * details decide whether it is usable in practice:
 *
 * - **`onInit` can arrive before the constructor returns.** This is not theoretical:
 *   on the reference phone (OPPO PKS110, ColorOS, 2026-09-29) it does, and code that
 *   reads the `engine` field from inside the listener returns early, never sets
 *   `ready`, and leaves every 朗读 tap queued forever — the symptom the user
 *   reported. The status is therefore remembered and the setup runs again once the
 *   constructor has returned ([earlyStatus]).
 * - **A Chinese voice may not be installed.** `setLanguage` reports
 *   [TextToSpeech.LANG_MISSING_DATA] / [TextToSpeech.LANG_NOT_SUPPORTED]; the UI
 *   turns that into a warning instead of silently doing nothing.
 * - **The engine may be missing or refused.** Binding to the system default can fail
 *   outright (the reference phone ships an accessibility TTS engine but leaves
 *   `tts_default_synth` unset), so the installed engines are tried explicitly, and if
 *   none of them works the UI says so rather than playing dead.
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null

    /** Text requested before the engine was ready, flushed by [onInit]. */
    private var pending: String? = null

    /**
     * Set when `onInit` fires before the constructor has assigned [engine]. The init
     * block drains it once the constructor returns, so setup happens exactly once
     * whatever the order.
     */
    private var earlyStatus: Int? = null

    /** True once the engine is up; before that, requests are queued. */
    var ready: Boolean by mutableStateOf(false)
        private set

    /** False when the device has no usable Chinese voice. Speech still works. */
    var chineseAvailable: Boolean by mutableStateOf(true)
        private set

    /** True while an utterance is in flight, for the button state. */
    var speaking: Boolean by mutableStateOf(false)
        private set

    /**
     * Non-null when speech cannot work at all — no engine, or every installed engine
     * refused to start. The UI disables 朗读 and shows this instead of doing nothing.
     */
    var unavailableReason: String? by mutableStateOf(null)
        private set

    init {
        startEngine(requested = null)
    }

    /**
     * Builds a [TextToSpeech] bound to [requested] (null = the system default) and
     * handles the init callback in either order.
     */
    private fun startEngine(requested: String?) {
        val listener = TextToSpeech.OnInitListener { status ->
            if (engine == null) {
                // Fired from inside the constructor: remember it and let init() finish.
                diag("onInit($status) arrived before the constructor returned (engine=$requested)")
                earlyStatus = status
            } else {
                onInit(status, requested)
            }
        }
        engine = if (requested == null) {
            TextToSpeech(appContext, listener)
        } else {
            TextToSpeech(appContext, listener, requested)
        }
        earlyStatus?.let { status ->
            earlyStatus = null
            onInit(status, requested)
        }
    }

    private fun onInit(status: Int, requested: String?) {
        diag("onInit status=$status requested=${requested ?: "<default>"}")
        if (status != TextToSpeech.SUCCESS) {
            if (requested == null) {
                // The default engine refused (or there is none). Try the installed
                // engines one by one before giving up: on ColorOS the TTS engine is
                // present and enabled while `tts_default_synth` is still unset.
                val installed = installedEngines()
                diag("default engine failed, installed engines=$installed")
                installed.firstOrNull()?.let { fallback ->
                    startEngine(requested = fallback)
                    return
                }
            }
            ready = false
            chineseAvailable = false
            unavailableReason = "这台手机没有可用的语音引擎，朗读用不了"
            return
        }

        val tts = engine ?: return
        val result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        chineseAvailable = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!chineseAvailable) {
            // English is the fallback: better a wrong-accent reading of the
            // pinyin-less Chinese text than nothing at all. Android's TTS
            // reads Han characters with whichever voice is available.
            tts.setLanguage(Locale.getDefault())
        }
        tts.setSpeechRate(0.95f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
            }

            override fun onDone(utteranceId: String?) {
                speaking = false
            }

            @Deprecated("Required by the platform interface")
            override fun onError(utteranceId: String?) {
                speaking = false
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                speaking = false
                diag("utterance failed: $errorCode")
            }
        })
        ready = true
        unavailableReason = null
        diag("engine ready, chinese=$chineseAvailable, pending=${pending != null}")
        pending?.let { text ->
            pending = null
            speak(text)
        }
    }

    /** Packages that advertise a TTS service, most-preferred first. */
    private fun installedEngines(): List<String> =
        appContext.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()

    /** Speaks [text], interrupting anything already being said. */
    fun speak(text: String) {
        if (text.isBlank()) return
        val tts = engine
        if (tts == null || !ready) {
            diag("speak queued (ready=$ready engine=${tts != null}): $text")
            pending = text
            return
        }
        diag("speak: $text")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** Speaks [text] only if speech is enabled in settings. */
    fun speakIfEnabled(text: String, enabled: Boolean) {
        if (enabled) speak(text) else diag("speak skipped: speech is off in settings")
    }

    fun stop() {
        engine?.stop()
        speaking = false
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    /** Intent that opens the system screen for installing TTS voice data. */
    fun installVoiceIntent(): Intent =
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun diag(message: String) {
        // Debug builds only, like the camera diagnostics: the release APK must not
        // log anything the user reads or types.
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "ColorLensTts"
        const val UTTERANCE_ID = "colorlens.reading"
    }
}

/**
 * What the settings screen's 测试语音 button reads out.
 *
 * Deliberately not a hard-coded sentence: it runs the real namer over a fixed sample
 * colour and formats it through the same [spokenText] the picker uses, so the test
 * speaks exactly what the app would say for that colour — including the 简短/详细
 * choice and any "更像 X"/"介于 X 和 Y" wording. A hand-written sample would drift
 * away from the real phrasing and stop being a test of anything.
 *
 * Pure (no Android APIs), so the JVM tests can pin the sentences.
 */
fun testSpeechText(settings: AppSettings): String =
    ColorNamer.read(TEST_SPEECH_COLOUR).spokenText(settings)

/**
 * The sample: a warm mid brown, the kind of colour this app is pointed at (wood,
 * skin, food) and one that gets a modifier, so a 详细 test hears more than just a
 * name. Chosen by looking at the sentence it produces, not by picking pretty numbers.
 */
private val TEST_SPEECH_COLOUR = Rgb8(0x8A, 0x5A, 0x3B)


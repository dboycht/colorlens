package com.dboycht.colorlens.ui

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Text-to-speech for the colour reading.
 *
 * Speech is not a nicety here: the app is used while looking at a physical object,
 * often one-handed, so hearing the answer matters more than reading it. Two
 * details that decide whether it is usable in practice:
 *
 * - **A Chinese voice may not be installed.** `setLanguage` reports
 *   [TextToSpeech.LANG_MISSING_DATA] / [TextToSpeech.LANG_NOT_SUPPORTED]; the UI
 *   turns that into a button that opens the system TTS installer instead of
 *   silently doing nothing.
 * - **The engine is asynchronous.** The first `speak()` usually arrives before
 *   initialisation finishes, so the pending text is queued and flushed on ready
 *   (otherwise the very first tap — the one users judge the app by — is silent).
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var pending: String? = null

    /** True once the engine is up; before that, requests are queued. */
    var ready: Boolean by mutableStateOf(false)
        private set

    /** False when the device has no usable Chinese voice. */
    var chineseAvailable: Boolean by mutableStateOf(true)
        private set

    /** True while an utterance is in flight, for the button state. */
    var speaking: Boolean by mutableStateOf(false)
        private set

    init {
        engine = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ready = false
                chineseAvailable = false
                return@TextToSpeech
            }
            val tts = engine ?: return@TextToSpeech
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
                }
            })
            ready = true
            pending?.let { text ->
                pending = null
                speak(text)
            }
        }
    }

    /** Speaks [text], interrupting anything already being said. */
    fun speak(text: String) {
        if (text.isBlank()) return
        val tts = engine
        if (tts == null || !ready) {
            pending = text
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** Speaks [text] only if speech is enabled in settings. */
    fun speakIfEnabled(text: String, enabled: Boolean) {
        if (enabled) speak(text)
    }

    fun stop() {
        engine?.stop()
        speaking = false
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    /** Intent that opens the system screen for installing TTS voice data. */
    fun installVoiceIntent(): Intent =
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val UTTERANCE_ID = "colorlens.reading"
    }
}

package com.dboycht.colorlens.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Speech — the feature the user reported as "朗读功能没有用" (2026-09-29).
 *
 * ## What this is really guarding
 *
 * The reading was never spoken on the reference phone, and nothing in the app said
 * why: tapping 朗读 produced no sound, no message and no visual change, while the
 * 播报颜色 switch was on. The cause was a missing `<queries>` element in the
 * manifest. `TextToSpeech` resolves the default engine with a `queryIntentServices`
 * call made **in the app's own process**; on this phone `tts_default_synth` is unset,
 * so that lookup is the only path to an engine, and package visibility (API 30+,
 * `targetSdk 36`) hid every candidate. `onInit` then reported `ERROR`, `ready` stayed
 * false, and every tap was queued into a `pending` field that nothing ever drained.
 *
 * Measured A/B on one debug build, one variable (see `ERROR.md` §22):
 *
 * ```
 * without <queries>:  onInit status=-1   default engine failed, installed engines=[]
 * with    <queries>:  onInit status=0    engine ready, chinese=true
 * ```
 *
 * These tests pin the four things that failure taught, none of which is visible to a
 * pure-logic test:
 *
 * 1. the manifest asks to see TTS engines at all;
 * 2. an engine that refuses (or a stale default) is retried against the installed
 *    engines before speech is declared impossible;
 * 3. "impossible" reaches the user on the picker, not only a settings card that sits
 *    below the fold;
 * 4. that message costs the photo no height — the budget invariant of
 *    [PickerLayout] is easy to break by adding one more fixed row.
 *
 * The `onInit`-before-the-constructor race is pinned too: it really happens on this
 * device (the same log first line) and reading `engine` from inside the callback is
 * the natural way to write it.
 */
class SpeechTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val speaker = File("src/main/java/com/dboycht/colorlens/ui/Speaker.kt").readText()
    private val picker = File("src/main/java/com/dboycht/colorlens/ui/PickerScreen.kt").readText()
    private val activity = File("src/main/java/com/dboycht/colorlens/MainActivity.kt").readText()

    @Test
    fun `the manifest declares the TTS service so the engine can be found`() {
        // Without this the engine list is empty on the reference phone and speech is
        // silently dead — the single line whose absence caused the reported bug.
        assertTrue("the manifest must declare a <queries> element", manifest.contains("<queries>"))
        assertTrue(
            "TTS engines are invisible without this action declaration",
            manifest.contains("android.intent.action.TTS_SERVICE"),
        )
        assertTrue(
            "the TTS action must be inside <queries>, not somewhere else",
            manifest.indexOf("<queries>") < manifest.indexOf("android.intent.action.TTS_SERVICE"),
        )
    }

    @Test
    fun `a refused default engine is retried against the installed engines`() {
        assertTrue(
            "the installed engines must be enumerated from the package manager",
            speaker.contains("queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)"),
        )
        assertTrue(
            "an engine that fails must be replaced by an explicit one",
            speaker.contains("startEngine(requested = fallback)"),
        )
        assertTrue(
            "only a total failure may set the unavailable reason",
            speaker.contains("installed.firstOrNull()?.let { fallback ->"),
        )
    }

    @Test
    fun `a device that cannot speak says so on the picker, not only in settings`() {
        assertTrue("the speaker must expose why it cannot speak", speaker.contains("var unavailableReason"))
        assertTrue(
            "the picker must receive that reason",
            activity.contains("speechNote = speaker.unavailableReason"),
        )
        assertTrue("the picker must accept it", picker.contains("speechNote: String? = null"))
        assertTrue(
            "an unusable 朗读 button must not look usable",
            picker.contains("enabled = speechNote == null"),
        )
        assertTrue(
            "the reason must be shown where the button is",
            picker.contains("speechNote?.let {"),
        )
    }

    @Test
    fun `the speech warning costs the photo no height`() {
        // ERROR.md §19: every fixed row is paid for by the description's budget, and
        // the photo's height must depend on the page and the reserve only. The note
        // therefore belongs inside the scrolling column — after the scroll modifier
        // is applied and before the card — never as a row of its own.
        val scroll = picker.indexOf("verticalScroll(rememberScrollState())")
        val note = picker.indexOf("speechNote?.let {")
        val card = picker.indexOf("ColorCard(reading = it, showHex = settings.showHex)")
        assertTrue("the scrolling column must exist", scroll >= 0)
        assertTrue("the note must live inside the scrolling column", note > scroll)
        assertTrue("the note must come before the card, at the top of that column", note < card)
    }

    @Test
    fun `an onInit callback that beats the constructor still ends up ready`() {
        // Measured: the callback fires before `engine` is assigned. Reading the field
        // from the callback is the obvious way to write this and the wrong one, so the
        // status is remembered and replayed once the constructor has returned.
        assertTrue(
            "the callback must not use the half-built speaker",
            speaker.contains("if (engine == null) {"),
        )
        assertTrue("the status must be remembered", speaker.contains("earlyStatus = status"))
        assertTrue("and replayed after construction", speaker.contains("earlyStatus = null"))
    }

    @Test
    fun `silent failure is impossible, so every outcome leaves a debug trace`() {
        // Three distinguishable states, so "no sound" can be told apart from "never
        // called" without guessing. Release builds log nothing (see the privacy note
        // in Speaker.kt).
        assertTrue("debug-only diagnostics", speaker.contains("if (BuildConfig.DEBUG)"))
        assertTrue("'was it called?'", speaker.contains("diag(\"speak: \$text\")"))
        assertTrue("'called too early to play?'", speaker.contains("\"speak queued"))
        assertTrue("'or switched off in settings?'", speaker.contains("\"speak skipped"))
        assertTrue("'and did an engine ever arrive?'", speaker.contains("onInit status="))
        assertTrue("'with a Chinese voice?'", speaker.contains("chinese=\$chineseAvailable"))
    }
}

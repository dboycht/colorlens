package com.dboycht.colorlens.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dboycht.colorlens.color.CvdType

/** How much text the app reads out. */
enum class SpeechDetail(val label: String) {
    BRIEF("简短"),
    DETAILED("详细"),
}

/**
 * How cautious the two-point verdict should be.
 *
 * Colour-vision deficiency varies a lot between people with the same diagnosis,
 * and the OKLab thresholds are population averages. This lets the user shift the
 * line: 严格 warns earlier (better for someone who is often caught out), 宽松
 * complains less.
 */
enum class CompareSensitivity(val label: String, val factor: Float, val hint: String) {
    RELAXED("宽松", 0.7f, "只有差别很小时才提醒"),
    NORMAL("普通", 1.0f, "按一般人的分辨力判断"),
    STRICT("严格", 1.5f, "差别稍小就提醒，更保险"),
}

/**
 * Everything the user can change. Persisted in `SharedPreferences`.
 *
 * Deliberately small: **no account, no network, no photo library**. The app never
 * stores the photos it analyses — see [PhotoStore] — so there is nothing here
 * about history except what is on screen right now.
 */
data class AppSettings(
    val cvdType: CvdType = CvdType.DEUTERANOMALY,
    /** Severity used for the 弱 types; 盲 types are always 1.0. */
    val severity: Float = CvdType.DEUTERANOMALY.severity,
    val speechEnabled: Boolean = true,
    val speechDetail: SpeechDetail = SpeechDetail.BRIEF,
    val autoSpeakOnPick: Boolean = true,
    val largeText: Boolean = false,
    val highContrast: Boolean = false,
    val showHex: Boolean = true,
    val sensitivity: CompareSensitivity = CompareSensitivity.NORMAL,
) {
    /** The severity actually used for simulation, given the selected type. */
    fun effectiveSeverity(): Float =
        if (cvdType.severity >= 1f) 1f else severity.coerceIn(0.1f, 1f)

    val sensitivityFactor: Float get() = sensitivity.factor
}

/**
 * Compose-observable settings store.
 *
 * A tiny hand-rolled store instead of a settings library: there are eleven
 * fields, they are all user-facing, and `SharedPreferences` is applied
 * synchronously, so a read on the composition thread is cheap and always fresh.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var value: AppSettings by mutableStateOf(load())
        private set

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(value)
        value = next
        save(next)
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            cvdType = CvdType.fromId(prefs.getString(KEY_TYPE, defaults.cvdType.name)),
            severity = prefs.getFloat(KEY_SEVERITY, defaults.severity),
            speechEnabled = prefs.getBoolean(KEY_SPEECH, defaults.speechEnabled),
            speechDetail = runCatching {
                SpeechDetail.valueOf(prefs.getString(KEY_SPEECH_DETAIL, defaults.speechDetail.name)!!)
            }.getOrDefault(defaults.speechDetail),
            autoSpeakOnPick = prefs.getBoolean(KEY_AUTO_SPEAK, defaults.autoSpeakOnPick),
            largeText = prefs.getBoolean(KEY_LARGE_TEXT, defaults.largeText),
            highContrast = prefs.getBoolean(KEY_HIGH_CONTRAST, defaults.highContrast),
            showHex = prefs.getBoolean(KEY_SHOW_HEX, defaults.showHex),
            sensitivity = runCatching {
                CompareSensitivity.valueOf(prefs.getString(KEY_SENSITIVITY, defaults.sensitivity.name)!!)
            }.getOrDefault(defaults.sensitivity),
        )
    }

    private fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_TYPE, settings.cvdType.name)
            .putFloat(KEY_SEVERITY, settings.severity)
            .putBoolean(KEY_SPEECH, settings.speechEnabled)
            .putString(KEY_SPEECH_DETAIL, settings.speechDetail.name)
            .putBoolean(KEY_AUTO_SPEAK, settings.autoSpeakOnPick)
            .putBoolean(KEY_LARGE_TEXT, settings.largeText)
            .putBoolean(KEY_HIGH_CONTRAST, settings.highContrast)
            .putBoolean(KEY_SHOW_HEX, settings.showHex)
            .putString(KEY_SENSITIVITY, settings.sensitivity.name)
            .apply()
    }

    private companion object {
        const val PREFS = "colorlens.settings"
        const val KEY_TYPE = "cvdType"
        const val KEY_SEVERITY = "severity"
        const val KEY_SPEECH = "speechEnabled"
        const val KEY_SPEECH_DETAIL = "speechDetail"
        const val KEY_AUTO_SPEAK = "autoSpeakOnPick"
        const val KEY_LARGE_TEXT = "largeText"
        const val KEY_HIGH_CONTRAST = "highContrast"
        const val KEY_SHOW_HEX = "showHex"
        const val KEY_SENSITIVITY = "comparisonSensitivity"
    }
}

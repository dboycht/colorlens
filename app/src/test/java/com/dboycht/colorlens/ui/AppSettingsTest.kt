package com.dboycht.colorlens.ui

import com.dboycht.colorlens.color.CvdType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that decide what every other screen computes.
 *
 * Both have bitten before in similar apps: a "程度" slider that silently does
 * nothing for the 全色盲 preset (because it is always 1.0), and a default that
 * does not survive the round trip through `SharedPreferences` names.
 */
class AppSettingsTest {

    @Test
    fun `severity only applies to the partial types`() {
        val settings = AppSettings(cvdType = CvdType.DEUTERANOMALY, severity = 0.45f)
        assertEquals(0.45f, settings.effectiveSeverity(), 0.0001f)

        // 色盲 presets are all-or-nothing whatever the slider says.
        assertEquals(
            1f,
            settings.copy(cvdType = CvdType.DEUTERANOPIA).effectiveSeverity(),
            0.0001f,
        )
        assertEquals(
            1f,
            settings.copy(cvdType = CvdType.PROTANOPIA).effectiveSeverity(),
            0.0001f,
        )
        assertEquals(
            1f,
            settings.copy(cvdType = CvdType.ACHROMATOPSIA).effectiveSeverity(),
            0.0001f,
        )
    }

    @Test
    fun `severity is clamped into the range the matrices cover`() {
        assertEquals(0.1f, AppSettings(severity = 0f).effectiveSeverity(), 0.0001f)
        assertEquals(1f, AppSettings(severity = 4f).effectiveSeverity(), 0.0001f)
    }

    @Test
    fun `normal vision behaves like severe-free settings`() {
        val settings = AppSettings(cvdType = CvdType.NORMAL)
        assertTrue(settings.cvdType.isNormal)
        // The default type is a partial deficiency, not 全色盲 and not normal:
        // the app is for people who have one.
        assertFalse(AppSettings().cvdType.isNormal)
        assertEquals(CvdType.DEUTERANOMALY, AppSettings().cvdType)
    }

    @Test
    fun `every type survives the round trip through its stored name`() {
        for (type in CvdType.entries) {
            assertEquals(type, CvdType.fromId(type.name))
        }
        // An unknown or missing id must fall back to the default, not crash.
        assertEquals(CvdType.DEUTERANOMALY, CvdType.fromId(null))
        assertEquals(CvdType.DEUTERANOMALY, CvdType.fromId("NOPE"))
    }

    @Test
    fun `sensitivity factors are ordered from lenient to cautious`() {
        assertTrue(CompareSensitivity.RELAXED.factor < CompareSensitivity.NORMAL.factor)
        assertTrue(CompareSensitivity.NORMAL.factor < CompareSensitivity.STRICT.factor)
        assertEquals(1f, CompareSensitivity.NORMAL.factor, 0.0001f)
    }
}

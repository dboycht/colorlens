package com.dboycht.colorlens.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.dboycht.colorlens.color.Rgb8

/**
 * Holds the photo currently being analysed, plus the two comparison markers.
 *
 * ## Why a singleton and why only one photo
 *
 * A captured photo is the app's entire working state, and rotating the phone must
 * not throw it away. The normal Android answer is `rememberSaveable` or a
 * ViewModel, but a Bitmap cannot go into a Bundle (too large) and a ViewModel
 * would be re-created on process death. A single process-wide holder with Compose
 * state gives the right behaviour for the smallest amount of machinery, and it
 * makes the privacy story trivially true: **one photo, in memory, never written
 * to disk, dropped when replaced**.
 *
 * The bitmap is scaled down on decode (see [BitmapTools]) to keep this well under
 * a few tens of megabytes.
 */
class PhotoStore {

    /** The photo being analysed, already downscaled, or null before the first shot. */
    var bitmap: Bitmap? by mutableStateOf(null)
        private set

    /** Where the photo came from, for the small caption above it. */
    var source: PhotoSource by mutableStateOf(PhotoSource.NONE)
        private set

    /** Marker A (the main sample) in **bitmap pixel** coordinates. */
    var markerA: Offset? by mutableStateOf(null)

    /** Marker B (the comparison sample) in **bitmap pixel** coordinates. */
    var markerB: Offset? by mutableStateOf(null)

    /** Which marker the crosshair is currently moving. */
    var activeMarker: Marker by mutableStateOf(Marker.A)

    val hasPhoto: Boolean get() = bitmap != null

    /** True once both markers exist and a comparison can be made. */
    val canCompare: Boolean get() = markerA != null && markerB != null

    fun setPhoto(bitmap: Bitmap, source: PhotoSource) {
        // Replace the previous bitmap eagerly: holding two full photos while the
        // garbage collector catches up is the easiest way to get killed on a
        // low-memory phone.
        this.bitmap?.takeIf { it !== bitmap }?.recycle()
        this.bitmap = bitmap
        this.source = source
        val centre = Offset(bitmap.width / 2f, bitmap.height / 2f)
        markerA = centre
        markerB = null
        activeMarker = Marker.A
    }

    fun clear() {
        bitmap?.recycle()
        bitmap = null
        source = PhotoSource.NONE
        markerA = null
        markerB = null
        activeMarker = Marker.A
    }

    /** Moves the active marker, clamped into the bitmap. */
    fun moveActiveMarker(x: Float, y: Float) {
        val photo = bitmap ?: return
        val clamped = Offset(
            x.coerceIn(0f, photo.width - 1f),
            y.coerceIn(0f, photo.height - 1f),
        )
        when (activeMarker) {
            Marker.A -> markerA = clamped
            Marker.B -> markerB = clamped
        }
    }

    /** Colour under a marker, or null when it is not placed yet. */
    fun colourAt(marker: Marker): Rgb8? {
        val photo = bitmap ?: return null
        val point = when (marker) {
            Marker.A -> markerA
            Marker.B -> markerB
        } ?: return null
        return BitmapTools.pixelAt(photo, point.x, point.y)
    }

    fun hasBothMarkers() = markerA != null && markerB != null

    enum class Marker(val label: String) {
        A("A"),
        B("B"),
    }

    enum class PhotoSource(val label: String) {
        NONE(""),
        CAMERA("刚拍摄的照片"),
        GALLERY("相册里的照片"),
    }
}

/**
 * The app's object graph. Deliberately a plain singleton rather than a DI
 * framework: two objects, both created in `MainActivity.onCreate` from an
 * application context, neither of which is a `Context` itself (so there is no
 * leak).
 */
object AppGraph {
    lateinit var settings: SettingsStore
        private set
    lateinit var photos: PhotoStore
        private set
    lateinit var speaker: Speaker
        private set

    /** True once [install] has run; lets `onDestroy` skip a half-built graph. */
    var isInstalled: Boolean = false
        private set

    fun install(settings: SettingsStore, photos: PhotoStore, speaker: Speaker) {
        this.settings = settings
        this.photos = photos
        this.speaker = speaker
        isInstalled = true
    }
}

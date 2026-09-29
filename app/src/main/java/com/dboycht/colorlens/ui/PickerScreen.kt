package com.dboycht.colorlens.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dboycht.colorlens.color.ColorNamer
import com.dboycht.colorlens.color.ColorReading
import com.dboycht.colorlens.color.WhiteBalance
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bitmap pixels fed to the magnifier. 48 px at ~7x is enough to see a texture. */
private const val LOUPE_CROP_PX = 48

/**
 * "点一下，看颜色" — the screen the app exists for.
 *
 * Interaction decisions worth knowing:
 *
 * - **The crosshair follows the finger while dragging and only speaks on release.**
 *   Speaking continuously would make the app unusable in public.
 * - **A magnifier loupe sits above the finger.** Without it the user's own finger
 *   covers the very spot being sampled, which is the single most common
 *   complaint about tap-to-pick colour apps.
 * - **The reading is a 3×3 average**, not one pixel: sensors are noisy, and the
 *   user means "the colour of that spot", not "the colour of that pixel".
 */
@Composable
fun PickerScreen(
    store: PhotoStore,
    settings: AppSettings,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onSpeak: (String) -> Unit,
    onOpenCompare: () -> Unit,
    onNeedPhoto: () -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
    speechNote: String? = null,
) {
    val bitmap = store.bitmap

    // A picker with no photo has nothing to show fullscreen and no chrome to tap, so
    // it always drops out of it. Measured on the device: entering fullscreen and then
    // changing the system font scale recreates the activity, loses the photo, and
    // left the navigation bar hidden on the empty state — with no way back.
    LaunchedEffect(bitmap) { if (bitmap == null) onFullscreenChange(false) }

    if (bitmap == null) {
        EmptyPhotoState(onNeedPhoto = onNeedPhoto, modifier = modifier)
        return
    }

    // Fullscreen hides the system bars as well as the app's own chrome. Both are
    // restored on the way out — and on dispose, so a recomposition that drops this
    // screen can never leave the phone without a status bar.
    val view = LocalView.current
    DisposableEffect(fullscreen) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Back leaves fullscreen before it leaves the app.
    BackHandler(enabled = fullscreen) { onFullscreenChange(false) }

    val activePoint = when (store.activeMarker) {
        PhotoStore.Marker.A -> store.markerA
        PhotoStore.Marker.B -> store.markerB
    }

    val reading: ColorReading? = remember(bitmap, activePoint) {
        activePoint?.let { ColorNamer.read(BitmapTools.averageAround(bitmap, it.x, it.y)) }
    }

    val scope = rememberCoroutineScope()
    var calibrating by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // The photo box's geometry, as reported by PhotoCanvas after layout. The zoom
    // buttons need it: zooming about the crosshair requires knowing where the
    // crosshair currently is on screen.
    var geometry by remember { mutableStateOf<Pair<ImageMapping, IntSize>?>(null) }
    val density = LocalDensity.current

    /**
     * The tapped colour becomes the white reference, and the photo is replaced by
     * a corrected copy so that every screen (pick / compare / simulate) agrees on
     * what the colours are. Runs off the main thread: the correction touches the
     * full-resolution bitmap.
     */
    fun calibrateAt(point: Offset) {
        val photo = store.bitmap ?: return
        val reference = BitmapTools.averageAround(photo, point.x, point.y, radius = 3)
        when (val evaluation = WhiteBalance.evaluate(reference)) {
            is WhiteBalance.Evaluation.Unusable -> notice = evaluation.reason
            is WhiteBalance.Evaluation.Usable -> {
                calibrating = false
                if (evaluation.gains.isIdentity) {
                    notice = "这个点本来就接近纯白（${reference.toHex()}），照片不需要校正。"
                    return
                }
                scope.launch {
                    val corrected = withContext(Dispatchers.Default) {
                        BitmapTools.applyGains(photo, evaluation.gains)
                    }
                    store.setCalibrated(corrected, reference)
                    notice = "已按 ${reference.toHex()} 校准：红 ×%.2f 绿 ×%.2f 蓝 ×%.2f"
                        .format(evaluation.gains.r, evaluation.gains.g, evaluation.gains.b)
                }
            }
        }
    }

    /**
     * ## Why this screen is measured instead of weighted
     *
     * The photo used to be `weight(1f)`: "whatever the text does not need". A
     * description that wrapped onto a second line therefore took a line of height
     * away from the photo, and the photo visibly rescaled while the user was moving
     * the crosshair — the exact complaint that produced this layout (2026-09-28).
     *
     * Now the photo box is sized from the page and the photo's own aspect ratio
     * ([PickerLayout.photoBox]), and the reading area below is given a fixed share
     * that always reserves the worst case: short text leaves slack, long text
     * scrolls inside its own box, and the photo cannot move for either reason.
     */
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val inset = 12.dp
        // Fullscreen gives the photo the whole width; the rows that remain keep the
        // page inset so buttons do not touch the screen edge.
        val pageWidth = if (fullscreen) maxWidth else maxWidth - inset * 2
        val pageHeight = maxHeight - 8.dp * 2
        val reserve = with(density) {
            if (fullscreen) {
                PickerLayout.fullscreenReserve(fontScale = density.fontScale)
            } else {
                PickerLayout.reserve(
                    bodyLine = MaterialTheme.typography.bodyLarge.lineHeight.toDp(),
                    fontScale = density.fontScale,
                )
            }
        }
        val photoBox = PickerLayout.photoBox(
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            photoAspect = bitmap.width / bitmap.height.toFloat(),
            reserve = reserve,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(PickerLayout.ROW_GAP),
        ) {
            Chrome(inset) {
                MarkerSwitcher(store = store, onSwitch = { marker ->
                    store.activeMarker = marker
                    if (marker == PhotoStore.Marker.B && store.markerB == null) {
                        // Give B a sensible starting point instead of leaving it missing.
                        store.markerB = store.markerA
                    }
                })
            }

            PhotoCanvas(
                store = store,
                modifier = if (fullscreen) {
                    Modifier
                        .fillMaxWidth()
                        .height(photoBox.height)
                } else {
                    Modifier
                        .width(photoBox.width)
                        .height(photoBox.height)
                        .align(Alignment.CenterHorizontally)
                },
                calibrating = calibrating,
                hint = if (calibrating) "点一下画面里的白纸或浅灰色物体" else null,
                onPick = { point ->
                    store.moveActiveMarker(point.x, point.y)
                },
                onCalibrate = ::calibrateAt,
                onPickFinished = {
                    if (settings.autoSpeakOnPick && settings.speechEnabled) {
                        reading?.let { onSpeak(it.spokenText(settings)) }
                    }
                },
                onGeometryChange = { mapping, size -> geometry = mapping to size },
            )

            Chrome(inset) {
                ZoomRow(
                    zoom = store.zoom,
                    // Disabled until the photo has been laid out: without its geometry
                    // there is nothing to zoom about, and a wrong centroid is worse
                    // than a button that visibly does nothing for one frame.
                    ready = geometry != null,
                    onZoom = { factor ->
                        geometry?.let { (mapping, size) ->
                            val anchor = when (store.activeMarker) {
                                PhotoStore.Marker.A -> store.markerA
                                PhotoStore.Marker.B -> store.markerB
                            }
                            store.zoom = store.zoom.zoomedBy(factor, anchor, mapping, size)
                        }
                    },
                    onReset = { store.zoom = PhotoZoom.NONE },
                    onFullscreen = if (fullscreen) null else ({ onFullscreenChange(true) }),
                )
            }

            if (!fullscreen) {
                // The white-balance row sits with the other controls, *outside* the
                // scrolling region below. It holds a button, and a button must never be
                // pushed below the fold of something that can scroll — measured on the
                // device on 2026-09-28, it was: its bounds came back clipped at the
                // region's bottom edge, leaving it untappable without a scroll.
                Chrome(inset) {
                    CalibrationRow(
                        store = store,
                        calibrating = calibrating,
                        notice = notice,
                        onStart = {
                            calibrating = true
                            notice = "点一下画面里的白纸或浅灰色物体"
                        },
                        onCancel = {
                            calibrating = false
                            notice = null
                        },
                        onClear = {
                            store.clearCalibration()
                            calibrating = false
                            notice = "已取消校准，照片恢复原样。"
                        },
                    )
                }

                // Scrollable on purpose, and holding reading matter only: the card keeps
                // a fixed share of the screen (so the photo above never moves), and
                // anything that does not fit — a three-line description, the 大字号
                // setting — scrolls here instead of pushing the photo around.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = inset),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(PickerLayout.ROW_GAP),
                    ) {
                        // Speech that cannot work says so instead of playing dead. This
                        // lives inside the scrolling area, not as its own fixed row:
                        // every fixed row here would come straight out of the
                        // description's budget and shrink the card.
                        speechNote?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        reading?.let {
                            ColorCard(reading = it, showHex = settings.showHex)
                        }
                    }
                }
            } else {
                // Fullscreen trades the reading card for a compact bar: the picked
                // colour as a chip, its name, its numbers. The description is one 朗读
                // (which reads it out) or one tap away, and the 64 dp it would cost is
                // 64 dp of photo — on the reference phone that is exactly the
                // difference between a 1017 px wide photo and a 1080 px one.
                Chrome(inset) {
                    reading?.let { FullscreenReading(reading = it, showHex = settings.showHex) }
                }
            }

            Chrome(inset) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { reading?.let { onSpeak(it.spokenText(settings)) } },
                        enabled = speechNote == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("朗读")
                    }
                    OutlinedButton(
                        onClick = {
                            // Freeze the current reading as B and hand the user straight to
                            // the comparison, which is the reason they tapped twice.
                            val point = store.markerA
                            if (point != null) {
                                store.markerB = point
                                store.activeMarker = PhotoStore.Marker.B
                                onOpenCompare()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("对比")
                    }
                    if (fullscreen) {
                        TextButton(onClick = { onFullscreenChange(false) }) { Text("退出全屏") }
                    } else {
                        TextButton(onClick = onRetake) { Text("换照片") }
                    }
                }
            }
        }
    }
}

/**
 * Keeps the page's horizontal inset on a row of chrome while letting the photo run
 * edge to edge in fullscreen mode. In the normal layout the inset is the page's, so
 * this is only ever a wrapper.
 */
@Composable
private fun Chrome(inset: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = inset)) { content() }
}

/**
 * The whole reading, in [PickerLayout.FULLSCREEN_READING] dp: the picked colour, its
 * name, and its numbers. Everything here is text on the surface rather than text on
 * the colour, so no contrast juggling is needed — and none of it covers the photo.
 */
@Composable
private fun FullscreenReading(reading: ColorReading, showHex: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(reading.rgb.toComposeColor())
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        RoundedCornerShape(10.dp),
                    ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reading.primaryName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (showHex) "${reading.hex} · ${reading.rgb.toRgbText()}" else reading.hex,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 放大 / 缩小 / 复位 — the pinch gesture is a shortcut for people who know it, but
 * a colour-picking app cannot make "put two fingers on the screen and pinch" the
 * only way to look closer, so the same thing is a pair of buttons. Zooming is
 * anchored on the crosshair, so the spot being sampled stays put while it grows.
 */
@Composable
private fun ZoomRow(
    zoom: PhotoZoom,
    ready: Boolean,
    onZoom: (Float) -> Unit,
    onReset: () -> Unit,
    /** Null while already fullscreen: the way out lives on the action row. */
    onFullscreen: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { onZoom(1f / ZOOM_STEP) }, enabled = ready && zoom.isZoomed) {
            Text("缩小")
        }
        Text(
            text = "×%.1f".format(zoom.scale),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onZoom(ZOOM_STEP) }, enabled = ready && zoom.scale < PhotoZoom.MAX_SCALE) {
            Text("放大")
        }
        Spacer(modifier = Modifier.weight(1f))
        if (onFullscreen != null) {
            TextButton(onClick = onFullscreen) { Text("全屏") }
        }
        TextButton(onClick = onReset, enabled = ready && zoom.isZoomed) {
            Text("复位")
        }
    }
}

/**
 * White-balance control. Deliberately quiet: one small button when unused, and
 * one line of feedback, because most photos do not need it.
 */
@Composable
private fun CalibrationRow(
    store: PhotoStore,
    calibrating: Boolean,
    notice: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                calibrating -> {
                    Text(
                        text = "正在校准",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = onCancel) { Text("取消") }
                }

                store.isCalibrated -> {
                    Text(
                        text = "已按白纸校准",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = onClear) { Text("取消校准") }
                }

                else -> {
                    TextButton(onClick = onStart) { Text("白纸校准") }
                    Text(
                        text = "偏黄偏蓝时，先点一下白纸再取色",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MarkerSwitcher(
    store: PhotoStore,
    onSwitch: (PhotoStore.Marker) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "正在取色：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (marker in PhotoStore.Marker.entries) {
            val placed = when (marker) {
                PhotoStore.Marker.A -> store.markerA != null
                PhotoStore.Marker.B -> store.markerB != null
            }
            FilterChip(
                selected = store.activeMarker == marker,
                onClick = { onSwitch(marker) },
                label = { Text(if (placed) "${marker.label} 已取" else "${marker.label} 未取") },
            )
        }
        Text(
            text = store.source.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The photo with its markers, the loupe, and all the pointer handling.
 *
 * Kept in its own composable so that dragging only recomposes this part, not the
 * colour card's layout.
 *
 * ## Gestures (one pipeline, because they have to agree)
 *
 * One finger moves the crosshair, two fingers pinch to zoom and drag to pan. They
 * live in a single `awaitEachGesture` loop rather than two `pointerInput` modifiers:
 * with separate detectors the second finger of a pinch also feeds the drag detector,
 * and the crosshair would crawl across the photo every time the user zoomed.
 * After a pinch has started, the rest of that gesture is treated as pure
 * zoom/pan — lifting one finger must not suddenly re-aim the crosshair.
 */
@Composable
private fun PhotoCanvas(
    store: PhotoStore,
    modifier: Modifier = Modifier,
    calibrating: Boolean = false,
    hint: String? = null,
    onPick: (Offset) -> Unit,
    onCalibrate: (Offset) -> Unit = {},
    onPickFinished: () -> Unit,
    onGeometryChange: (ImageMapping, IntSize) -> Unit = { _, _ -> },
) {
    val bitmap = store.bitmap ?: return
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val mapping = remember(containerSize, bitmap) {
        ImageMapping(containerSize, bitmap.width, bitmap.height)
    }
    val zoom = store.zoom
    val density = LocalDensity.current

    // The gesture loop must not be torn down by the very state it writes, so it
    // reads the current values through refs instead of taking them as keys: a
    // `pointerInput(zoom)` would cancel the pinch on the first zoom change.
    val zoomRef = rememberUpdatedState(zoom)
    val mappingRef = rememberUpdatedState(mapping)

    LaunchedEffect(mapping, containerSize) {
        if (mapping.isUsable) onGeometryChange(mapping, containerSize)
    }
    val loupeSizePx = with(density) { 120.dp.toPx() }
    val loupeCrop = remember(bitmap, store.markerA, store.markerB, store.activeMarker) {
        val point = when (store.activeMarker) {
            PhotoStore.Marker.A -> store.markerA
            PhotoStore.Marker.B -> store.markerB
        } ?: return@remember null
        BitmapTools.cropAround(bitmap, point.x, point.y, LOUPE_CROP_PX)
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .background(Color(0xFF05070A), RoundedCornerShape(16.dp))
            .onSizeChanged { containerSize = it }
            .pointerInput(bitmap, calibrating) {
                // While calibrating, dragging is disabled on purpose: the tap is
                // about to *mean* something else, and a stray drag must not move
                // the marker out from under the reading the user is looking at.
                if (calibrating) {
                    detectTapGestures { position ->
                        mappingRef.value.toBitmap(position, zoomRef.value)?.let(onCalibrate)
                    }
                    return@pointerInput
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // `live` rather than `zoomRef.value`: several gesture events can
                    // arrive before the recomposition that would refresh the ref, and
                    // compounding against a stale scale drifts badly during a pinch.
                    var live = zoomRef.value
                    mappingRef.value.toBitmap(down.position, live)?.let(onPick)
                    var pinched = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            pinched = true
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (centroid.isSpecified && (zoomChange != 1f || panChange != Offset.Zero)) {
                                live = live.zoomedBy(zoomChange, centroid).pannedBy(panChange)
                                    .clampedTo(mappingRef.value, size)
                                store.zoom = live
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (!pinched) {
                            val change = event.changes.firstOrNull { it.pressed } ?: break
                            if (change.positionChanged()) {
                                change.consume()
                                mappingRef.value.toBitmap(change.position, live)?.let(onPick)
                            }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                    onPickFinished()
                }
            },
    ) {
        Image(
            bitmap = image,
            contentDescription = "待取色的照片",
            modifier = Modifier
                .fillMaxSize()
                // The same `f * scale + offset` convention that ImageMapping uses to
                // hit-test, so what the user sees and what gets sampled cannot drift
                // apart — the origin is the top-left corner, not the centre.
                .graphicsLayer(
                    scaleX = zoom.scale,
                    scaleY = zoom.scale,
                    translationX = zoom.offset.x,
                    translationY = zoom.offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
            contentScale = ContentScale.Fit,
        )

        hint?.let {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
            ) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            fun drawMarker(point: Offset, colour: Color, filled: Boolean) {
                // The crosshair is drawn at the *zoomed* position and keeps its screen
                // size: a marker that grew with the photo would cover the very pixels
                // the user zoomed in to look at.
                val centre = mapping.toView(point, zoom)
                if (filled) {
                    drawCircle(color = colour, radius = 9f, center = centre)
                }
                drawCircle(color = colour, radius = 22f, center = centre, style = Stroke(width = 2.5f))
                drawLine(
                    color = colour,
                    start = Offset(centre.x - 30f, centre.y),
                    end = Offset(centre.x + 30f, centre.y),
                    strokeWidth = 1.5f,
                )
                drawLine(
                    color = colour,
                    start = Offset(centre.x, centre.y - 30f),
                    end = Offset(centre.x, centre.y + 30f),
                    strokeWidth = 1.5f,
                )
            }

            store.markerB?.let { drawMarker(it, Color(0xFFFFD166), store.activeMarker == PhotoStore.Marker.B) }
            store.markerA?.let { drawMarker(it, Color(0xFF53E1C0), store.activeMarker == PhotoStore.Marker.A) }
        }

        val activePoint = when (store.activeMarker) {
            PhotoStore.Marker.A -> store.markerA
            PhotoStore.Marker.B -> store.markerB
        }
        if (activePoint != null && mapping.isUsable && loupeCrop != null) {
            val viewPoint = mapping.toView(activePoint, zoom)
            val marginPx = with(density) { 12.dp.toPx() }
            val above = viewPoint.y - loupeSizePx - marginPx >= 0f
            val rawY = if (above) viewPoint.y - loupeSizePx - marginPx else viewPoint.y + marginPx
            val x = (viewPoint.x - loupeSizePx / 2f)
                .coerceIn(0f, (containerSize.width - loupeSizePx).coerceAtLeast(0f))
            val y = rawY.coerceIn(0f, (containerSize.height - loupeSizePx).coerceAtLeast(0f))

            Box(
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .border(2.dp, Color(0xFFFFD166), CircleShape),
            ) {
                Image(
                    bitmap = remember(loupeCrop) { loupeCrop.asImageBitmap() },
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val px = size.width / LOUPE_CROP_PX
                    drawRect(
                        color = Color(0xFFFFD166),
                        topLeft = Offset(centre.x - px / 2f, centre.y - px / 2f),
                        size = Size(px, px),
                        style = Stroke(width = 1.5f),
                    )
                    drawLine(Color.White, Offset(centre.x - 10f, centre.y), Offset(centre.x + 10f, centre.y), 1f)
                    drawLine(Color.White, Offset(centre.x, centre.y - 10f), Offset(centre.x, centre.y + 10f), 1f)
                }
            }
        }

        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
        ) {
            Text(
                text = "拖动或点一下照片选位置 · 两指捏合放大",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EmptyPhotoState(onNeedPhoto: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有照片", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "拍一张或者从相册里选一张，然后点你想问的位置。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onNeedPhoto) { Text("去拍照 / 选照片") }
    }
}

/** Speech text honouring the 详细/简略 setting. */
fun ColorReading.spokenText(settings: AppSettings): String = when (settings.speechDetail) {
    SpeechDetail.BRIEF -> speakText
    SpeechDetail.DETAILED -> speakTextDetailed
}

/** Small helper used by the magnifier's geometry in tests and previews. */
internal fun loupeCropPx(): Int = LOUPE_CROP_PX

@Composable
internal fun ThinDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    )
}

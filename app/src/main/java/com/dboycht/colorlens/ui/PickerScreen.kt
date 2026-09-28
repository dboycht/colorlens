package com.dboycht.colorlens.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
    onSpeak: (String) -> Unit,
    onOpenCompare: () -> Unit,
    onNeedPhoto: () -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = store.bitmap
    if (bitmap == null) {
        EmptyPhotoState(onNeedPhoto = onNeedPhoto, modifier = modifier)
        return
    }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MarkerSwitcher(store = store, onSwitch = { marker ->
            store.activeMarker = marker
            if (marker == PhotoStore.Marker.B && store.markerB == null) {
                // Give B a sensible starting point instead of leaving it missing.
                store.markerB = store.markerA
            }
        })

        PhotoCanvas(
            store = store,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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
        )

        reading?.let {
            ColorCard(reading = it, showHex = settings.showHex)
        }

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { reading?.let { onSpeak(it.spokenText(settings)) } },
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
            TextButton(onClick = onRetake) { Text("换照片") }
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
) {
    val bitmap = store.bitmap ?: return
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val mapping = remember(containerSize, bitmap) {
        ImageMapping(containerSize, bitmap.width, bitmap.height)
    }
    val density = LocalDensity.current
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
            .pointerInput(mapping, calibrating) {
                // While calibrating, dragging is disabled on purpose: the tap is
                // about to *mean* something else, and a stray drag must not move
                // the marker out from under the reading the user is looking at.
                if (calibrating) return@pointerInput
                detectDragGestures(
                    onDragStart = { position ->
                        mapping.toBitmap(position)?.let(onPick)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        mapping.toBitmap(change.position)?.let(onPick)
                    },
                    onDragEnd = onPickFinished,
                    onDragCancel = onPickFinished,
                )
            }
            .pointerInput(mapping, calibrating) {
                detectTapGestures { position ->
                    mapping.toBitmap(position)?.let { point ->
                        if (calibrating) {
                            onCalibrate(point)
                        } else {
                            onPick(point)
                            onPickFinished()
                        }
                    }
                }
            },
    ) {
        Image(
            bitmap = image,
            contentDescription = "待取色的照片",
            modifier = Modifier.fillMaxSize(),
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
                val centre = mapping.toView(point)
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
            val viewPoint = mapping.toView(activePoint)
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
                text = "拖动或点一下照片，选你要问的位置",
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

/** Shared empty height so a missing card does not jump the layout around. */
internal val CARD_RESERVED_HEIGHT = 132.dp

@Composable
internal fun ThinDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    )
}

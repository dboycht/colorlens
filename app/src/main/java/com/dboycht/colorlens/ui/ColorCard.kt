package com.dboycht.colorlens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dboycht.colorlens.color.ColorReading
import com.dboycht.colorlens.color.Rgb8

/** Compose colour from a pure-Kotlin [Rgb8]. */
fun Rgb8.toComposeColor(): Color = Color(toArgb())

/** Black or white text, whichever is legible on [this]. */
fun Rgb8.readableTextColor(): Color = if (isDark()) Color.White else Color(0xFF101319)

/**
 * The main reading: a big block of the sampled colour with its name written
 * directly on it, then the supporting detail underneath.
 *
 * Why the name goes *on* the colour instead of next to it: the point of the app is
 * to connect a name to what the user is looking at, and putting them in the same
 * glance is the whole trick. No scrim or shadow is used, because anything drawn
 * over the swatch changes the colour the user is trying to judge.
 */
@Composable
fun ColorCard(
    reading: ColorReading,
    showHex: Boolean,
    modifier: Modifier = Modifier,
    /** What the user sees, when that differs from the true colour. */
    seenAs: Rgb8? = null,
    caption: String? = null,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(reading.rgb.toComposeColor())
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
            ) {
                Text(
                    text = reading.primaryName,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = reading.rgb.readableTextColor(),
                )
                val extras = buildList {
                    reading.specificHint?.let { add("更像$it") }
                    if (reading.alternatives.isNotEmpty()) {
                        add("介于${reading.baseWord}和${reading.alternatives.first()}之间")
                    }
                }
                if (extras.isNotEmpty()) {
                    Text(
                        text = extras.joinToString(" · "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = reading.rgb.readableTextColor().copy(alpha = 0.85f),
                    )
                }
            }
            caption?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = reading.rgb.readableTextColor().copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp),
                )
            }
        }

        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = reading.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showHex) {
                    Text(
                        text = reading.hex,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = reading.rgb.toRgbText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            seenAs?.let { seen ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(seen.toComposeColor()),
                        )
                        Text(
                            text = "你看到的大概是这个颜色",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** A small labelled colour chip, used by the comparison screen. */
@Composable
fun Swatch(
    label: String,
    rgb: Rgb8,
    modifier: Modifier = Modifier,
    showLabelOnColour: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(rgb.toComposeColor())
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
    ) {
        if (showLabelOnColour) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = rgb.readableTextColor(),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            )
        }
    }
}

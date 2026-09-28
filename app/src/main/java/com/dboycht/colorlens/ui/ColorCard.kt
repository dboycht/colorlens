package com.dboycht.colorlens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.style.TextOverflow
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
    val extras = buildList {
        reading.specificHint?.let { add("更像$it") }
        if (reading.alternatives.isNotEmpty()) {
            add("介于${reading.baseWord}和${reading.alternatives.first()}之间")
        }
    }.joinToString(" · ")

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
            }

            /**
             * The colour numbers and the photo's source share the top edge of the
             * swatch. Hex/RGB are reference data rather than reading matter, and every
             * row they occupy *below* the swatch is a row the photo above loses — on
             * the reference phone that trade was 22 dp of photo for one line of
             * numbers. The caption yields (ellipsis) if 大字号 crowds it, because
             * "where the photo came from" is the least important thing on screen.
             */
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (caption != null) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelMedium,
                        color = reading.rgb.readableTextColor().copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (caption == null || showHex) Spacer(modifier = Modifier.weight(1f))
                if (showHex) {
                    Text(
                        text = reading.hex,
                        style = MaterialTheme.typography.labelLarge,
                        color = reading.rgb.readableTextColor().copy(alpha = 0.85f),
                    )
                    Text(
                        text = reading.rgb.toRgbText(),
                        style = MaterialTheme.typography.labelLarge,
                        color = reading.rgb.readableTextColor().copy(alpha = 0.85f),
                    )
                }
            }
        }

        // Reading matter. Fullscreen mode uses its own compact bar instead of this
        // card, so this is the only variant: swatch, description, optional "what you
        // see" chip.
        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                // The "更像…/介于…之间" hint rides along with the description rather
                // than sitting inside the swatch. Inside, at 大字号, its line pushed
                // the name up into the hex numbers on the swatch's top edge —
                // measured overlap 32 px — and the swatch is the one place where two
                // texts must never collide. Joined here it costs no extra line.
                text = if (extras.isEmpty()) reading.description else "${reading.description} · $extras",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
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

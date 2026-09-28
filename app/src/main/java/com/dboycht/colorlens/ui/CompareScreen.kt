package com.dboycht.colorlens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dboycht.colorlens.color.ColorCompare
import com.dboycht.colorlens.color.ColorNamer
import com.dboycht.colorlens.color.VisionSimulator
import java.util.Locale

/** Below this distance the two markers are effectively the same spot in the photo. */
private const val SAME_SPOT_PIXELS = 3f

/**
 * "这两个颜色我分得出来吗？"
 *
 * The screen shows four swatches on purpose: the pair as **other people** see it,
 * and the pair as **the user** sees it. Seeing both is what makes the verdict
 * believable — a statement about a difference the user cannot perceive is only
 * useful if they can trust where it came from.
 */
@Composable
fun CompareScreen(
    store: PhotoStore,
    settings: AppSettings,
    onSpeak: (String) -> Unit,
    onPickMarker: (PhotoStore.Marker) -> Unit,
    onNeedPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val photo = store.bitmap
    if (photo == null) {
        CompareEmptyState(
            title = "还没有照片",
            body = "先拍一张或选一张照片，再取两个颜色来对比。",
            actionLabel = "去拍照 / 选照片",
            onAction = onNeedPhoto,
            modifier = modifier,
        )
        return
    }

    val pointA = store.markerA
    val pointB = store.markerB
    if (pointA == null || pointB == null) {
        CompareEmptyState(
            title = "还差一个取色点",
            body = "对比需要两个点：A 和 B。回到取色页面，把十字准星分别放在两个颜色上。",
            actionLabel = if (pointA == null) "去取 A" else "去取 B",
            onAction = { onPickMarker(if (pointA == null) PhotoStore.Marker.A else PhotoStore.Marker.B) },
            modifier = modifier,
        )
        return
    }

    // Two markers on the same pixel is not a comparison. Without this guard the app
    // would happily report "你看起来几乎是同一个颜色" for what is really the same
    // spot measured twice — technically true, completely useless.
    if ((pointA - pointB).getDistance() < SAME_SPOT_PIXELS) {
        CompareEmptyState(
            title = "A 和 B 还叠在一起",
            body = "两个取色点在照片上的同一个位置。回到取色页面，把 B 拖到另一个颜色上再回来。",
            actionLabel = "去挪动 B",
            onAction = { onPickMarker(PhotoStore.Marker.B) },
            modifier = modifier,
        )
        return
    }

    val colourA = BitmapTools.averageAround(photo, pointA.x, pointA.y)
    val colourB = BitmapTools.averageAround(photo, pointB.x, pointB.y)
    val readingA = ColorNamer.read(colourA)
    val readingB = ColorNamer.read(colourB)

    val verdict = remember(colourA, colourB, settings.cvdType, settings.effectiveSeverity(), settings.sensitivityFactor) {
        ColorCompare.compare(
            readingA,
            readingB,
            settings.cvdType,
            settings.effectiveSeverity(),
            settings.sensitivityFactor,
        )
    }

    val seenA = remember(colourA, settings.cvdType, settings.effectiveSeverity()) {
        VisionSimulator.simulate(colourA, settings.cvdType, settings.effectiveSeverity())
    }
    val seenB = remember(colourB, settings.cvdType, settings.effectiveSeverity()) {
        VisionSimulator.simulate(colourB, settings.cvdType, settings.effectiveSeverity())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (verdict.hiddenFromUser || verdict.familyHiddenFromUser) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = verdict.headline,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = verdict.advice, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "判断依据：${settings.cvdType.label}" +
                        if (settings.cvdType.severity < 1f) "（程度 ${(settings.effectiveSeverity() * 100).toInt()}%）" else "" +
                        " · 灵敏度${settings.sensitivity.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionTitle("其它人看到的")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Swatch("A ${readingA.primaryName}", colourA, Modifier.weight(1f).height(96.dp))
            Swatch("B ${readingB.primaryName}", colourB, Modifier.weight(1f).height(96.dp))
        }

        SectionTitle("你看到的（${settings.cvdType.label}）")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Swatch("A ${verdict.seenFirst.primaryName}", seenA, Modifier.weight(1f).height(96.dp))
            Swatch("B ${verdict.seenSecond.primaryName}", seenB, Modifier.weight(1f).height(96.dp))
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "色差（OKLab，0.02 约等于刚好能分辨）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "常人 ${fmt(verdict.normalDistance)}  ·  你 ${fmt(verdict.seenDistance)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "明暗差 ${verdict.lightnessGapPct}%  ·  色相相差 常人 ${verdict.normalHueGapDeg}° / 你 ${verdict.seenHueGapDeg}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "色相相差 0° 有两种可能：两者本来同色相，或者颜色太灰、色相不适用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    onSpeak(
                        when (settings.speechDetail) {
                            SpeechDetail.BRIEF -> verdict.speakText
                            SpeechDetail.DETAILED -> verdict.speakTextDetailed
                        },
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("朗读结论")
            }
            OutlinedButton(
                onClick = { onPickMarker(PhotoStore.Marker.A) },
                modifier = Modifier.weight(1f),
            ) {
                Text("重取 A")
            }
            OutlinedButton(
                onClick = { onPickMarker(PhotoStore.Marker.B) },
                modifier = Modifier.weight(1f),
            ) {
                Text("重取 B")
            }
        }

        Hint(
            "「色相上几乎一样」指的是颜色种类的区别对你来说已经消失；" +
                "这时明暗差就是最可靠的线索。" +
                if (settings.cvdType.family == com.dboycht.colorlens.color.CvdFamily.TRITAN) {
                    " 蓝色觉异常的模拟精度有限，结论仅供参考。"
                } else {
                    ""
                },
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
internal fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun CompareEmptyState(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

private fun fmt(value: Float): String = String.format(Locale.US, "%.2f", value)

/** A four-swatch strip used in the help text: normal pair above, simulated pair below. */
@Composable
internal fun RowSpacer(height: Int = 2) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(MaterialTheme.colorScheme.background)
            .clip(RoundedCornerShape(2.dp)),
    )
}

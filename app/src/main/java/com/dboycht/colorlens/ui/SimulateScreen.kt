package com.dboycht.colorlens.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.dboycht.colorlens.color.CvdFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "你的视角 / 常人的视角" — the same photo through two pairs of eyes.
 *
 * This is the screen that turns the app from a lookup tool into an explanation
 * tool: a person with colour-vision deficiency usually *cannot* tell that they
 * are missing something, because there is nothing obviously absent from their
 * view. Putting the two renderings next to each other makes the difference
 * visible, which is also what makes an argument like "we should label the chart"
 * land with colleagues and family.
 */
@Composable
fun SimulateScreen(
    store: PhotoStore,
    settings: AppSettings,
    onNeedPhoto: () -> Unit,
    onSeverityChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val photo = store.bitmap
    if (photo == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("还没有照片", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "拍一张或选一张照片，就能看到它在你眼里和别人眼里有什么不同。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Button(onClick = onNeedPhoto) { Text("去拍照 / 选照片") }
        }
        return
    }

    var userView by rememberSaveable { mutableStateOf(true) }
    val type = settings.cvdType
    val severity = settings.effectiveSeverity()

    val shown by produceState<android.graphics.Bitmap?>(initialValue = null, photo, type, severity, userView) {
        val produced = withContext(Dispatchers.Default) {
            if (userView) {
                BitmapTools.simulate(photo, type, severity)
            } else {
                BitmapTools.scaleToFit(photo, BitmapTools.PREVIEW_EDGE)
            }
        }
        val previous = value
        value = produced
        // The simulate() preview is a fresh allocation each time; release the one
        // it replaces, but never the user's own photo.
        if (previous != null && previous !== photo && previous !== produced) previous.recycle()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = userView,
                onClick = { userView = true },
                label = { Text("你的视角（${type.label}）") },
            )
            FilterChip(
                selected = !userView,
                onClick = { userView = false },
                label = { Text("常人的视角") },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF05070A)),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = shown
            if (bitmap == null) {
                Text("正在计算…", color = Color.White)
            } else {
                Image(
                    bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                    contentDescription = if (userView) "你的视角下的照片" else "常人视角下的照片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        Text(
            text = when {
                !userView -> "这是大多数人的看到的样子，用来对照。"
                type.family == CvdFamily.ACHROMATOPSIA -> "全色盲的世界只有明暗，没有颜色。"
                type.severity < 1f -> "按「${type.label}」程度 ${(severity * 100).toInt()}% 模拟。"
                else -> "按「${type.label}」模拟（完全型）。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (type.severity < 1f) {
            Column {
                Text(
                    text = "程度：${(severity * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = severity,
                    onValueChange = onSeverityChange,
                    valueRange = 0.1f..1f,
                )
                Hint("程度可以按你自己的感觉调：不同的人差别很大，中度的人设 40%–60% 往往更接近你真实看到的样子。")
            }
        }

        Hint(
            "这个模拟用的是 Machado、Oliveira 与 Fernandes (2009) 的模型，它是人群平均的近似，不是医学诊断，" +
                "也不能替代你自己的眼睛。" +
                if (type.family == CvdFamily.TRITAN) {
                    " 该模型对蓝色觉异常（第三型）的可靠度较低，请只当作大致参考。"
                } else {
                    ""
                },
        )
    }
}

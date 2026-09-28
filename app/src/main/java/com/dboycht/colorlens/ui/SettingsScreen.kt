package com.dboycht.colorlens.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dboycht.colorlens.BuildConfig
import com.dboycht.colorlens.color.CvdType

/**
 * Settings.
 *
 * Ordering is by "what will the user need to fix first": the colour-vision type
 * changes every number the app produces, so it comes first, and the privacy
 * statement sits at the bottom where people look for it.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    speaker: Speaker,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onInstallVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // ------------------------------------------------------------- colour vision
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("你的色觉")
            Hint("选一个最接近的。拿不准就先留「正常色觉」——对比页会按常人的分辨力给建议；知道自己偏哪一类，再选对应的，随时可以改。")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    CvdType.CHOICES.forEachIndexed { index, type ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChange { it.copy(cvdType = type) } }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.cvdType == type,
                                onClick = { onChange { it.copy(cvdType = type) } },
                            )
                            Column(modifier = Modifier.padding(start = 6.dp)) {
                                Text(type.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = type.hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // The slider is only meaningful for the "弱" types: 正常色觉 has nothing to
        // simulate, and the 盲 types are pinned at 100%. Showing a 10% slider for
        // normal vision (the default) would imply the app is simulating something.
        if (!settings.cvdType.isNormal && settings.cvdType.severity < 1f) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "程度：${(settings.effectiveSeverity() * 100).toInt()}%",
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = settings.effectiveSeverity(),
                    onValueChange = { value -> onChange { it.copy(severity = value) } },
                    valueRange = 0.1f..1f,
                )
                Hint("「弱」是程度问题，人与人差别很大。调到自己看着顺眼为止。")
            }
        }

        // ------------------------------------------------------------------- speech
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("语音播报")
            SettingSwitch(
                title = "播报颜色",
                subtitle = "取色和对比结论都可以念出来",
                checked = settings.speechEnabled,
                onChange = { value -> onChange { it.copy(speechEnabled = value) } },
            )
            SettingSwitch(
                title = "松手就播报",
                subtitle = "拖动取色时松手自动念，不用再点按钮",
                checked = settings.autoSpeakOnPick,
                onChange = { value -> onChange { it.copy(autoSpeakOnPick = value) } },
            )
            SectionTitle("播报内容")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechDetail.entries.forEach { detail ->
                    FilterChip(
                        selected = settings.speechDetail == detail,
                        onClick = { onChange { it.copy(speechDetail = detail) } },
                        label = { Text(detail.label) },
                    )
                }
            }
            Hint(
                when (settings.speechDetail) {
                    SpeechDetail.BRIEF -> "简短：取色只念色名，对比只念「A 是什么、B 是什么、分不分得出来」。"
                    SpeechDetail.DETAILED -> "详细：再念明暗、冷暖，以及对比时可以靠什么分辨。"
                },
            )
            if (!speaker.chineseAvailable) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "这台手机没有装中文语音包，播报可能听不清。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = "点这里去安装语音数据",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable(onClick = onInstallVoice),
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------------ display
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("显示")
            SettingSwitch(
                title = "大字号",
                subtitle = "所有文字放大 30%",
                checked = settings.largeText,
                onChange = { value -> onChange { it.copy(largeText = value) } },
            )
            SettingSwitch(
                title = "高对比",
                subtitle = "纯黑背景、纯白文字，字和背景分得更开",
                checked = settings.highContrast,
                onChange = { value -> onChange { it.copy(highContrast = value) } },
            )
            SettingSwitch(
                title = "显示 Hex / RGB 数值",
                subtitle = "给需要把颜色报给别人的场合（比如网购、设计稿）",
                checked = settings.showHex,
                onChange = { value -> onChange { it.copy(showHex = value) } },
            )
        }

        // -------------------------------------------------------------- sensitivity
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("对比灵敏度")
            Hint("对比结论里「多接近才算危险」这条线，可以按自己的感觉调。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompareSensitivity.entries.forEach { option ->
                    FilterChip(
                        selected = settings.sensitivity == option,
                        onClick = { onChange { it.copy(sensitivity = option) } },
                        label = { Text(option.label) },
                    )
                }
            }
            Hint(settings.sensitivity.hint)
        }

        // -------------------------------------------------------------------- about
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("关于")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "辨色助手 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "照片只在这台手机上处理：不联网、不上传、不保存，也不会存进相册。" +
                            "关掉应用，照片就没有了。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "色名依据汉语基本颜色词（红、橙、黄、黄绿、绿、青绿、天蓝、蓝、紫、品红、粉、棕、米、灰、白、黑），" +
                            "进阶色名只作为「更像…」的提示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "色差用 OKLab（Ottosson）感知距离计算；色觉模拟用 Machado, Oliveira & Fernandes (2009) " +
                            "的线性 RGB 矩阵，共 3 类 × 11 档，中间档按论文用相邻两档插值。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "这不是医疗器械，也不能代替医生或专业色觉检查。",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

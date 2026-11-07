package com.hush.space.ui.compose

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.hush.space.core.Prefs

/**
 * 一套预设色板。
 *
 * 只存一个种子色，明暗两套色阶都由 [hushScheme] 现算 —— 这样新增预设
 * 只需要加一行，不用手写 30 多个颜色值，也不会出现某套预设漏改某个槽位。
 */
data class HushPreset(
    val id: Int,
    val label: String,
    val seed: Color,
)

val HushPresets = listOf(
    HushPreset(0, "静海", Color(0xFF4F46E5)),
    HushPreset(1, "青瓷", Color(0xFF00796B)),
    HushPreset(2, "暮橙", Color(0xFFE8590C)),
    HushPreset(3, "森野", Color(0xFF2E7D32)),
    HushPreset(4, "樱雾", Color(0xFFC2185B)),
    HushPreset(5, "石墨", Color(0xFF475569)),
)

/** 相对亮度，用于算对比度（WCAG 2.1）。 */
private fun relLum(c: Color): Double {
    fun f(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * f(c.red) + 0.7152 * f(c.green) + 0.0722 * f(c.blue)
}

private fun contrastOf(a: Color, b: Color): Double {
    val la = relLum(a)
    val lb = relLum(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

/**
 * 由一个种子色生成 MD3 配色。
 *
 * 这里的映射规则参照 Material 3 的 tone 板：
 * 亮色下主色取 tone 40、容器取 tone 90、文字取 tone 10；
 * 暗色下反过来，主色取 tone 80、容器取 tone 30。
 * 用 HSL 近似 HCT，色相保持一致，饱和度按槽位衰减，
 * 因此任意种子色（包括用户自选的）都能得到协调的一套，而不会出现
 * 「主色换了、容器还是紫的」这种断裂。
 *
 * 主色还会做一次对比度校正：青绿、黄这类高明度色相在 tone 40 下
 * 配白字只有 2:1 左右，按钮文字会发虚。这里逐步压低（暗色下提高）
 * 亮度直到与 onPrimary 达到 4.5:1，避免预设里出现读不清的按钮。
 */
fun hushScheme(seed: Color, dark: Boolean): ColorScheme {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
    val hue = hsv[0]
    val sat = hsv[1].coerceIn(0.18f, 0.90f)

    fun t(l: Float, s: Float = sat, h: Float = hue): Color = Color.hsl(
        ((h % 360f) + 360f) % 360f,
        s.coerceIn(0f, 1f),
        l.coerceIn(0f, 1f),
    )

    val onPrimaryColor = if (!dark) t(0.99f, 0.04f) else t(0.20f, sat * 0.8f)
    var primaryLightness = if (!dark) 0.42f else 0.78f
    var primaryColor = t(primaryLightness)
    var guard = 0
    while (contrastOf(primaryColor, onPrimaryColor) < 4.5 && guard++ < 40) {
        primaryLightness += if (dark) 0.01f else -0.01f
        primaryColor = t(primaryLightness.coerceIn(0.18f, 0.92f))
    }

    return if (!dark) {
        lightColorScheme(
            primary = primaryColor,
            onPrimary = onPrimaryColor,
            primaryContainer = t(0.91f, sat * 0.55f),
            onPrimaryContainer = t(0.17f),
            inversePrimary = t(0.78f, sat * 0.6f),
            secondary = t(0.46f, sat * 0.55f),
            onSecondary = t(0.99f, 0.04f),
            secondaryContainer = t(0.92f, sat * 0.35f),
            onSecondaryContainer = t(0.20f, sat * 0.7f),
            tertiary = t(0.48f, sat * 0.62f, hue + 52f),
            onTertiary = t(0.99f, 0.04f),
            tertiaryContainer = t(0.92f, sat * 0.35f, hue + 52f),
            onTertiaryContainer = t(0.20f, sat * 0.7f, hue + 52f),
            background = t(0.985f, 0.06f),
            onBackground = t(0.16f, sat * 0.5f),
            surface = t(0.985f, 0.06f),
            onSurface = t(0.16f, sat * 0.5f),
            surfaceVariant = t(0.92f, sat * 0.22f),
            onSurfaceVariant = t(0.40f, sat * 0.38f),
            surfaceTint = primaryColor,
            inverseSurface = t(0.20f, sat * 0.3f),
            inverseOnSurface = t(0.96f, 0.05f),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
            outline = t(0.60f, sat * 0.30f),
            outlineVariant = t(0.83f, sat * 0.22f),
            scrim = Color(0xFF000000),
            surfaceBright = t(0.99f, 0.04f),
            surfaceDim = t(0.88f, 0.10f),
            surfaceContainerLowest = t(1.0f, 0.03f),
            surfaceContainerLow = t(0.97f, 0.07f),
            surfaceContainer = t(0.955f, 0.08f),
            surfaceContainerHigh = t(0.935f, 0.10f),
            surfaceContainerHighest = t(0.915f, 0.12f),
        )
    } else {
        darkColorScheme(
            primary = primaryColor,
            onPrimary = onPrimaryColor,
            primaryContainer = t(0.34f, sat * 0.75f),
            onPrimaryContainer = t(0.91f, sat * 0.5f),
            inversePrimary = t(0.42f),
            secondary = t(0.76f, sat * 0.45f),
            onSecondary = t(0.20f, sat * 0.5f),
            secondaryContainer = t(0.30f, sat * 0.40f),
            onSecondaryContainer = t(0.90f, sat * 0.30f),
            tertiary = t(0.76f, sat * 0.50f, hue + 52f),
            onTertiary = t(0.20f, sat * 0.5f, hue + 52f),
            tertiaryContainer = t(0.30f, sat * 0.40f, hue + 52f),
            onTertiaryContainer = t(0.90f, sat * 0.30f, hue + 52f),
            background = t(0.085f, 0.10f),
            onBackground = t(0.92f, sat * 0.20f),
            surface = t(0.085f, 0.10f),
            onSurface = t(0.92f, sat * 0.20f),
            surfaceVariant = t(0.26f, sat * 0.22f),
            onSurfaceVariant = t(0.78f, sat * 0.22f),
            surfaceTint = primaryColor,
            inverseSurface = t(0.92f, sat * 0.15f),
            inverseOnSurface = t(0.20f, sat * 0.3f),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
            errorContainer = Color(0xFF8C1D18),
            onErrorContainer = Color(0xFFF9DEDC),
            outline = t(0.58f, sat * 0.30f),
            outlineVariant = t(0.32f, sat * 0.22f),
            scrim = Color(0xFF000000),
            surfaceBright = t(0.26f, 0.12f),
            surfaceDim = t(0.085f, 0.10f),
            surfaceContainerLowest = t(0.06f, 0.10f),
            surfaceContainerLow = t(0.12f, 0.11f),
            surfaceContainer = t(0.15f, 0.11f),
            surfaceContainerHigh = t(0.19f, 0.11f),
            surfaceContainerHighest = t(0.23f, 0.11f),
        )
    }
}

/** 取当前配置对应的种子色，供设置页做选中态高亮。 */
fun currentSeed(presetId: Int, customSeed: Int): Color =
    if (presetId in HushPresets.indices) HushPresets[presetId].seed else Color(customSeed)

@Composable
fun HushTheme(
    themeMode: Int,
    presetId: Int,
    customSeed: Int,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        Prefs.THEME_LIGHT -> false
        Prefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> hushScheme(currentSeed(presetId, customSeed), dark)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

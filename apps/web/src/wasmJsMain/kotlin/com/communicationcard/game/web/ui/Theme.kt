package com.communicationcard.game.web.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * 沟通牌 · Web 客户端全局 Theme（Stage 3 抽离）。
 *
 * - [GreenTableColors]：绿色赌桌配色，与 Android 端 res/values/colors.xml 对齐
 *   （A/B 队红/蓝，主操作金黄，次要白）
 * - [cjkTypography]：把 Material3 全部 textStyle 的 fontFamily 设成中文 family；
 *   未加载完字体时（family=null）回退默认 Typography。同时按 LayoutMode 缩放字号。
 *
 * 使用：在 App.kt 顶层 `CommunicationCardTheme(cjkFamily, mode) { ... }` 包裹整个 UI。
 */

object GreenTableColors {
    val tableGreen = Color(0xFF1B5E20)        // 主桌面背景
    val tableGreenDeep = Color(0xFF0E3812)    // 次要面板背景
    val tableGreenLight = Color(0xFF2E7D32)   // 卡片表面
    val tableGreenAccent = Color(0xFF388E3C)  // 列表项
    val tableGreenSelected = Color(0xFF43A047) // 选中态背景

    val brandPrimary = Color(0xFFFFC107)      // 金黄主操作
    val onBrandPrimary = Color.Black

    val brandSecondary = Color(0xFF66BB6A)    // 副操作绿
    val onBrandSecondary = Color.Black

    val teamA = Color(0xFFEF5350)             // A 队红
    val teamB = Color(0xFF42A5F5)             // B 队蓝
    val teamABg = Color(0xFF5D2A2A)           // A 队卡片底
    val teamBBg = Color(0xFF22416A)           // B 队卡片底

    val textOnDark = Color.White
    val textMuted = Color(0xFFE8F5E9)
    val textSubtle = Color(0xFFB2DFDB)
    val textDisabled = Color(0xFFB0BEC5)

    val warning = Color(0xFFE53935)
    val cardWhite = Color.White
    val cardOutline = Color(0xFF424242)
    val hintBorder = Color(0xFF66BB6A)
    val selectedBorder = Color(0xFFFFC107)
}

private fun greenTableScheme() = darkColorScheme(
    primary = GreenTableColors.brandPrimary,
    onPrimary = GreenTableColors.onBrandPrimary,
    secondary = GreenTableColors.brandSecondary,
    onSecondary = GreenTableColors.onBrandSecondary,
    background = GreenTableColors.tableGreen,
    onBackground = GreenTableColors.textOnDark,
    surface = GreenTableColors.tableGreenLight,
    onSurface = GreenTableColors.textOnDark,
    error = GreenTableColors.warning,
)

/**
 * 把 Material3 的 15 个 textStyle 的 fontFamily 都设为 [cjkFamily]，并按
 * [mode] 适当缩放字号（Compact 比 Expanded 小 2 sp 左右）。
 *
 * cjkFamily=null 时（首屏字体未加载完）回退 Material3 默认 Typography（Latin
 * 字符正常显示，中文豆腐 < 1s）。
 */
@Composable
fun cjkTypography(cjkFamily: FontFamily?, mode: LayoutMode = LayoutMode.Medium): Typography {
    val base = Typography()
    val scale = when (mode) {
        LayoutMode.Compact -> 0.92f
        LayoutMode.Medium -> 1.0f
        LayoutMode.Expanded -> 1.08f
    }
    fun TextStyle.tweak(): TextStyle = copy(
        fontFamily = cjkFamily ?: this.fontFamily,
        fontSize = (this.fontSize.value * scale).sp,
    )
    return Typography(
        displayLarge = base.displayLarge.tweak(),
        displayMedium = base.displayMedium.tweak(),
        displaySmall = base.displaySmall.tweak(),
        headlineLarge = base.headlineLarge.tweak(),
        headlineMedium = base.headlineMedium.tweak(),
        headlineSmall = base.headlineSmall.tweak(),
        titleLarge = base.titleLarge.tweak(),
        titleMedium = base.titleMedium.tweak(),
        titleSmall = base.titleSmall.tweak(),
        bodyLarge = base.bodyLarge.tweak(),
        bodyMedium = base.bodyMedium.tweak(),
        bodySmall = base.bodySmall.tweak(),
        labelLarge = base.labelLarge.tweak().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.tweak(),
        labelSmall = base.labelSmall.tweak(),
    )
}

@Composable
fun CommunicationCardTheme(
    cjkFamily: FontFamily?,
    mode: LayoutMode = LayoutMode.Medium,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = greenTableScheme(),
        typography = cjkTypography(cjkFamily, mode),
        content = content,
    )
}

package com.communicationcard.game.web.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 沟通牌 · Web 客户端全局 Theme（Stage 3 抽离）。
 *
 * - [GreenTableColors]：绿色赌桌配色 token。仅本 module 内消费；如要与
 *   Android 端 res/values/colors.xml 对齐请人工同步（**没有自动校验**）。
 * - [cjkTypography]：纯函数（非 @Composable）—— 必须由调用方用
 *   `remember(family, mode)` 包裹，否则每次重组都会重建 15 个 TextStyle 实例
 *   触发整树 invalidate。详见 pr-reviewer P1 on PR #49。
 * - [LocalLayoutMode]：CompositionLocal，让子树拿到当前 LayoutMode，避免
 *   每个屏自己再开 BoxWithConstraints。
 *
 * 使用：
 *   ```kotlin
 *   BoxWithConstraints {
 *       val mode = classify(maxWidth)
 *       CommunicationCardTheme(cjkFont, mode) { /* content */ }
 *   }
 *   ```
 */

object GreenTableColors {
    val tableGreen = Color(0xFF1B5E20)        // 主桌面背景
    val tableGreenDeep = Color(0xFF0E3812)    // 次要面板背景（顶栏 / 手牌区）
    val tableGreenLight = Color(0xFF2E7D32)   // 卡片表面
    val tableGreenAccent = Color(0xFF388E3C)  // 列表项

    val brandPrimary = Color(0xFFFFC107)      // 金黄主操作
    val onBrandPrimary = Color.Black

    val brandSecondary = Color(0xFF66BB6A)    // 副操作绿（hint border / 已走完）
    val onBrandSecondary = Color.Black

    val teamA = Color(0xFFEF5350)             // A 队前景红（标题/小圆点/文字）
    val teamB = Color(0xFF42A5F5)             // B 队前景蓝（标题/小圆点/文字）
    val teamABg = Color(0xFF5D2A2A)           // A 队卡片底
    val teamBBg = Color(0xFF22416A)           // B 队卡片底

    val textOnDark = Color.White
    val textMuted = Color(0xFFE8F5E9)

    val warning = Color(0xFFE53935)
    val cardWhite = Color.White
    val cardOutline = Color(0xFF424242)
    val hintBorder = Color(0xFF66BB6A)
    val selectedBorder = Color(0xFFFFC107)

    // Stage F：与 Android `current_round_background` / `player_area_background` 对齐
    val goldBorder = Color(0xFFFFC107)             // 中央面板黄色描边
    val centralPanelBg = Color(0x33000000)         // 中央面板半透明黑底
    val playerStripBg = Color(0x40000000)          // 玩家卡片半透明黑底（Android view_player_with_cards 同款）
    val handAreaBg = Color(0x40000000)             // 手牌区底色（Android playerHandArea 同款 #40000000）
    val transientBg = Color(0xCC000000)            // 瞬时消息背景（Android tvMessage 同款 #CC000000）
    val bombBorder = Color(0xFFFF8F00)             // 炸弹卡片高亮橙边
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
 * Material3 Typography with all 15 textStyles tweaked: fontFamily=cjkFamily and
 * fontSize *= scale (Compact 0.92 / Medium 1.0 / Expanded 1.08).
 *
 * **纯函数。调用方负责 `remember(family, mode)` 包裹**——否则每次 viewport
 * 重组都会触发 15 次 TextStyle.copy + 整树 invalidate（pr-reviewer P1 on #49）。
 */
fun cjkTypography(cjkFamily: FontFamily?, mode: LayoutMode): Typography {
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

/** CompositionLocal 让子树读取当前 LayoutMode，无需自己再开 BoxWithConstraints。 */
val LocalLayoutMode = compositionLocalOf { LayoutMode.Medium }

@Composable
fun CommunicationCardTheme(
    cjkFamily: FontFamily?,
    mode: LayoutMode,
    content: @Composable () -> Unit,
) {
    // remember(family, mode)：family 或 mode 不变时 Typography 实例稳定，
    // 整树不会因 viewport 抖动而 invalidate。
    val typography = remember(cjkFamily, mode) { cjkTypography(cjkFamily, mode) }
    CompositionLocalProvider(LocalLayoutMode provides mode) {
        MaterialTheme(
            colorScheme = greenTableScheme(),
            typography = typography,
            content = content,
        )
    }
}

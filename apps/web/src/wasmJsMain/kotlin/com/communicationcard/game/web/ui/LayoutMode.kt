package com.communicationcard.game.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 屏幕尺寸分级（响应式布局）。
 *
 * 用 `BoxWithConstraints` 拿到的 `maxWidth: Dp` 喂给 [classify] 即可。
 * 与 Material3 的 `WindowSizeClass` 概念对齐，但 CMP 1.6.10 wasmJs 没有
 * 现成的 WindowSizeClass，所以这里自己定一个最小集。
 *
 * - [Compact]：手机竖屏 / 小窗（< 600 dp）—— 2 列布局，多行手牌，按钮分行
 * - [Medium]：iPad / 笔记本 / 手机横屏（600 - 1200 dp）—— 3 列玩家 + 单行手牌
 * - [Expanded]：宽屏笔记本 / 桌面 / 4K（>= 1200 dp）—— 一字排玩家 + 大卡牌
 *
 * 阈值参考 Material3 WindowSizeClass：Compact < 600, Medium 600-840,
 * Expanded >= 840。本项目 5-6 个玩家头像至少要 600 dp 才能横排，所以把
 * Medium 的上限放宽到 1200 dp，留更多空间给 4K 等大屏的"奢华"布局。
 */
enum class LayoutMode {
    Compact,
    Medium,
    Expanded;

    val isCompact: Boolean get() = this == Compact
    val isExpanded: Boolean get() = this == Expanded
}

@ReadOnlyComposable
@Composable
fun classify(width: Dp): LayoutMode = when {
    width < COMPACT_MAX -> LayoutMode.Compact
    width < MEDIUM_MAX -> LayoutMode.Medium
    else -> LayoutMode.Expanded
}

// 常量放外层文件级，避免 enum 内 const 限制
val COMPACT_MAX: Dp = 600.dp
val MEDIUM_MAX: Dp = 1200.dp

package com.communicationcard.game.web.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.communicationcard.game.network.SerializedCard
import com.communicationcard.game.network.SerializedCardGroup
import com.communicationcard.game.network.SerializedGameState
import com.communicationcard.game.network.SerializedPlayer
import com.communicationcard.game.web.viewmodel.Screen

/**
 * 游戏中屏幕（Stage 2 改为响应式）。
 *
 * 三档布局（[LayoutMode]）：
 *
 * - **Compact** (< 600 dp，手机竖屏)
 *   - 顶栏：图标 + 紧凑分数；当前玩家文字省略
 *   - 玩家区：2 列 × N 行 grid（5 玩家 = 3 行 / 7 = 4 行）
 *   - 上一手：放玩家区下方
 *   - 手牌：LazyRow 单行 + 横向滚动；卡片 44×62 dp
 *   - 操作按钮：2 行（提示+过牌 / 出牌单独宽按钮）
 *
 * - **Medium** (600-1200 dp，iPad / 笔记本)
 *   - 顶栏完整
 *   - 玩家区：3 列横排（5+ 玩家自动 wrap 到第二行）
 *   - 上一手：玩家区下方居中
 *   - 手牌：LazyRow 单行；卡片 56×80 dp
 *   - 操作按钮：1 行（当前布局）
 *
 * - **Expanded** (>= 1200 dp，宽屏 / 4K)
 *   - 顶栏完整
 *   - 玩家区：1 行 5 列均分，最大化卡片显示
 *   - 上一手：居中放大
 *   - 手牌：LazyRow 单行；卡片 64×90 dp（更大更舒适）
 *   - 操作按钮：1 行 + 更大字号
 */
@Composable
fun GameScreen(
    state: Screen.Game,
    onPlayCards: (List<SerializedCard>) -> Unit,
    onPass: () -> Unit,
    onHint: () -> Unit,
    onToggleSelected: (String) -> Unit,
    onLeave: () -> Unit,
) {
    // Stage 3 nit#2: 读 LocalLayoutMode（Theme.kt 提供）而非自己开 BoxWithConstraints。
    // 整个 App 已经在 App.kt 一处统一 classify 过；这里保持单一真相来源。
    val mode = LocalLayoutMode.current
    val me = state.state.players.find { it.id == state.localSeatIndex }
    val isMyTurn = state.state.currentPlayerIndex == state.localSeatIndex
    val cardSize = cardSizeFor(mode)

    Box(modifier = Modifier.fillMaxSize().background(GreenTableColors.tableGreen)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(state = state.state, onLeave = onLeave, mode = mode)

            // 桌面区
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                TableArea(state = state.state, localSeatIndex = state.localSeatIndex, mode = mode)
            }

            // 手牌 + 操作
            //
            // P2#1 修复：Compact + 6 玩家时 36 张牌 FlowRow 多行可能占 >450dp，
            // 加上 chrome 总高超过 640dp 视口 → 出牌按钮被裁掉。
            // 方案：手牌区域 heightIn(max) 限制 + verticalScroll 兜底，
            // 让 ActionButtons 永远可见。Medium/Expanded 单行 hand 没此问题。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GreenTableColors.tableGreenDeep)
                    .padding(horizontal = 8.dp, vertical = if (mode.isCompact) 4.dp else 8.dp),
            ) {
                Box(
                    modifier = if (mode.isCompact) {
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = HAND_MAX_HEIGHT_COMPACT)
                            .verticalScroll(rememberScrollState())
                    } else {
                        Modifier.fillMaxWidth()
                    },
                ) {
                    HandRow(
                        me = me,
                        selected = state.selectedCardIds,
                        hinted = state.hintedCardIds,
                        cardWidth = cardSize.first,
                        cardHeight = cardSize.second,
                        mode = mode,
                        onToggle = onToggleSelected,
                    )
                }

                Spacer(Modifier.height(if (mode.isCompact) 6.dp else 8.dp))

                ActionButtons(
                    mode = mode,
                    isMyTurn = isMyTurn,
                    canPass = isMyTurn && state.state.lastPlayedGroup != null,
                    canPlay = isMyTurn && state.selectedCardIds.isNotEmpty(),
                    onHint = onHint,
                    onPass = onPass,
                    onPlay = {
                        if (state.selectedCardIds.isEmpty() || me == null) return@ActionButtons
                        val toPlay = me.hand.filter { keyOf(it) in state.selectedCardIds }
                        onPlayCards(toPlay)
                    },
                )
            }
        }
    }
}

/* ────────────────────────────────────────────────────────────── */
/*  顶栏                                                            */
/* ────────────────────────────────────────────────────────────── */

@Composable
private fun TopBar(state: SerializedGameState, onLeave: () -> Unit, mode: LayoutMode) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0E3812)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onLeave) {
            Text(if (mode.isCompact) "←" else "离开", color = Color.White)
        }
        Spacer(Modifier.width(if (mode.isCompact) 6.dp else 12.dp))
        ScoreChip(label = "A", score = state.teamAScore, color = Color(0xFFEF5350), mode = mode)
        Spacer(Modifier.width(if (mode.isCompact) 6.dp else 12.dp))
        ScoreChip(label = "B", score = state.teamBScore, color = Color(0xFF42A5F5), mode = mode)
        Spacer(Modifier.fillMaxWidth().weight(1f))
        if (!mode.isCompact) {
            val current = state.players.find { it.id == state.currentPlayerIndex }
            Text(
                text = current?.let { "当前: ${it.name}" } ?: "—",
                color = Color.White,
                fontSize = 14.sp,
            )
        } else {
            // Compact 用单字符圆点指示当前是谁
            val current = state.players.find { it.id == state.currentPlayerIndex }
            Text(
                text = current?.let { "▶ ${it.name.take(4)}" } ?: "—",
                color = Color(0xFFFFC107),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ScoreChip(label: String, score: Int, color: Color, mode: LayoutMode) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(if (mode.isCompact) 8.dp else 12.dp)
                .background(color, shape = RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "$label: $score",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (mode.isCompact) 12.sp else 14.sp,
        )
    }
}

/* ────────────────────────────────────────────────────────────── */
/*  桌面区（玩家头像 + 上一手）—— 三档布局                          */
/* ────────────────────────────────────────────────────────────── */

@Composable
private fun TableArea(state: SerializedGameState, localSeatIndex: Int, mode: LayoutMode) {
    val others = state.players.filter { it.id != localSeatIndex }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (mode.isCompact) 6.dp else 12.dp),
    ) {
        when (mode) {
            LayoutMode.Compact -> CompactPlayersGrid(others, state.currentPlayerIndex)
            LayoutMode.Medium -> MediumPlayersWrap(others, state.currentPlayerIndex)
            LayoutMode.Expanded -> ExpandedPlayersRow(others, state.currentPlayerIndex)
        }

        Spacer(Modifier.height(4.dp))

        val last = state.lastPlayedGroup
        if (last != null) {
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .background(Color(0xFF2E7D32), RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                LastPlayedRow(
                    group = last,
                    owner = state.players.find { it.id == state.lastPlayerId },
                    cardSize = cardSizeFor(mode),
                )
            }
        } else {
            Text(
                text = if (state.consecutivePasses > 0) "等待新一轮 …" else "请出牌",
                color = Color(0xFFE8F5E9),
                fontSize = if (mode.isCompact) 13.sp else 14.sp,
            )
        }
    }
}

/** Compact: 2 列网格，5 玩家 = 3 行（最后 1 行只占左格） */
@Composable
private fun CompactPlayersGrid(others: List<SerializedPlayer>, currentSeat: Int) {
    val rows = others.chunked(2)
    rows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            row.forEach { p ->
                Box(modifier = Modifier.weight(1f)) {
                    OtherPlayerCell(player = p, isCurrent = p.id == currentSeat, compact = true)
                }
            }
            // 凑齐 2 列宽度（最后一行可能只有 1 个）
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Medium: 5 玩家 1 行 width 不够时自动 wrap 到第二行（最多 3 列） */
@Composable
private fun MediumPlayersWrap(others: List<SerializedPlayer>, currentSeat: Int) {
    val rows = others.chunked(3)
    rows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            row.forEach { p ->
                OtherPlayerCell(player = p, isCurrent = p.id == currentSeat, compact = false)
            }
        }
    }
}

/** Expanded: 一字排开，sp 大字号 */
@Composable
private fun ExpandedPlayersRow(others: List<SerializedPlayer>, currentSeat: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        others.forEach { p -> OtherPlayerCell(player = p, isCurrent = p.id == currentSeat, compact = false) }
    }
}

/**
 * 其他玩家卡片（Stage 3 升级）：
 * - 当前玩家边框色 animateColorAsState 平滑过渡（不再瞬切金黄）
 * - 阴影 elevation：当前玩家 6dp / 普通 1dp（突出当前玩家）
 * - 已走完玩家半透明 + "已走完" 标签
 */
@Composable
private fun OtherPlayerCell(player: SerializedPlayer, isCurrent: Boolean, compact: Boolean) {
    val targetBorder = if (isCurrent) GreenTableColors.selectedBorder else Color.Transparent
    val borderColor by animateColorAsState(targetBorder, label = "playerBorder")
    val targetElev = if (isCurrent) 6.dp else 1.dp
    val elev by animateDpAsState(targetElev, label = "playerElev")
    val padding = if (compact) 6.dp else 10.dp
    val shape = RoundedCornerShape(10.dp)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (player.team == "TEAM_A") GreenTableColors.teamABg else GreenTableColors.teamBBg,
        ),
        shape = shape,
        modifier = Modifier
            .padding(if (compact) 2.dp else 4.dp)
            .shadow(elevation = elev, shape = shape, clip = false)
            .border(2.dp, borderColor, shape),
    ) {
        Column(
            modifier = Modifier.padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                player.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 12.sp else 14.sp,
            )
            Text(
                if (player.hasFinished) "已走完" else "剩 ${player.handSize}",
                color = if (player.hasFinished) GreenTableColors.brandSecondary else GreenTableColors.textMuted,
                fontSize = if (compact) 10.sp else 12.sp,
            )
            Text(
                "已收 ${player.collectedScore}",
                color = GreenTableColors.brandPrimary,
                fontSize = if (compact) 10.sp else 12.sp,
            )
        }
    }
}

@Composable
private fun LastPlayedRow(
    group: SerializedCardGroup,
    owner: SerializedPlayer?,
    cardSize: Pair<Dp, Dp>,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = owner?.let { "${it.name} 出: " } ?: "",
            color = Color(0xFFE8F5E9),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            group.cards.forEach { c ->
                CardView(
                    card = c,
                    selected = false,
                    modifier = Modifier.size(cardSize.first, cardSize.second),
                )
            }
        }
    }
}

/* ────────────────────────────────────────────────────────────── */
/*  手牌区 + 操作按钮                                               */
/* ────────────────────────────────────────────────────────────── */

/**
 * 手牌区（Stage 3 升级）：
 * - **Compact 模式用 FlowRow 多行 wrap**，手机上无需横向滚动
 * - Medium / Expanded 仍用 LazyRow（横滚），大屏单行更优雅
 *
 * FlowRow 是 androidx.compose.foundation.layout 的 ExperimentalLayoutApi。
 * CMP 1.6 已经稳定可用，加 @OptIn 抑制警告。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HandRow(
    me: SerializedPlayer?,
    selected: Set<String>,
    hinted: Set<String>,
    cardWidth: Dp,
    cardHeight: Dp,
    mode: LayoutMode,
    onToggle: (String) -> Unit,
) {
    if (me == null) {
        Text("等待发牌 …", color = Color.White)
        return
    }

    if (mode.isCompact) {
        // 手机：多行 wrap，避免 30+ 张牌横向滚动
        // nit#3: 避免每次重组分配 (selected + hinted) Set —— 用 || 短路布尔
        val hasHighlight = selected.isNotEmpty() || hinted.isNotEmpty()
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(if (hasHighlight) 18.dp else 4.dp),
        ) {
            me.hand.forEach { card ->
                val key = keyOf(card)
                CardView(
                    card = card,
                    selected = key in selected,
                    hinted = key in hinted,
                    modifier = Modifier
                        .size(cardWidth, cardHeight)
                        .clickable { onToggle(key) },
                )
            }
        }
    } else {
        // 平板 / 桌面：单行横滚（屏幕宽足够，单行更优雅）
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(me.hand) { card ->
                val key = keyOf(card)
                CardView(
                    card = card,
                    selected = key in selected,
                    hinted = key in hinted,
                    modifier = Modifier
                        .size(cardWidth, cardHeight)
                        .clickable { onToggle(key) },
                )
            }
        }
    }
}

@Composable
private fun ActionButtons(
    mode: LayoutMode,
    isMyTurn: Boolean,
    canPass: Boolean,
    canPlay: Boolean,
    onHint: () -> Unit,
    onPass: () -> Unit,
    onPlay: () -> Unit,
) {
    val btnHeight = if (mode.isCompact) 44.dp else 48.dp
    val playFontSize = if (mode.isExpanded) 20.sp else 18.sp

    if (mode.isCompact) {
        // 2 行：提示 + 过牌 一行 / 出牌 单独一行（最重要的操作占整宽）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = onHint, enabled = isMyTurn,
                modifier = Modifier.weight(1f).height(btnHeight),
            ) { Text("提示", color = Color.White, fontSize = 14.sp) }
            OutlinedButton(
                onClick = onPass, enabled = canPass,
                modifier = Modifier.weight(1f).height(btnHeight),
            ) { Text("过牌", color = Color.White, fontSize = 14.sp) }
        }
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onPlay, enabled = canPlay,
            modifier = Modifier.fillMaxWidth().height(btnHeight),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFC107),
                contentColor = Color.Black,
            ),
        ) { Text("出牌", fontSize = playFontSize, fontWeight = FontWeight.Bold) }
    } else {
        // Medium / Expanded: 1 行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onHint, enabled = isMyTurn,
                modifier = Modifier.weight(1f).height(btnHeight),
            ) { Text("提示", color = Color.White) }
            OutlinedButton(
                onClick = onPass, enabled = canPass,
                modifier = Modifier.weight(1f).height(btnHeight),
            ) { Text("过牌", color = Color.White) }
            Button(
                onClick = onPlay, enabled = canPlay,
                modifier = Modifier.weight(1.4f).height(btnHeight),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFC107),
                    contentColor = Color.Black,
                ),
            ) { Text("出牌", fontSize = playFontSize, fontWeight = FontWeight.Bold) }
        }
    }
}

/* ────────────────────────────────────────────────────────────── */
/*  CardView + 工具                                                 */
/* ────────────────────────────────────────────────────────────── */

/**
 * 卡牌视觉（Stage 3 升级）：
 * - 阴影 elevation：未选 2dp / hint 4dp / 选中 8dp（spring 动画过渡）
 * - 选中上抬 -16dp，平滑 spring 动画（不再瞬切）
 * - 边框颜色 animateColorAsState 平滑过渡（默认灰 → hint 绿 → 选中金）
 * - 圆角放大到 8dp（更现代）
 */
@Composable
private fun CardView(
    card: SerializedCard,
    selected: Boolean,
    hinted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isRed = card.suit == "HEART" || card.suit == "DIAMOND" ||
        (card.suit == "JOKER" && card.rank == "BIG_JOKER")
    val textColor = if (isRed) Color(0xFFE53935) else Color.Black

    val shape = RoundedCornerShape(8.dp)
    val targetBorder = when {
        selected -> GreenTableColors.selectedBorder
        hinted -> GreenTableColors.hintBorder
        else -> GreenTableColors.cardOutline
    }
    val borderColor by animateColorAsState(targetBorder, label = "cardBorder")
    val targetLift = if (selected) (-16).dp else 0.dp
    val lift by animateDpAsState(targetLift, animationSpec = spring(), label = "cardLift")
    val targetElev = when {
        selected -> 8.dp
        hinted -> 4.dp
        else -> 2.dp
    }
    val elev by animateDpAsState(targetElev, label = "cardElev")

    Box(
        modifier = modifier
            .offset(y = lift)
            .shadow(elevation = elev, shape = shape, clip = false)
            .background(GreenTableColors.cardWhite, shape)
            .border(if (hinted || selected) 3.dp else 1.5.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(rankSymbol(card.rank), color = textColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(suitSymbol(card.suit, card.rank), color = textColor, fontSize = 14.sp)
        }
    }
}

/** 三档 LayoutMode 对应的卡片宽×高（dp）。 */
private fun cardSizeFor(mode: LayoutMode): Pair<Dp, Dp> = when (mode) {
    LayoutMode.Compact -> 44.dp to 62.dp
    LayoutMode.Medium -> 56.dp to 80.dp
    LayoutMode.Expanded -> 64.dp to 90.dp
}

/**
 * Compact 模式下手牌区域最大高度。≈ 3 行牌 (62dp) + 选中态行间距 (18dp×2)
 * = 222dp，向下取整到 220dp 留点弹性。超过此高度由 verticalScroll 兜底。
 *
 * 之所以是 220 而不是更大：手机视口高常 640dp，扣 TopBar (52) + TableArea
 * 最小 (~120) + 按钮区 (~110) + padding (~20) = 302dp 占用 → 留给 hand
 * 最多 ~338dp。220dp 留出 100+ dp 空间给 TableArea 收缩弹性。
 */
private val HAND_MAX_HEIGHT_COMPACT: Dp = 220.dp

private fun keyOf(c: SerializedCard) = "${c.suit}|${c.rank}|${c.deckIndex}"

private fun rankSymbol(rank: String): String = when (rank) {
    "THREE" -> "3"
    "FOUR" -> "4"
    "FIVE" -> "5"
    "SIX" -> "6"
    "SEVEN" -> "7"
    "EIGHT" -> "8"
    "NINE" -> "9"
    "TEN" -> "10"
    "JACK" -> "J"
    "QUEEN" -> "Q"
    "KING" -> "K"
    "ACE" -> "A"
    "TWO" -> "2"
    "SMALL_JOKER" -> "小"
    "BIG_JOKER" -> "大"
    else -> rank
}

private fun suitSymbol(suit: String, rank: String): String = when (suit) {
    "SPADE" -> "♠"
    "HEART" -> "♥"
    "CLUB" -> "♣"
    "DIAMOND" -> "♦"
    "JOKER" -> if (rank == "BIG_JOKER") "★" else "☆"
    else -> ""
}

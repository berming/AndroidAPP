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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
 * 游戏中屏幕（Stage 4：完整向 Android 客户端对齐）。
 *
 * 与 Android `activity_game.xml` 信息密度对齐的三段式布局：
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ TopBar：[你 N 张 已收 N] · 红队/蓝队/本轮 · [提示][过牌][出牌] │
 * ├─────────────────────────────────────────────────────────────┤
 * │ 玩家行：5 个紧凑卡片 + **每个下方显示该玩家最近出的牌缩图**     │
 * │   ┌───────┐  ┌───────┐  ┌───────┐  ┌───────┐  ┌───────┐    │
 * │   │玩家A 32│ │玩家B 32│ │电脑12 30│ │电脑13 30│ │电脑14 28│   │
 * │   │ 2♣ 2♥ │ │小王小王│ │K♦K♦K♦K♠│ │2♦2♥2♦2♠│ │7♣7♣7♣7♠..│   │
 * │   └───────┘  └───────┘  └───────┘  └───────┘  └───────┘    │
 * ├─────────────────────────────────────────────────────────────┤
 * │ 桌面区（可空，多用于 round-end 信息）                         │
 * ├─────────────────────────────────────────────────────────────┤
 * │ 我的手牌（FlowRow 密铺多行；卡片紧凑）                        │
 * └─────────────────────────────────────────────────────────────┘
 *
 * 三档 LayoutMode 缩放：卡片大小、玩家头像大小、字号都跟 [LocalLayoutMode] 走。
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
    val mode = LocalLayoutMode.current
    val gs = state.state
    val me = gs.players.find { it.id == state.localSeatIndex }
    val isMyTurn = gs.currentPlayerIndex == state.localSeatIndex
    val cardSize = cardSizeFor(mode)
    val miniCardSize = miniCardSizeFor(mode)

    Column(modifier = Modifier.fillMaxSize().background(GreenTableColors.tableGreen)) {
        // ─── 顶栏：我的信息 + 比分 + 操作按钮 inline ───
        TopBar(
            me = me,
            score = ScoreSummary(gs.teamAScore, gs.teamBScore, currentName = currentPlayerName(gs)),
            isMyTurn = isMyTurn,
            canPass = isMyTurn && gs.lastPlayedGroup != null,
            canPlay = isMyTurn && state.selectedCardIds.isNotEmpty(),
            mode = mode,
            onHint = onHint,
            onPass = onPass,
            onLeave = onLeave,
            onPlay = {
                if (state.selectedCardIds.isEmpty() || me == null) return@TopBar
                val toPlay = me.hand.filter { keyOf(it) in state.selectedCardIds }
                onPlayCards(toPlay)
            },
        )

        // ─── 玩家行：紧凑卡片 + 每个下方最近出牌缩图 ───
        PlayersRow(
            others = gs.players.filter { it.id != state.localSeatIndex },
            currentSeat = gs.currentPlayerIndex,
            perPlayerLastPlay = state.perPlayerLastPlay,
            miniCardSize = miniCardSize,
            mode = mode,
        )

        // ─── 中部：可滚动空白 + 等待提示（对应 Android 中央信息条） ───
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (gs.lastPlayedGroup == null) {
                Text(
                    text = if (gs.consecutivePasses > 0) "等待新一轮 …" else
                           if (isMyTurn) "请出牌" else "等待 ${currentPlayerName(gs) ?: "…"} 出牌",
                    color = GreenTableColors.textMuted,
                    fontSize = 14.sp,
                )
            }
        }

        // ─── 我的手牌：FlowRow 多行密铺 ───
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(GreenTableColors.tableGreenDeep)
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .heightIn(max = handMaxHeightFor(mode))
                .verticalScroll(rememberScrollState()),
        ) {
            HandFlow(
                me = me,
                selected = state.selectedCardIds,
                hinted = state.hintedCardIds,
                cardWidth = cardSize.first,
                cardHeight = cardSize.second,
                onToggle = onToggleSelected,
            )
        }
    }
}

/* ────────────────────────────────────────────────────────────── */
/*  顶栏（"我的信息" + 比分 + 5 操作按钮 inline）                     */
/* ────────────────────────────────────────────────────────────── */

private data class ScoreSummary(val teamA: Int, val teamB: Int, val currentName: String?)

@Composable
private fun TopBar(
    me: SerializedPlayer?,
    score: ScoreSummary,
    isMyTurn: Boolean,
    canPass: Boolean,
    canPlay: Boolean,
    mode: LayoutMode,
    onHint: () -> Unit,
    onPass: () -> Unit,
    onLeave: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GreenTableColors.tableGreenDeep)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 离开按钮（最左）
        OutlinedButton(
            onClick = onLeave,
            modifier = Modifier.height(36.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        ) {
            Text(if (mode.isCompact) "←" else "离开", color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))

        // 我的信息（紧凑）
        if (me != null) {
            Box(
                modifier = Modifier
                    .background(GreenTableColors.warning, RoundedCornerShape(3.dp))
                    .size(8.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (mode.isCompact) "你 ${me.handSize}" else "你 ${me.handSize}张 已收 ${me.collectedScore}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
        }

        // 比分 + 当前轮（中部）
        Text("红队 ${score.teamA}", color = GreenTableColors.teamA, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text("蓝队 ${score.teamB}", color = GreenTableColors.teamB, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (!mode.isCompact) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "当前: ${score.currentName ?: "—"}",
                color = if (isMyTurn) GreenTableColors.brandPrimary else GreenTableColors.textMuted,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.fillMaxWidth().weight(1f))

        // 操作按钮组（最右）
        OutlinedButton(
            onClick = onHint,
            enabled = isMyTurn,
            modifier = Modifier.height(36.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        ) { Text("提示", color = Color.White, fontSize = 12.sp) }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(
            onClick = onPass,
            enabled = canPass,
            modifier = Modifier.height(36.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        ) { Text("过牌", color = Color.White, fontSize = 12.sp) }
        Spacer(Modifier.width(4.dp))
        Button(
            onClick = onPlay,
            enabled = canPlay,
            modifier = Modifier.height(36.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GreenTableColors.brandPrimary,
                contentColor = GreenTableColors.onBrandPrimary,
            ),
        ) { Text("出牌", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}

private fun currentPlayerName(state: SerializedGameState): String? =
    state.players.find { it.id == state.currentPlayerIndex }?.name

/* ────────────────────────────────────────────────────────────── */
/*  玩家行：紧凑卡片 + 最近出牌缩图（Stage 4 核心）                  */
/* ────────────────────────────────────────────────────────────── */

@Composable
private fun PlayersRow(
    others: List<SerializedPlayer>,
    currentSeat: Int,
    perPlayerLastPlay: Map<Int, SerializedCardGroup>,
    miniCardSize: Pair<Dp, Dp>,
    mode: LayoutMode,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        others.sortedBy { it.id }.forEach { p ->
            Box(modifier = Modifier.weight(1f)) {
                PlayerStrip(
                    player = p,
                    isCurrent = p.id == currentSeat,
                    lastPlay = perPlayerLastPlay[p.id],
                    miniCardSize = miniCardSize,
                    mode = mode,
                )
            }
        }
    }
}

@Composable
private fun PlayerStrip(
    player: SerializedPlayer,
    isCurrent: Boolean,
    lastPlay: SerializedCardGroup?,
    miniCardSize: Pair<Dp, Dp>,
    mode: LayoutMode,
) {
    val borderColor by animateColorAsState(
        if (isCurrent) GreenTableColors.selectedBorder else Color.Transparent,
        label = "playerBorder",
    )
    val elev by animateDpAsState(if (isCurrent) 6.dp else 1.dp, label = "playerElev")
    val shape = RoundedCornerShape(8.dp)
    val bgColor = if (player.team == "TEAM_A") GreenTableColors.teamABg else GreenTableColors.teamBBg

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elev, shape, clip = false)
            .background(bgColor, shape)
            .border(2.dp, borderColor, shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        // 头部一行：名 + 张数 + 已收
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (player.team == "TEAM_A") GreenTableColors.teamA else GreenTableColors.teamB,
                        RoundedCornerShape(3.dp),
                    ),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                player.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if (mode.isCompact) 10.sp else 12.sp,
                maxLines = 1,
            )
        }
        Row(modifier = Modifier.padding(top = 2.dp)) {
            Text(
                if (player.hasFinished) "已走完" else "${player.handSize}张",
                color = if (player.hasFinished) GreenTableColors.brandSecondary else GreenTableColors.textMuted,
                fontSize = if (mode.isCompact) 9.sp else 11.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "已收 ${player.collectedScore}",
                color = GreenTableColors.brandPrimary,
                fontSize = if (mode.isCompact) 9.sp else 11.sp,
            )
        }
        // 下方：最近出牌缩图 mini cards
        Spacer(Modifier.height(3.dp))
        if (lastPlay != null && lastPlay.cards.isNotEmpty()) {
            MiniCardRow(cards = lastPlay.cards, cardSize = miniCardSize)
        } else {
            // 占位高度，避免有/无 lastPlay 切换时 layout 跳动
            Spacer(Modifier.height(miniCardSize.second + 2.dp))
        }
    }
}

@Composable
private fun MiniCardRow(cards: List<SerializedCard>, cardSize: Pair<Dp, Dp>) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        cards.forEach { c ->
            MiniCardView(card = c, modifier = Modifier.size(cardSize.first, cardSize.second))
        }
    }
}

@Composable
private fun MiniCardView(card: SerializedCard, modifier: Modifier = Modifier) {
    val isRed = card.suit == "HEART" || card.suit == "DIAMOND" ||
        (card.suit == "JOKER" && card.rank == "BIG_JOKER")
    val textColor = if (isRed) Color(0xFFE53935) else Color.Black
    Box(
        modifier = modifier
            .background(GreenTableColors.cardWhite, RoundedCornerShape(2.dp))
            .border(0.5.dp, GreenTableColors.cardOutline, RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(rankSymbol(card.rank), color = textColor, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            Text(suitSymbol(card.suit, card.rank), color = textColor, fontSize = 8.sp)
        }
    }
}

/* ────────────────────────────────────────────────────────────── */
/*  我的手牌区（FlowRow 多行密铺）                                  */
/* ────────────────────────────────────────────────────────────── */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HandFlow(
    me: SerializedPlayer?,
    selected: Set<String>,
    hinted: Set<String>,
    cardWidth: Dp,
    cardHeight: Dp,
    onToggle: (String) -> Unit,
) {
    if (me == null) {
        Text("等待发牌 …", color = Color.White)
        return
    }
    val hasHighlight = selected.isNotEmpty() || hinted.isNotEmpty()
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(if (hasHighlight) 18.dp else 2.dp),
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
}

/* ────────────────────────────────────────────────────────────── */
/*  CardView（带阴影 + 选中 spring 动画）                           */
/* ────────────────────────────────────────────────────────────── */

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

    val shape = RoundedCornerShape(6.dp)
    val targetBorder = when {
        selected -> GreenTableColors.selectedBorder
        hinted -> GreenTableColors.hintBorder
        else -> GreenTableColors.cardOutline
    }
    val borderColor by animateColorAsState(targetBorder, label = "cardBorder")
    val targetLift = if (selected) (-14).dp else 0.dp
    val lift by animateDpAsState(targetLift, animationSpec = spring(), label = "cardLift")
    val targetElev = when {
        selected -> 6.dp
        hinted -> 3.dp
        else -> 1.dp
    }
    val elev by animateDpAsState(targetElev, label = "cardElev")

    Box(
        modifier = modifier
            .offset(y = lift)
            .shadow(elevation = elev, shape = shape, clip = false)
            .background(GreenTableColors.cardWhite, shape)
            .border(if (hinted || selected) 2.5.dp else 1.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(rankSymbol(card.rank), color = textColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(suitSymbol(card.suit, card.rank), color = textColor, fontSize = 12.sp)
        }
    }
}

/* ────────────────────────────────────────────────────────────── */
/*  尺寸表 + 工具                                                   */
/* ────────────────────────────────────────────────────────────── */

/** 自己手牌卡片宽×高（dp）—— 密铺紧凑设计：Compact 比 Stage 3 更小（38×52）。 */
private fun cardSizeFor(mode: LayoutMode): Pair<Dp, Dp> = when (mode) {
    LayoutMode.Compact -> 38.dp to 52.dp
    LayoutMode.Medium -> 46.dp to 64.dp
    LayoutMode.Expanded -> 54.dp to 76.dp
}

/** 玩家最近出牌缩图卡片宽×高（dp）—— 比手牌更小，仅快速识别 rank+suit。 */
private fun miniCardSizeFor(mode: LayoutMode): Pair<Dp, Dp> = when (mode) {
    LayoutMode.Compact -> 16.dp to 22.dp
    LayoutMode.Medium -> 18.dp to 26.dp
    LayoutMode.Expanded -> 22.dp to 30.dp
}

/** 手牌区域最大高度（超出滚动）。Stage 4 因卡片更小，高度上限可适度放宽。 */
private fun handMaxHeightFor(mode: LayoutMode): Dp = when (mode) {
    LayoutMode.Compact -> 200.dp     // 3-4 行
    LayoutMode.Medium -> 220.dp      // 2-3 行
    LayoutMode.Expanded -> 260.dp    // 2 行
}

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

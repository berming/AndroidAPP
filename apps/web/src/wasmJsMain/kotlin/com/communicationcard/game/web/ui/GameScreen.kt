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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.communicationcard.game.web.storage.GameSpeed
import com.communicationcard.game.web.viewmodel.Screen

/**
 * 游戏中屏幕（Stage F：4 段信息架构对齐 Android 优化版基线）。
 *
 * 与 Android `activity_game.xml` 对齐的四段式布局：
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ ① PlayersRow：5 个对手玩家紧凑卡片（含队伍色 + 已收分 + mini） │
 * ├─────────────────────────────────────────────────────────────┤
 * │ ② CentralPanel（黄描边）：红队:N | 蓝队:N | 本轮:N |          │
 * │    玩家X | 当前最大牌 | 状态/瞬时消息                        │
 * ├─────────────────────────────────────────────────────────────┤
 * │ ③ ActionBar：■ 你 N张 已收 N | 等待/轮到 | [出牌][过牌][提示] │
 * │              [AI 接管(多人)][更多…]                          │
 * ├─────────────────────────────────────────────────────────────┤
 * │ ④ HandArea：分组 + 同点数 30% 重叠（FlowRow 多行密铺）        │
 * └─────────────────────────────────────────────────────────────┘
 *
 * 三档 LayoutMode 缩放：卡片大小、字号、按钮密度都跟 [LocalLayoutMode] 走，
 * **信息层级在三档间不变**（约束 7）。
 *
 * 按钮顺序按附录 A：出牌 / 过牌 / 提示 / AI 接管(仅多人) / 更多…
 * "记录""暂离""设置昵称""AI 出牌速度"全部进"更多…"菜单（不退出本屏）。
 */
@Composable
fun GameScreen(
    state: Screen.Game,
    onPlayCards: (List<SerializedCard>) -> Unit,
    onPass: () -> Unit,
    onHint: () -> Unit,
    onToggleSelected: (String) -> Unit,
    onLeave: () -> Unit,
    /** 多人模式传非 null；单机模式 null = 不显示托管按钮（feature_spec G34/G35） */
    imAiSubstitute: Boolean? = null,
    onToggleAITakeover: (() -> Unit)? = null,
    /** "更多…" 菜单 → 设置昵称 子动作 */
    nickname: String = "",
    onUpdateNickname: (String) -> Unit = {},
    /** "更多…" 菜单 → AI 出牌速度 子动作 */
    gameSpeed: GameSpeed = GameSpeed.NORMAL,
    onUpdateGameSpeed: (GameSpeed) -> Unit = {},
) {
    val mode = LocalLayoutMode.current
    val gs = state.state
    val me = gs.players.find { it.id == state.localSeatIndex }
    val isMyTurn = gs.currentPlayerIndex == state.localSeatIndex
    val cardSize = cardSizeFor(mode)
    val miniCardSize = miniCardSizeFor(mode)
    val centralCardSize = centralCardSizeFor(mode)

    // "更多…" 菜单状态：默认关；菜单内点子项后切到对应 sub-dialog
    var moreDialog by remember { mutableStateOf(MoreDialogState.None) }

    Column(modifier = Modifier.fillMaxSize().background(GreenTableColors.tableGreen)) {
        // ─── ① 玩家行：紧凑卡片 + 每个下方最近出牌缩图 ───
        PlayersRow(
            others = gs.players.filter { it.id != state.localSeatIndex },
            currentSeat = gs.currentPlayerIndex,
            perPlayerLastPlay = state.perPlayerLastPlay,
            miniCardSize = miniCardSize,
            mode = mode,
        )

        // ─── ② 中央面板：黄描边 + 分数/最大牌/瞬时消息 ───
        CentralPanel(
            state = gs,
            isMyTurn = isMyTurn,
            transientMessage = state.transientMessage,
            currentPlayerName = currentPlayerName(gs),
            cardSize = centralCardSize,
            mode = mode,
        )

        // ─── ③ 状态行 + 操作按钮组（按附录 A 顺序） ───
        ActionBar(
            me = me,
            isMyTurn = isMyTurn,
            currentPlayerName = currentPlayerName(gs),
            consecutivePasses = gs.consecutivePasses,
            canPass = isMyTurn && gs.lastPlayedGroup != null,
            canPlay = isMyTurn && state.selectedCardIds.isNotEmpty() && imAiSubstitute != true,
            mode = mode,
            onPlay = {
                if (state.selectedCardIds.isEmpty() || me == null) return@ActionBar
                val toPlay = me.hand.filter { keyOf(it) in state.selectedCardIds }
                onPlayCards(toPlay)
            },
            onPass = onPass,
            onHint = onHint,
            onMore = { moreDialog = MoreDialogState.Menu },
            imAiSubstitute = imAiSubstitute,
            onToggleAITakeover = onToggleAITakeover,
        )

        // ─── ④ 我的手牌：分组 + 30% 重叠 + FlowRow 密铺 ───
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(GreenTableColors.handAreaBg)
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

    // 更多… 菜单 + 子 dialogs
    when (moreDialog) {
        MoreDialogState.None -> Unit
        MoreDialogState.Menu -> MoreMenuDialog(
            isMultiplayer = imAiSubstitute != null,
            imAiSubstitute = imAiSubstitute == true,
            onDismiss = { moreDialog = MoreDialogState.None },
            onShowLog = { moreDialog = MoreDialogState.Log },
            onEditNickname = { moreDialog = MoreDialogState.Nickname },
            onChangeAiSpeed = { moreDialog = MoreDialogState.AiSpeed },
            onLeave = {
                moreDialog = MoreDialogState.None
                onLeave()
            },
            onToggleAfk = {
                moreDialog = MoreDialogState.None
                onToggleAITakeover?.invoke()
            },
        )
        MoreDialogState.Log -> LogViewerDialog(
            playLog = state.playLog,
            onDismiss = { moreDialog = MoreDialogState.Menu },
            cardSize = miniCardSize,
        )
        MoreDialogState.Nickname -> NicknameDialog(
            current = nickname,
            onSave = { newName ->
                onUpdateNickname(newName)
                moreDialog = MoreDialogState.Menu
            },
            onDismiss = { moreDialog = MoreDialogState.Menu },
        )
        MoreDialogState.AiSpeed -> AISpeedDialog(
            current = gameSpeed,
            onSelect = { sp ->
                onUpdateGameSpeed(sp)
                moreDialog = MoreDialogState.Menu
            },
            onDismiss = { moreDialog = MoreDialogState.Menu },
        )
    }
}

private enum class MoreDialogState { None, Menu, Log, Nickname, AiSpeed }

/* ────────────────────────────────────────────────────────────── */
/*  ② 中央面板（黄描边；分数 + 当前最大牌 + 瞬时消息）              */
/* ────────────────────────────────────────────────────────────── */

@Composable
private fun CentralPanel(
    state: SerializedGameState,
    isMyTurn: Boolean,
    transientMessage: Screen.Game.TransientMessage?,
    currentPlayerName: String?,
    cardSize: Pair<Dp, Dp>,
    mode: LayoutMode,
) {
    val lastGroup = state.lastPlayedGroup
    val owner = state.lastPlayerId?.let { id -> state.players.find { it.id == id } }
    val ownerColor = teamColorFor(owner?.team)

    // 状态文字（中央右侧 / 瞬时消息覆盖）
    val statusText = when {
        transientMessage != null -> transientMessage.text
        state.consecutivePasses > 0 && lastGroup == null -> "等待新一轮 …"
        isMyTurn -> "请出牌"
        currentPlayerName != null -> "等待 $currentPlayerName 出牌"
        else -> ""
    }
    val statusColor = if (transientMessage != null) GreenTableColors.brandPrimary
        else if (isMyTurn) GreenTableColors.brandPrimary
        else GreenTableColors.textMuted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .border(1.5.dp, GreenTableColors.goldBorder, RoundedCornerShape(6.dp))
            .background(GreenTableColors.centralPanelBg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 红队 / 蓝队 / 本轮 — 与 Android 14sp bold 对齐
        Text("红队:${state.teamAScore}分", color = GreenTableColors.teamA,
            fontSize = panelFontFor(mode), fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(if (mode.isCompact) 6.dp else 12.dp))
        Text("蓝队:${state.teamBScore}分", color = GreenTableColors.teamB,
            fontSize = panelFontFor(mode), fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        VerticalDivider()
        Spacer(Modifier.width(8.dp))
        Text("本轮:${state.currentRoundScore}分", color = GreenTableColors.brandPrimary,
            fontSize = panelFontFor(mode), fontWeight = FontWeight.Bold)

        // 当前领先玩家 + 最大牌（lastPlayedGroup 非空时）
        if (lastGroup != null && owner != null) {
            Spacer(Modifier.width(8.dp))
            VerticalDivider()
            Spacer(Modifier.width(8.dp))
            Text("${owner.name}:", color = ownerColor,
                fontSize = panelFontFor(mode), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            MiniCardRow(cards = lastGroup.cards, cardSize = cardSize)
        }

        Spacer(Modifier.fillMaxWidth().weight(1f))

        // 状态文本 / 瞬时消息（中央右侧；瞬时消息时半透明黑底 + 黄字）
        if (statusText.isNotEmpty()) {
            if (transientMessage != null) {
                Box(
                    modifier = Modifier
                        .background(GreenTableColors.transientBg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(statusText, color = statusColor, fontSize = panelFontFor(mode), fontWeight = FontWeight.Bold)
                }
            } else {
                Text(statusText, color = statusColor, fontSize = panelFontFor(mode), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(16.dp)
            .background(Color(0x40FFFFFF)),
    )
}

/* ────────────────────────────────────────────────────────────── */
/*  ③ 状态行 + 操作按钮组（按附录 A 顺序）                          */
/* ────────────────────────────────────────────────────────────── */

@Composable
private fun ActionBar(
    me: SerializedPlayer?,
    isMyTurn: Boolean,
    currentPlayerName: String?,
    consecutivePasses: Int,
    canPass: Boolean,
    canPlay: Boolean,
    mode: LayoutMode,
    onPlay: () -> Unit,
    onPass: () -> Unit,
    onHint: () -> Unit,
    onMore: () -> Unit,
    imAiSubstitute: Boolean? = null,
    onToggleAITakeover: (() -> Unit)? = null,
) {
    val statusText = when {
        consecutivePasses > 0 && !isMyTurn -> "等待新一轮 …"
        isMyTurn -> "轮到你出牌"
        currentPlayerName != null -> "等待 $currentPlayerName 出牌"
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ■ 你 N张 已收 N分（左侧；与 Android playerInfoRow 对齐）
        if (me != null) {
            val myColor = teamColorFor(me.team)
            Box(modifier = Modifier.size(10.dp).background(myColor))
            Spacer(Modifier.width(4.dp))
            Text("你", color = Color.White, fontSize = actionFontFor(mode), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("${me.handSize}张", color = GreenTableColors.textMuted, fontSize = actionFontFor(mode))
            Spacer(Modifier.width(8.dp))
            Text("已收 ${me.collectedScore}分", color = GreenTableColors.brandPrimary, fontSize = actionFontFor(mode))
        }

        // 状态提示居中（黄色加粗，对齐 Android tvGameStatus）
        Spacer(Modifier.fillMaxWidth().weight(1f))
        if (statusText.isNotEmpty()) {
            Text(statusText, color = GreenTableColors.brandPrimary,
                fontSize = actionFontFor(mode), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.fillMaxWidth().weight(1f))

        // 按钮组（附录 A 顺序：出牌 / 过牌 / 提示 / AI 接管 / 更多…）
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
        Spacer(Modifier.width(4.dp))
        OutlinedButton(
            onClick = onPass,
            enabled = canPass,
            modifier = Modifier.height(36.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        ) { Text("过牌", color = Color.White, fontSize = 12.sp) }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(
            onClick = onHint,
            enabled = isMyTurn,
            modifier = Modifier.height(36.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        ) { Text("提示", color = Color.White, fontSize = 12.sp) }

        // AI 接管 / 我回来了（仅多人）
        // 用 local val 固化非空，避开 wasmJs/Compose 智能转换 + lambda 捕获组合
        // 触发的 psi2ir backend NPE（PR #53 第二轮 CI 失败的根因）
        val takeoverCb = onToggleAITakeover
        val isSub = imAiSubstitute
        if (isSub != null && takeoverCb != null) {
            Spacer(Modifier.width(4.dp))
            OutlinedButton(
                onClick = takeoverCb,
                modifier = Modifier.height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    if (isSub) "我回来了" else "AI 接管",
                    color = Color.White,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.width(4.dp))
        OutlinedButton(
            onClick = onMore,
            modifier = Modifier.height(36.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        ) { Text("更多…", color = Color.White, fontSize = 12.sp) }
    }
}

private fun currentPlayerName(state: SerializedGameState): String? =
    state.players.find { it.id == state.currentPlayerIndex }?.name

private fun teamColorFor(team: String?): Color = when (team) {
    "TEAM_A" -> GreenTableColors.teamA
    "TEAM_B" -> GreenTableColors.teamB
    else -> Color.Gray
}

/* ────────────────────────────────────────────────────────────── */
/*  ① 玩家行：紧凑卡片 + 最近出牌缩图                                */
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
    // 与 Android view_player_with_cards.xml 对齐：半透明黑背景 + 队伍色细条点缀
    val teamAccent = teamColorFor(player.team)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elev, shape, clip = false)
            .background(GreenTableColors.playerStripBg, shape)
            .border(2.dp, borderColor, shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(teamAccent, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                player.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if (mode.isCompact) 11.sp else 13.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (player.hasFinished) "已走完" else "${player.handSize}张",
                color = if (player.hasFinished) GreenTableColors.brandSecondary else GreenTableColors.textMuted,
                fontSize = if (mode.isCompact) 10.sp else 12.sp,
            )
            if (!mode.isCompact) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "已收:${player.collectedScore}分",
                    color = GreenTableColors.brandPrimary,
                    fontSize = 12.sp,
                )
            }
        }
        // 下方：最近出牌缩图
        Spacer(Modifier.height(3.dp))
        if (lastPlay != null && lastPlay.cards.isNotEmpty()) {
            MiniCardRow(cards = lastPlay.cards, cardSize = miniCardSize)
        } else {
            Spacer(Modifier.height(miniCardSize.second + 2.dp))
        }
    }
}

@Composable
internal fun MiniCardRow(cards: List<SerializedCard>, cardSize: Pair<Dp, Dp>) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        cards.forEach { c ->
            MiniCardView(card = c, modifier = Modifier.size(cardSize.first, cardSize.second))
        }
    }
}

@Composable
internal fun MiniCardView(card: SerializedCard, modifier: Modifier = Modifier) {
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
/*  ④ 我的手牌区：分组 + 30% 重叠 + FlowRow 多行密铺                */
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
    // 排序 + 分组：按 rank 升序，同 rank 视为一组（30% overlap）；
    // 4+ 张同 rank（炸弹）置顶（先于其它）让用户一眼能看到。
    val sortedGroups = sortAndGroupHand(me.hand)
    val overlap = -(cardWidth.value * 0.3f).dp

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(if (hasHighlight) 18.dp else 4.dp),
    ) {
        sortedGroups.forEach { group ->
            // 单卡：直接 CardView；多卡同 rank：Row + spacedBy(负值) 实现 30% 重叠
            if (group.size == 1) {
                val card = group[0]
                val key = keyOf(card)
                CardView(
                    card = card,
                    selected = key in selected,
                    hinted = key in hinted,
                    modifier = Modifier
                        .size(cardWidth, cardHeight)
                        .clickable { onToggle(key) },
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(overlap)) {
                    group.forEach { card ->
                        val key = keyOf(card)
                        CardView(
                            card = card,
                            selected = key in selected,
                            hinted = key in hinted,
                            isBomb = group.size >= 4,
                            modifier = Modifier
                                .size(cardWidth, cardHeight)
                                .clickable { onToggle(key) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 把手牌排序 + 按 rank 分组：
 * - 4+ 张同 rank（炸弹）置顶（按 rank 升序内部排），让用户先看到能炸的牌
 * - 然后剩余（单牌 / 对子 / 三条）按 rank 升序排列
 * - 同 rank 内按 deckIndex + suit 稳定排序，避免选中 / 反序列化时抖动
 */
internal fun sortAndGroupHand(hand: List<SerializedCard>): List<List<SerializedCard>> {
    val byRank: Map<String, List<SerializedCard>> = hand
        .sortedWith(compareBy({ it.deckIndex }, { it.suit }))
        .groupBy { it.rank }
    val (bombs, others) = byRank.entries.partition { it.value.size >= 4 }
    val sortedBombs = bombs.sortedBy { rankValue(it.key) }
    val sortedOthers = others.sortedBy { rankValue(it.key) }
    return (sortedBombs + sortedOthers).map { it.value }
}

/* ────────────────────────────────────────────────────────────── */
/*  CardView（带阴影 + 选中 spring 动画 + 炸弹高亮边）              */
/* ────────────────────────────────────────────────────────────── */

@Composable
internal fun CardView(
    card: SerializedCard,
    selected: Boolean,
    hinted: Boolean = false,
    isBomb: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isRed = card.suit == "HEART" || card.suit == "DIAMOND" ||
        (card.suit == "JOKER" && card.rank == "BIG_JOKER")
    val textColor = if (isRed) Color(0xFFE53935) else Color.Black

    val shape = RoundedCornerShape(6.dp)
    val targetBorder = when {
        selected -> GreenTableColors.selectedBorder
        hinted -> GreenTableColors.hintBorder
        isBomb -> GreenTableColors.bombBorder
        else -> GreenTableColors.cardOutline
    }
    val borderColor by animateColorAsState(targetBorder, label = "cardBorder")
    val targetLift = if (selected) (-14).dp else 0.dp
    val lift by animateDpAsState(targetLift, animationSpec = spring(), label = "cardLift")
    val targetElev = when {
        selected -> 6.dp
        hinted -> 3.dp
        isBomb -> 2.dp
        else -> 1.dp
    }
    val elev by animateDpAsState(targetElev, label = "cardElev")

    Box(
        modifier = modifier
            .offset(y = lift)
            .shadow(elevation = elev, shape = shape, clip = false)
            .background(GreenTableColors.cardWhite, shape)
            .border(if (hinted || selected || isBomb) 2.5.dp else 1.dp, borderColor, shape),
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

private fun cardSizeFor(mode: LayoutMode): Pair<Dp, Dp> = when (mode) {
    LayoutMode.Compact -> 38.dp to 52.dp
    LayoutMode.Medium -> 46.dp to 64.dp
    LayoutMode.Expanded -> 54.dp to 76.dp
}

private fun miniCardSizeFor(mode: LayoutMode): Pair<Dp, Dp> = when (mode) {
    LayoutMode.Compact -> 16.dp to 22.dp
    LayoutMode.Medium -> 18.dp to 26.dp
    LayoutMode.Expanded -> 22.dp to 30.dp
}

/** 中央面板里"当前最大牌"用的卡片尺寸 —— 比缩图大些便于辨识（Android currentWinningCards）。 */
private fun centralCardSizeFor(mode: LayoutMode): Pair<Dp, Dp> = when (mode) {
    LayoutMode.Compact -> 22.dp to 30.dp
    LayoutMode.Medium -> 26.dp to 36.dp
    LayoutMode.Expanded -> 30.dp to 42.dp
}

private fun handMaxHeightFor(mode: LayoutMode): Dp = when (mode) {
    LayoutMode.Compact -> 200.dp
    LayoutMode.Medium -> 220.dp
    LayoutMode.Expanded -> 260.dp
}

private fun panelFontFor(mode: LayoutMode) = when (mode) {
    LayoutMode.Compact -> 12.sp
    LayoutMode.Medium -> 13.sp
    LayoutMode.Expanded -> 14.sp
}

private fun actionFontFor(mode: LayoutMode) = when (mode) {
    LayoutMode.Compact -> 12.sp
    LayoutMode.Medium -> 13.sp
    LayoutMode.Expanded -> 14.sp
}

internal fun keyOf(c: SerializedCard) = "${c.suit}|${c.rank}|${c.deckIndex}"

internal fun rankValue(rank: String): Int = when (rank) {
    "THREE" -> 3
    "FOUR" -> 4
    "FIVE" -> 5
    "SIX" -> 6
    "SEVEN" -> 7
    "EIGHT" -> 8
    "NINE" -> 9
    "TEN" -> 10
    "JACK" -> 11
    "QUEEN" -> 12
    "KING" -> 13
    "ACE" -> 14
    "TWO" -> 15
    "SMALL_JOKER" -> 16
    "BIG_JOKER" -> 17
    else -> 0
}

internal fun rankSymbol(rank: String): String = when (rank) {
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

internal fun suitSymbol(suit: String, rank: String): String = when (suit) {
    "SPADE" -> "♠"
    "HEART" -> "♥"
    "CLUB" -> "♣"
    "DIAMOND" -> "♦"
    "JOKER" -> if (rank == "BIG_JOKER") "★" else "☆"
    else -> ""
}

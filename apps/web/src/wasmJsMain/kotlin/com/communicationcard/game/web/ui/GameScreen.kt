package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.communicationcard.game.network.SerializedCard
import com.communicationcard.game.network.SerializedCardGroup
import com.communicationcard.game.network.SerializedPlayer
import com.communicationcard.game.web.viewmodel.Screen

@Composable
fun GameScreen(
    state: Screen.Game,
    onPlayCards: (List<SerializedCard>) -> Unit,
    onPass: () -> Unit,
    onHint: () -> Unit,
    onToggleSelected: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val me = state.state.players.find { it.id == state.localSeatIndex }
    val isMyTurn = state.state.currentPlayerIndex == state.localSeatIndex
    val selected = state.selectedCardIds
    val hinted = state.hintedCardIds

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
        // 顶栏：分数 + 当前轮 + 离开
        TopBar(state = state.state, onLeave = onLeave)

        // 桌面区：其它玩家头像 + 上一手
        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
            TableArea(state = state.state, localSeatIndex = state.localSeatIndex)
        }

        // 我的手牌
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF0E3812)).padding(8.dp)) {
            HandRow(
                me = me,
                selected = selected,
                hinted = hinted,
                onToggle = onToggleSelected,
            )

            Spacer(Modifier.height(8.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onHint,
                    enabled = isMyTurn,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("提示", color = Color.White) }
                OutlinedButton(
                    onClick = onPass,
                    enabled = isMyTurn && state.state.lastPlayedGroup != null,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("过牌", color = Color.White) }
                Button(
                    onClick = {
                        if (selected.isEmpty() || me == null) return@Button
                        val toPlay = me.hand.filter { keyOf(it) in selected }
                        onPlayCards(toPlay)
                    },
                    enabled = isMyTurn && selected.isNotEmpty(),
                    modifier = Modifier.weight(1.4f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFC107),
                        contentColor = Color.Black,
                    ),
                ) { Text("出牌", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun TopBar(
    state: com.communicationcard.game.network.SerializedGameState,
    onLeave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0E3812)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onLeave) { Text("离开", color = Color.White) }
        Spacer(Modifier.width(12.dp))
        ScoreChip(label = "A 队", score = state.teamAScore, color = Color(0xFFEF5350))
        Spacer(Modifier.width(12.dp))
        ScoreChip(label = "B 队", score = state.teamBScore, color = Color(0xFF42A5F5))
        Spacer(Modifier.fillMaxWidth().weight(1f))
        val current = state.players.find { it.id == state.currentPlayerIndex }
        Text(
            text = current?.let { "当前: ${it.name}" } ?: "—",
            color = Color.White,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun ScoreChip(label: String, score: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, shape = RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(4.dp))
        Text("$label: $score", color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TableArea(
    state: com.communicationcard.game.network.SerializedGameState,
    localSeatIndex: Int,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 其他玩家
        val others = state.players.filter { it.id != localSeatIndex }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            others.forEach { p -> OtherPlayerCell(player = p, isCurrent = p.id == state.currentPlayerIndex) }
        }

        Spacer(Modifier.height(8.dp))

        // 上一手出牌
        val last = state.lastPlayedGroup
        if (last != null) {
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .background(Color(0xFF2E7D32), RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                LastPlayedRow(group = last, owner = state.players.find { it.id == state.lastPlayerId })
            }
        } else {
            Text(
                text = if (state.consecutivePasses > 0) "等待新一轮 …" else "请出牌",
                color = Color(0xFFE8F5E9),
            )
        }
    }
}

@Composable
private fun OtherPlayerCell(player: SerializedPlayer, isCurrent: Boolean) {
    val borderColor = if (isCurrent) Color(0xFFFFC107) else Color.Transparent
    Card(
        colors = CardDefaults.cardColors(containerColor = if (player.team == "TEAM_A") Color(0xFF5D2A2A) else Color(0xFF22416A)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(4.dp).border(2.dp, borderColor, RoundedCornerShape(8.dp)),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(player.name, color = Color.White, fontWeight = FontWeight.Bold)
            Text("剩 ${player.handSize} 张", color = Color(0xFFE8F5E9), fontSize = 12.sp)
            Text("已收 ${player.collectedScore}", color = Color(0xFFFFC107), fontSize = 12.sp)
        }
    }
}

@Composable
private fun LastPlayedRow(group: SerializedCardGroup, owner: SerializedPlayer?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = owner?.let { "${it.name} 出: " } ?: "",
            color = Color(0xFFE8F5E9),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            group.cards.forEach { c -> CardView(card = c, selected = false, modifier = Modifier.size(48.dp, 64.dp)) }
        }
    }
}

@Composable
private fun HandRow(
    me: SerializedPlayer?,
    selected: Set<String>,
    hinted: Set<String>,
    onToggle: (String) -> Unit,
) {
    if (me == null) {
        Text("等待发牌 …", color = Color.White)
        return
    }
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
                    .size(56.dp, 80.dp)
                    .clickable { onToggle(key) },
            )
        }
    }
}

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
    val offset = if (selected) -16.dp else 0.dp
    // 选中：金黄高亮 / 仅提示：绿色微光提示 / 未选：默认深灰
    val borderColor = when {
        selected -> Color(0xFFFFC107)
        hinted -> Color(0xFF66BB6A)
        else -> Color(0xFF424242)
    }
    Box(
        modifier = modifier
            .offset(y = offset)
            .background(Color.White, RoundedCornerShape(6.dp))
            .border(if (hinted || selected) 3.dp else 2.dp, borderColor, RoundedCornerShape(6.dp)),
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

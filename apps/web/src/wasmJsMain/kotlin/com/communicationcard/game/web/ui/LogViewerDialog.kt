package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.communicationcard.game.web.viewmodel.Screen

/**
 * 查看出牌记录弹层（"更多…"→ 查看出牌记录）。
 *
 * 数据来源：[Screen.Game.playLog]，由 ViewModel 订阅 GameEvent stream 累积。
 * 列表倒序显示（最新在最上）：玩家名 + 队伍色 + 操作（出/过/抢得本轮）+ 牌缩图。
 */
@Composable
fun LogViewerDialog(
    playLog: List<Screen.Game.PlayLogEntry>,
    onDismiss: () -> Unit,
    cardSize: Pair<Dp, Dp>,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 560.dp)
                .background(GreenTableColors.tableGreenLight, RoundedCornerShape(12.dp))
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "出牌记录",
                        color = GreenTableColors.brandPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.fillMaxWidth().weight(1f))
                    Text(
                        "${playLog.size} 条",
                        color = GreenTableColors.textMuted,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (playLog.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "还没有出牌记录",
                            color = GreenTableColors.textMuted,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(playLog.asReversed()) { entry ->
                            LogEntryRow(entry, cardSize)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("关闭", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: Screen.Game.PlayLogEntry, cardSize: Pair<Dp, Dp>) {
    val teamColor = when (entry.team) {
        "TEAM_A" -> GreenTableColors.teamA
        "TEAM_B" -> GreenTableColors.teamB
        else -> Color.Gray
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GreenTableColors.tableGreenAccent, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).background(teamColor, RoundedCornerShape(2.dp)))
        Spacer(Modifier.padding(start = 6.dp))
        Text(
            entry.playerName,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Spacer(Modifier.padding(start = 8.dp))
        Text(
            entry.text,
            color = GreenTableColors.textMuted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.fillMaxWidth().weight(1f))
        if (entry.cards.isNotEmpty()) {
            MiniCardRow(cards = entry.cards, cardSize = cardSize)
        }
    }
}

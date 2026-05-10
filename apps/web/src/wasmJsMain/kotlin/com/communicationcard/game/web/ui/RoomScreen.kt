package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import com.communicationcard.game.network.RoomPlayer
import com.communicationcard.game.web.viewmodel.Screen

@Composable
fun RoomScreen(
    state: Screen.Room,
    onToggleReady: () -> Unit,
    onStartGame: () -> Unit,
    onAddAi: () -> Unit,
    onLeave: () -> Unit,
) {
    val isHost = state.room.hostId == state.localPlayerId
    val allReadyExceptHost = state.room.players
        .filter { it.id != state.room.hostId }
        .all { it.isReady || it.isAI }
    val canAddAi = isHost && state.room.players.size < state.room.maxPlayers

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
        Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onLeave) { Text("离开房间", color = Color.White) }
                Spacer(Modifier.fillMaxWidth().weight(1f))
                Text(
                    text = if (state.room.roomName.isBlank()) "房间 ${state.room.roomCode}" else state.room.roomName,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.fillMaxWidth().weight(1f))
                Text("码: ${state.room.roomCode}", color = Color(0xFFFFC107), fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "玩家 ${state.room.players.size} / ${state.room.maxPlayers}",
                color = Color(0xFFE8F5E9),
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(state.room.players.sortedBy { it.seatIndex }) { player ->
                    PlayerRow(player = player, isMe = player.id == state.localPlayerId, isHost = player.id == state.room.hostId)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 添加 AI（仅房主可见，且房间未满时启用）—— 与 Android RoomActivity 对齐
            if (isHost) {
                OutlinedButton(
                    onClick = onAddAi,
                    enabled = canAddAi,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                ) {
                    Text(
                        if (canAddAi) "+ 添加一个 AI 玩家" else "房间已满",
                        color = if (canAddAi) Color.White else Color(0xFFB0BEC5),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // 准备按钮 / 开始游戏按钮
            if (isHost) {
                Button(
                    onClick = onStartGame,
                    enabled = allReadyExceptHost && state.room.players.size >= 2,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFC107),
                        contentColor = Color.Black,
                    ),
                ) {
                    Text(
                        if (allReadyExceptHost) "开始游戏" else "等待其他玩家准备…",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Button(
                    onClick = onToggleReady,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isReady) Color(0xFF66BB6A) else Color(0xFFFFC107),
                        contentColor = Color.Black,
                    ),
                ) {
                    Text(
                        if (state.isReady) "已准备 · 点击取消" else "我准备好了",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerRow(player: RoomPlayer, isMe: Boolean, isHost: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isMe) Color(0xFF43A047) else Color(0xFF388E3C),
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("座位 ${player.seatIndex + 1}", color = Color(0xFFE8F5E9), modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        player.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    if (isHost) Text(" 房主", color = Color(0xFFFFC107), fontSize = 12.sp)
                    if (player.isAI) Text(" AI", color = Color(0xFFB2DFDB), fontSize = 12.sp)
                    if (isMe) Text(" (我)", color = Color(0xFFFFC107), fontSize = 12.sp)
                }
                player.team?.let {
                    Text(
                        text = if (it == "TEAM_A") "A 队" else "B 队",
                        color = Color(0xFFE8F5E9),
                        fontSize = 12.sp,
                    )
                }
            }
            ReadyBadge(player)
        }
    }
}

@Composable
private fun ReadyBadge(player: RoomPlayer) {
    val (color, label) = when {
        !player.isConnected -> Color(0xFF9E9E9E) to "离线"
        player.isAI -> Color(0xFF66BB6A) to "AI"
        player.isReady -> Color(0xFF66BB6A) to "已准备"
        else -> Color(0xFFFFC107) to "未准备"
    }
    Box(
        modifier = Modifier
            .background(color, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

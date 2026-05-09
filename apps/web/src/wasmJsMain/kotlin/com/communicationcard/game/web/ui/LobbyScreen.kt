package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.communicationcard.game.network.RoomInfo
import com.communicationcard.game.network.RoomStatus
import com.communicationcard.game.web.net.WebSocketTransport
import com.communicationcard.game.web.viewmodel.Screen

@Composable
fun LobbyScreen(
    state: Screen.Lobby,
    onCreateRoom: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onSetNickname: (String) -> Unit,
    onSetServerUrl: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("返回", color = Color.White) }
                Spacer(Modifier.fillMaxWidth().weight(1f))
                Text("联网大厅", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.fillMaxWidth().weight(1f))
                ConnectionDot(state.connectionState)
            }

            Spacer(Modifier.height(16.dp))

            // 服务器 + 昵称表单
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = state.serverUrl,
                        onValueChange = onSetServerUrl,
                        label = { Text("服务器地址 (ws://...)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.nickname,
                        onValueChange = onSetNickname,
                        label = { Text("昵称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    when (state.connectionState) {
                        WebSocketTransport.State.Disconnected,
                        WebSocketTransport.State.Error -> Button(
                            onClick = onConnect,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text("连接服务器")
                        }
                        WebSocketTransport.State.Connecting -> Text(
                            "正在连接 …",
                            color = Color(0xFFB2DFDB),
                        )
                        WebSocketTransport.State.Connected -> {
                            Row {
                                Button(
                                    onClick = onCreateRoom,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFFC107),
                                        contentColor = Color.Black,
                                    ),
                                    modifier = Modifier.weight(1f).height(48.dp),
                                ) { Text("创建房间", fontWeight = FontWeight.Bold) }
                                Spacer(Modifier.height(0.dp))
                                Spacer(modifier = Modifier.padding(start = 8.dp))
                                OutlinedButton(
                                    onClick = onRefresh,
                                    modifier = Modifier.height(48.dp),
                                ) { Text("刷新", color = Color.White) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 房间码加入
            JoinByCodeRow(onJoinRoom = onJoinRoom, enabled = state.connectionState == WebSocketTransport.State.Connected)

            Spacer(Modifier.height(16.dp))

            // 房间列表
            Text("公开房间", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (state.rooms.isEmpty()) {
                Text("暂无开放房间，点上方“创建房间”开一桌吧", color = Color(0xFFB2DFDB))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rooms) { room ->
                        RoomCard(room = room, onJoin = { onJoinRoom(room.roomCode) })
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinByCodeRow(onJoinRoom: (String) -> Unit, enabled: Boolean) {
    var code by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = code,
            onValueChange = { code = it.uppercase().take(6) },
            label = { Text("房间码") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = enabled,
        )
        Spacer(Modifier.padding(start = 8.dp))
        Button(
            onClick = { if (code.isNotBlank()) onJoinRoom(code) },
            enabled = enabled && code.isNotBlank(),
            modifier = Modifier.height(56.dp),
        ) { Text("加入") }
    }
}

@Composable
private fun RoomCard(room: RoomInfo, onJoin: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF388E3C)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable { onJoin() },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (room.roomName.isBlank()) "房间 ${room.roomCode}" else room.roomName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${room.players.size}/${room.maxPlayers} · ${room.status.displayName()}",
                    color = Color(0xFFE8F5E9),
                    fontSize = 13.sp,
                )
            }
            Text(room.roomCode, color = Color(0xFFFFC107), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun ConnectionDot(state: WebSocketTransport.State) {
    val (color, label) = when (state) {
        WebSocketTransport.State.Disconnected -> Color(0xFF9E9E9E) to "未连接"
        WebSocketTransport.State.Connecting -> Color(0xFFFFC107) to "连接中"
        WebSocketTransport.State.Connected -> Color(0xFF4CAF50) to "已连接"
        WebSocketTransport.State.Error -> Color(0xFFE53935) to "错误"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .height(10.dp)
                .background(color, shape = RoundedCornerShape(5.dp))
                .padding(horizontal = 5.dp),
        )
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

private fun RoomStatus.displayName(): String = when (this) {
    RoomStatus.WAITING -> "等待中"
    RoomStatus.STARTING -> "准备开始"
    RoomStatus.IN_GAME -> "进行中"
    RoomStatus.FINISHED -> "已结束"
}

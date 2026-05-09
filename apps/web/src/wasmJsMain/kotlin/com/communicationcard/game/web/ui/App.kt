package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.communicationcard.game.web.viewmodel.AppViewModel
import com.communicationcard.game.web.viewmodel.Screen

/**
 * 沟通牌 · Web 客户端根 Composable
 *
 * 屏幕状态机由 [AppViewModel] 管理；这里只负责按当前 Screen 渲染对应页面。
 */
@Composable
fun App() {
    val vm = remember { AppViewModel() }
    val screen by vm.screen.collectAsState()

    MaterialTheme(colorScheme = greenTableScheme()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF1B5E20),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
                when (val s = screen) {
                    is Screen.Home -> HomeScreen(
                        onSinglePlayer = { vm.startSinglePlayer() },
                        onMultiplayer = { vm.startMultiplayer() },
                    )
                    is Screen.Lobby -> LobbyScreen(
                        state = s,
                        onCreateRoom = vm::createRoom,
                        onJoinRoom = vm::joinRoom,
                        onRefresh = vm::refreshRooms,
                        onBack = vm::goHome,
                        onConnect = vm::connectServer,
                        onSetNickname = vm::setNickname,
                        onSetServerUrl = vm::setServerUrl,
                    )
                    is Screen.Room -> RoomScreen(
                        state = s,
                        onToggleReady = vm::toggleReady,
                        onStartGame = vm::startGame,
                        onLeave = vm::leaveRoom,
                    )
                    is Screen.Game -> GameScreen(
                        state = s,
                        onPlayCards = vm::playCards,
                        onPass = vm::pass,
                        onHint = vm::hint,
                        onLeave = vm::leaveGame,
                    )
                    is Screen.Settlement -> SettlementScreen(
                        state = s,
                        onPlayAgain = vm::playAgain,
                        onBackToLobby = vm::goHome,
                    )
                }
            }
        }
    }
}

private fun greenTableScheme() = darkColorScheme(
    primary = Color(0xFF66BB6A),
    onPrimary = Color.White,
    secondary = Color(0xFFFFC107),
    onSecondary = Color.Black,
    background = Color(0xFF1B5E20),
    onBackground = Color.White,
    surface = Color(0xFF2E7D32),
    onSurface = Color.White,
)

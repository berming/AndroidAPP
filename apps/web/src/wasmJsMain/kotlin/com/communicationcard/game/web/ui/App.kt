package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.communicationcard.game.web.viewmodel.AppViewModel
import com.communicationcard.game.web.viewmodel.Screen

/**
 * 沟通牌 · Web 客户端根 Composable
 *
 * 屏幕状态机由 [AppViewModel] 管理；这里只负责按当前 Screen 渲染对应页面。
 *
 * Stage 3 起：
 * - Theme 抽离到 [CommunicationCardTheme]（Theme.kt）
 * - 顶层 BoxWithConstraints 一次拿到 LayoutMode，传给 Theme 让 Typography
 *   全局响应式缩放（Compact 字号 ×0.92，Expanded ×1.08）
 *
 * 字体加载：[rememberCJKFontFamily] 异步 fetch GB2312 子集（~3 MB）。
 * 加载完成前所有中文字符走 Skia 默认 fallback（豆腐块短暂闪现 < 1s）。
 */
@Composable
fun App() {
    val vm = remember { AppViewModel() }
    val screen by vm.screen.collectAsState()
    val cjkFont by rememberCJKFontFamily()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val mode = classify(maxWidth)
        CommunicationCardTheme(cjkFamily = cjkFont, mode = mode) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = GreenTableColors.tableGreen,
            ) {
                Box(modifier = Modifier.fillMaxSize().background(GreenTableColors.tableGreen)) {
                    when (val s = screen) {
                        is Screen.Home -> HomeScreen(
                            onSinglePlayer = vm::startSinglePlayer,
                            onMultiplayer = vm::startMultiplayer,
                            onSettings = vm::openSettings,
                            onStats = vm::openStats,
                            onHelp = vm::openHelp,
                        )
                        is Screen.Settings -> SettingsScreen(
                            state = s,
                            onUpdate = vm::updatePrefs,
                            onBack = vm::goHome,
                        )
                        is Screen.Stats -> StatisticsScreen(
                            state = s,
                            onReset = vm::resetStats,
                            onBack = vm::goHome,
                        )
                        is Screen.Help -> HelpScreen(onBack = vm::goHome)
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
                            onAddAi = vm::addAI,
                            onLeave = vm::leaveRoom,
                        )
                        is Screen.Game -> GameScreen(
                            state = s,
                            onPlayCards = vm::playCards,
                            onPass = vm::pass,
                            onHint = vm::hint,
                            onToggleSelected = vm::toggleSelectedCard,
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
}

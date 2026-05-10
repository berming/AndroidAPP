package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.communicationcard.game.web.viewmodel.AppViewModel
import com.communicationcard.game.web.viewmodel.Screen

/**
 * 沟通牌 · Web 客户端根 Composable
 *
 * 屏幕状态机由 [AppViewModel] 管理；这里只负责按当前 Screen 渲染对应页面。
 *
 * 字体加载：[rememberCJKFontFamily] 异步 fetch GB2312 子集字体
 * （~3 MB；apps/web/fonts/build-subset.sh 生成）。加载完成前所有
 * 中文字符走 Skia 默认 fallback（豆腐块短暂闪现 < 1s），加载完后
 * 整个 UI 自动 recompose 用 NotoSansSC。
 */
@Composable
fun App() {
    val vm = remember { AppViewModel() }
    val screen by vm.screen.collectAsState()
    val cjkFont by rememberCJKFontFamily()

    MaterialTheme(
        colorScheme = greenTableScheme(),
        typography = cjkTypography(cjkFont),
    ) {
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

/**
 * 把所有 Material3 默认 textStyle 的 fontFamily 替换成 [cjkFamily]。
 * 字体未加载完时（[cjkFamily] = null）走 Material3 默认值（Skia 自带，
 * 不识中文）—— 仅在首屏加载这 < 1s 内有效，对最终用户可见时间极短。
 */
private fun cjkTypography(cjkFamily: FontFamily?): Typography {
    if (cjkFamily == null) return Typography()
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = cjkFamily),
        displayMedium = base.displayMedium.copy(fontFamily = cjkFamily),
        displaySmall = base.displaySmall.copy(fontFamily = cjkFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = cjkFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = cjkFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = cjkFamily),
        titleLarge = base.titleLarge.copy(fontFamily = cjkFamily),
        titleMedium = base.titleMedium.copy(fontFamily = cjkFamily),
        titleSmall = base.titleSmall.copy(fontFamily = cjkFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = cjkFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = cjkFamily),
        bodySmall = base.bodySmall.copy(fontFamily = cjkFamily),
        labelLarge = base.labelLarge.copy(fontFamily = cjkFamily),
        labelMedium = base.labelMedium.copy(fontFamily = cjkFamily),
        labelSmall = base.labelSmall.copy(fontFamily = cjkFamily),
    )
}

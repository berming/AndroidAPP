package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主菜单：与 Android 端 MainActivity 5 大入口对齐：
 *   联网多人对战 / 单机 AI 对战 / 游戏设置 / 统计回看 / 帮助
 */
@Composable
fun HomeScreen(
    onSinglePlayer: () -> Unit,
    onMultiplayer: () -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onHelp: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp).widthIn(max = 480.dp).fillMaxWidth(),
        ) {
            Text(
                text = "沟通牌",
                color = Color.White,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Web 浏览器版",
                color = Color(0xFFE8F5E9),
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(40.dp))

            // 主操作（金黄）
            PrimaryMenuButton("联网多人对战", onMultiplayer)
            Spacer(Modifier.height(12.dp))
            SecondaryMenuButton("单机 AI 对战", onSinglePlayer)

            Spacer(Modifier.height(24.dp))

            // 次要入口（与 Android 主菜单一致：设置 / 统计 / 帮助）
            SecondaryMenuButton("游戏设置", onSettings)
            Spacer(Modifier.height(8.dp))
            SecondaryMenuButton("统计回看", onStats)
            Spacer(Modifier.height(8.dp))
            SecondaryMenuButton("帮助", onHelp)

            Spacer(Modifier.height(40.dp))

            Text(
                text = "3 人 vs 3 人 · 6 副 216 张牌 · 与 Android 版本完全兼容",
                color = Color(0xFFB2DFDB),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun PrimaryMenuButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFFFC107),
            contentColor = Color.Black,
        ),
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecondaryMenuButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(label, fontSize = 16.sp, color = Color.White)
    }
}

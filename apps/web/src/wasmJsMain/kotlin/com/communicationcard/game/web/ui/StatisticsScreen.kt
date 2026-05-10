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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.communicationcard.game.web.viewmodel.Screen
import kotlin.math.roundToInt

/**
 * 与 Android 端 SettingsActivity 「游戏统计」面板对齐：
 * 总场次 / 胜率 / 当前 + 最高连胜 / 累计得分 / 重置按钮。
 *
 * 数据持久化在 localStorage（[com.communicationcard.game.web.storage.Statistics]）。
 * 重置走 ViewModel.resetStats() —— 此屏不直接访问存储，唯一真相在 VM。
 */
@Composable
fun StatisticsScreen(
    state: Screen.Stats,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val s = state.stats
    var showConfirm by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("返回", color = Color.White) }
                Spacer(Modifier.fillMaxWidth().weight(1f))
                Text("统计回看", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.fillMaxWidth().weight(1f))
            }

            Spacer(Modifier.height(16.dp))

            // 顶部三大块（总场 / 胜率 / 最高连胜）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatBigCell("总场次", s.totalGames.toString(), Modifier.weight(1f))
                StatBigCell("胜率", "${(s.winRate * 100).roundToInt()}%", Modifier.weight(1f))
                StatBigCell("最高连胜", s.maxStreak.toString(), Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            // 详细 4 项
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatRow("胜场数", s.wins.toString())
                    Spacer(Modifier.height(8.dp))
                    StatRow("负场数", s.losses.toString())
                    Spacer(Modifier.height(8.dp))
                    StatRow("当前连胜", s.currentStreak.toString())
                    Spacer(Modifier.height(8.dp))
                    StatRow("累计得分", s.totalScore.toString())
                }
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { showConfirm = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53935),
                    contentColor = Color.White,
                ),
            ) {
                Text("重置统计", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "数据持久化在本机浏览器；重置不可撤销。清除浏览器数据等同于重置。",
                color = Color(0xFFB2DFDB),
                fontSize = 12.sp,
            )
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("重置全部统计？") },
            text = { Text("此操作不可撤销 —— 总场次、胜率、连胜、得分都会清零。") },
            confirmButton = {
                Button(
                    onClick = { onReset(); showConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                ) { Text("确认重置", color = Color.White) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StatBigCell(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF388E3C)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(label, color = Color(0xFFB2DFDB), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = Color(0xFFFFC107), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Text(value, color = Color(0xFFFFC107), fontWeight = FontWeight.Bold)
    }
}

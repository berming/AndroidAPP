package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.communicationcard.game.web.storage.GameSpeed
import com.communicationcard.game.web.storage.UserPreferences
import com.communicationcard.game.web.viewmodel.Screen

/**
 * 与 Android 端 SettingsActivity 「游戏设置」面板对齐：
 * 昵称 / 音效 / 震动 / 出牌动画 / 游戏速度。
 *
 * 每次改值由 [onUpdate] 回调到 ViewModel，VM 写 localStorage 并立刻反推新 [Screen.Settings]，
 * 所以这里不维护本地 mutableStateOf —— 唯一真相在 VM 持有的 prefs 里。
 */
@Composable
fun SettingsScreen(
    state: Screen.Settings,
    onUpdate: ((UserPreferences) -> UserPreferences) -> Unit,
    onBack: () -> Unit,
) {
    val prefs = state.prefs
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("返回", color = Color.White) }
                Spacer(Modifier.fillMaxWidth().weight(1f))
                Text("游戏设置", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.fillMaxWidth().weight(1f))
            }

            Spacer(Modifier.height(16.dp))

            // 昵称
            SettingsCard(title = "昵称") {
                OutlinedTextField(
                    value = prefs.nickname,
                    onValueChange = { v -> onUpdate { it.copy(nickname = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            Spacer(Modifier.height(12.dp))

            // 开关组
            SettingsCard(title = "声效与反馈") {
                ToggleRow(
                    label = "音效",
                    sub = "出牌 / 收牌 / 通知",
                    checked = prefs.soundEnabled,
                    onChange = { v -> onUpdate { it.copy(soundEnabled = v) } },
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    label = "震动反馈",
                    sub = "仅触屏设备生效",
                    checked = prefs.vibrationEnabled,
                    onChange = { v -> onUpdate { it.copy(vibrationEnabled = v) } },
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    label = "出牌动画",
                    sub = "关闭以提升弱设备帧率",
                    checked = prefs.animationEnabled,
                    onChange = { v -> onUpdate { it.copy(animationEnabled = v) } },
                )
            }

            Spacer(Modifier.height(12.dp))

            // 速度（与 Android 对齐：慢/正常/快/极速）
            SettingsCard(title = "AI 出牌速度") {
                GameSpeed.entries.forEach { sp ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpdate { it.copy(gameSpeed = sp) } }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = prefs.gameSpeed == sp,
                            onClick = { onUpdate { it.copy(gameSpeed = sp) } },
                        )
                        Spacer(Modifier.padding(start = 4.dp))
                        Column {
                            Text(sp.displayName, color = Color.White)
                            Text("${sp.aiDelayMs} ms / 步", color = Color(0xFFB2DFDB), fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "设置自动保存到本机浏览器（localStorage）。清除浏览器数据会重置。",
                color = Color(0xFFB2DFDB),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = Color(0xFFFFC107), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold)
            Text(sub, color = Color(0xFFB2DFDB), fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFFFC107),
                checkedTrackColor = Color(0xFF66BB6A),
            ),
        )
    }
}

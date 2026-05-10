package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.communicationcard.game.web.storage.GameSpeed

/**
 * 游戏内"更多…"菜单（附录 A.2）。
 *
 * 5 项：① 查看出牌记录 / ② 设置昵称 / ③ AI 出牌速度 / ④ 离开 / ⑤ 暂离（仅多人）
 *
 * 用 [Dialog] overlay，关闭后回到原 GameScreen。
 *
 * "设置昵称""AI 出牌速度"通过 [NicknameDialog] / [AISpeedDialog] 子弹层完成；
 * 与 [SettingsScreen] 复用同一 prefs 持久化路径（约束："不要移动现有入口，
 * 是有多个设置入口"）。
 */
@Composable
fun MoreMenuDialog(
    isMultiplayer: Boolean,
    imAiSubstitute: Boolean,
    onDismiss: () -> Unit,
    onShowLog: () -> Unit,
    onEditNickname: () -> Unit,
    onChangeAiSpeed: () -> Unit,
    onLeave: () -> Unit,
    onToggleAfk: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties()) {
        DialogPanel(title = "更多") {
            MenuItem(label = "查看出牌记录", onClick = onShowLog)
            MenuDivider()
            MenuItem(label = "设置昵称", onClick = onEditNickname)
            MenuDivider()
            MenuItem(label = "AI 出牌速度", onClick = onChangeAiSpeed)
            MenuDivider()
            MenuItem(
                label = if (isMultiplayer) "离开房间" else "返回主界面",
                color = GreenTableColors.warning,
                onClick = onLeave,
            )
            if (isMultiplayer) {
                MenuDivider()
                MenuItem(
                    label = if (imAiSubstitute) "我回来了（取消暂离）" else "暂离（AI 接管）",
                    onClick = onToggleAfk,
                )
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

/** 设置昵称子弹层（"更多…"→ 设置昵称）。复用同一 prefs.nickname 持久化路径。 */
@Composable
fun NicknameDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(title = "设置昵称") {
            Text(
                "改名后立即保存到本机浏览器（localStorage），同时同步到本场游戏。",
                color = GreenTableColors.textMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onDismiss) { Text("取消", color = Color.White) }
                Spacer(Modifier.padding(start = 8.dp))
                Button(
                    onClick = { if (text.isNotBlank()) onSave(text.trim()) },
                    enabled = text.isNotBlank() && text.trim() != current,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GreenTableColors.brandPrimary,
                        contentColor = GreenTableColors.onBrandPrimary,
                    ),
                ) { Text("保存", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** AI 出牌速度子弹层（"更多…"→ AI 出牌速度）。复用 [GameSpeed] 4 档。 */
@Composable
fun AISpeedDialog(
    current: GameSpeed,
    onSelect: (GameSpeed) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(title = "AI 出牌速度") {
            Text(
                "改后立即生效（下一手 AI 用新延迟）。设置同步到本机浏览器。",
                color = GreenTableColors.textMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            GameSpeed.entries.forEach { sp ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(sp) }
                        .padding(vertical = 4.dp),
                ) {
                    RadioButton(
                        selected = sp == current,
                        onClick = { onSelect(sp) },
                    )
                    Spacer(Modifier.padding(start = 4.dp))
                    Column {
                        Text(sp.displayName, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("${sp.aiDelayMs} ms / 步", color = GreenTableColors.textMuted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
            }
        }
    }
}

@Composable
private fun DialogPanel(title: String, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .widthIn(min = 280.dp, max = 480.dp)
            .background(GreenTableColors.tableGreenLight, RoundedCornerShape(12.dp))
            .padding(20.dp),
    ) {
        Column {
            Text(
                title,
                color = GreenTableColors.brandPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    color: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
    ) {
        Text(label, color = color, fontSize = 15.sp)
    }
}

@Composable
private fun MenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x22FFFFFF)),
    )
}

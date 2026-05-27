package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.communicationcard.game.web.util.DebugLogManager

/**
 * 调试日志查看器（feature_spec N6，对齐 Android `LogViewerActivity`）。
 *
 * 按钮：复制 / 清空 / 刷新 / 返回；列表底部自动滚动到最新。
 *
 * 复制走 [jsCopyToClipboard]（navigator.clipboard.writeText / execCommand 兜底）；
 * 失败时静默——浏览器隐私模式下 clipboard API 可能被拒。
 */
@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    // 用 remember 缓存当前快照；点"刷新"才重读，避免每帧拉一次大字符串
    var snapshot by remember { mutableStateOf(DebugLogManager.getLogs()) }
    var toast by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    // 列表内容变化后滚到底部（最新日志），与 Android 的 fullScroll(FOCUS_DOWN) 等价
    LaunchedEffect(snapshot.size) {
        if (snapshot.isNotEmpty()) {
            listState.animateScrollToItem(snapshot.size - 1)
        }
    }
    // toast 自动消失（1.5s）
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(1500)
            toast = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
        Column(
            modifier = Modifier
                .widthIn(max = 960.dp)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) {
                    Text("返回", color = Color.White)
                }
                Spacer(Modifier.fillMaxWidth().weight(1f))
                Text(
                    "调试日志",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.fillMaxWidth().weight(1f))
                Text(
                    "${snapshot.size} 条",
                    color = Color(0xFFFFC107),
                    fontSize = 14.sp,
                )
            }

            Spacer(Modifier.padding(top = 12.dp))

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val text = DebugLogManager.getLogsAsString()
                        if (text.isEmpty()) {
                            toast = "暂无日志可复制"
                        } else {
                            jsCopyToClipboard(text)
                            toast = "日志已复制到剪贴板"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GreenTableColors.brandPrimary,
                        contentColor = GreenTableColors.onBrandPrimary,
                    ),
                ) { Text("复制") }

                Button(
                    onClick = {
                        DebugLogManager.clear()
                        snapshot = DebugLogManager.getLogs()
                        toast = "日志已清空"
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GreenTableColors.warning,
                        contentColor = Color.White,
                    ),
                ) { Text("清空") }

                Button(
                    onClick = {
                        snapshot = DebugLogManager.getLogs()
                        toast = "已刷新"
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF388E3C),
                        contentColor = Color.White,
                    ),
                ) { Text("刷新") }
            }

            Spacer(Modifier.padding(top = 12.dp))

            // Toast (transient banner)
            if (toast != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                ) {
                    Text(toast ?: "", color = Color.White, fontSize = 13.sp)
                }
                Spacer(Modifier.padding(top = 8.dp))
            }

            // Log list
            if (snapshot.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("暂无日志", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0D2818), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(snapshot) { entry -> LogEntryRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: DebugLogManager.LogEntry) {
    val color = when (entry.level) {
        "E" -> Color(0xFFFF6B6B)  // 红
        "W" -> Color(0xFFFFD54F)  // 黄
        "I" -> Color(0xFF81C784)  // 绿
        "D" -> Color(0xFF90CAF9)  // 蓝
        else -> Color(0xFFAAAAAA)  // 灰（fallback / 加载的旧记录）
    }
    // 等宽字体 + 颜色按 level 区分，便于扫读
    Text(
        text = entry.toString(),
        color = color,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth(),
    )
}

// === JS interop ===

/**
 * 复制到剪贴板：优先 `navigator.clipboard.writeText`（HTTPS / localhost），
 * 失败回退到隐藏 textarea + `document.execCommand('copy')`（兼容老浏览器）。
 * 任何异常静默吞掉——浏览器隐私模式可能完全禁用 clipboard。
 *
 * ASCII-only body per Main.kt:30 convention.
 */
@JsFun(
    """(text) => {
        try {
            if (navigator && navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text);
                return;
            }
        } catch (e) {}
        try {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.left = '-9999px';
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
        } catch (e) {}
    }""",
)
private external fun jsCopyToClipboard(text: String)

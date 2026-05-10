package com.communicationcard.game.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.communicationcard.game.network.GameMessage

/**
 * 与 Android 端 MainActivity 「帮助」面板对齐：游戏规则 / 关于 / 项目仓库链接。
 *
 * 链接打开走 [openUrl] @JsFun（window.open(...)）—— 不依赖 kotlinx-browser。
 */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B5E20))) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text("返回", color = Color.White) }
                Spacer(Modifier.fillMaxWidth().weight(1f))
                Text("帮助", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.fillMaxWidth().weight(1f))
            }

            Spacer(Modifier.padding(top = 16.dp))

            HelpCard("游戏规则") {
                BulletText("3 vs 3 团队对抗，4 副 216 张牌（每副带大小王）")
                BulletText("队伍交替坐：A 队 = 座位 0/2/4，B 队 = 座位 1/3/5")
                BulletText("起手玩家随机；其他玩家依次跟牌或过牌")
                BulletText("合法牌型：单 / 对 / 三 / 炸弹 (≥4 同点)。不支持顺子")
                BulletText("跟牌须同型同张数比点；炸弹压非炸弹；炸弹间先比张数再比点数")
                BulletText("一队全部走完结算；或一队已走完玩家累计已收 ≥ 200 分提前结束")
                BulletText("结算公式：赢方分 = 赢方已收 + 输方未走完玩家已收 + 输方未走完玩家手牌分")
                BulletText("完整规则与 15 个验证用例见 docs/game_rules.md / settlement_verification.md")
            }

            Spacer(Modifier.padding(top = 12.dp))

            HelpCard("关于") {
                BulletText("沟通牌 · Web 客户端")
                BulletText("基于 Compose Multiplatform / Wasm-JS")
                BulletText("与 Android 客户端共享 :shared 模块（牌型规则 / 结算 / AI / 协议 DTO）")
                // 实际从 :shared 读，避免 hardcode 漂移（per pr-reviewer nit on PR #47）
                BulletText("协议 PROTOCOL_VERSION = ${GameMessage.PROTOCOL_VERSION}（与服务端握手时校验）")
                Spacer(Modifier.padding(top = 8.dp))
                LinkButton("源代码 / Issues  ↗") {
                    openUrl("https://github.com/berming/AndroidAPP")
                }
                Spacer(Modifier.padding(top = 4.dp))
                LinkButton("调试日志（浏览器 DevTools Console）") {
                    openConsole()
                }
            }

            Spacer(Modifier.padding(top = 12.dp))

            HelpCard("提示") {
                BulletText("Web 版菜单与 Android 端持续对齐中；功能差异请提 GitHub Issues")
                BulletText("中文字体子集 ~3 MB，首次加载稍慢；缓存后秒开")
                BulletText("联网模式默认连同源 /game；开发可在大厅手动改 ws URL")
            }
        }
    }
}

@Composable
private fun HelpCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = Color(0xFFFFC107), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(top = 8.dp))
            content()
        }
    }
}

@Composable
private fun BulletText(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("·  ", color = Color(0xFFFFC107))
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color(0xFFFFC107))
    }
}

@JsFun("(url) => { try { window.open(url, '_blank'); } catch (e) {} }")
private external fun openUrl(url: String)

/**
 * 在浏览器 DevTools Console 打两条彩色提示。
 *
 * @JsFun body **必须 ASCII-only**（同 Main.kt:30 注释；过去某些 wasmJs / webpack 工具链
 * 对 UTF-8 字面量处理不一致）。把中文文案作为 Kotlin 参数传入，JS 内只做拼接。
 */
private fun openConsole() {
    consoleLogStyled(
        styledMsg = "%c[沟通牌] 调试日志输出在浏览器 DevTools Console。",
        css = "color: #FFC107; font-size: 14px; font-weight: bold;",
        plainMsg = "打开方式：F12 / 右键 → 检查 / Cmd+Option+I",
    )
}

@JsFun(
    """(styledMsg, css, plainMsg) => {
        try {
            console.log(styledMsg, css);
            console.log(plainMsg);
        } catch (e) {}
    }""",
)
private external fun consoleLogStyled(styledMsg: String, css: String, plainMsg: String)

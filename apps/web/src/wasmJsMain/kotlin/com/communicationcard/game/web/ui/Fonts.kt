package com.communicationcard.game.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * 中文字体加载（沟通牌 · Web）
 *
 * 背景：Compose Multiplatform / wasmJs 用 Skia 在 `<canvas>` 里渲染文本。
 * Skia 在浏览器 wasm 沙箱内**拿不到 OS 字体**（沙箱安全模型），它只用打进
 * wasm 包的字体；CMP 默认字体只覆盖 Latin。所以中文字符全部画成豆腐块（□）。
 *
 * 修法：fetch 一份 Noto Sans CJK SC 子集字体（apps/web/fonts/build-subset.sh
 * 生成；当前 GB2312 全集 + 项目用到的非 GB2312 符号 = 7626 codepoints，
 * ~3 MB），通过 androidx.compose.ui.text.platform.Font(identity, data) 注册
 * 成 Compose 字体家族，挂在 MaterialTheme 的默认 typography 上。
 *
 * 文件名是 `.otf`（不是 `.ttf`）—— Noto Sans CJK 用 CFF outline，sfnt magic
 * 是 `OTTO`，是真正的 OpenType/CFF 字体；Skia 看 sfnt header 不看扩展名，
 * 浏览器和 Caddy 也都识别。
 *
 * 加载流程异步：未加载完时返回 null（fallback 到 CMP 默认字体，仍是豆腐
 * 但短暂出现 < 1s）；加载完后整个 UI 自动 recompose 用新字体。
 *
 * URL 用绝对路径 `/fonts/...`：避免未来 Web 客户端被部署到 sub-path
 * (e.g. `https://host/cards/`) 或被 SPA 路由器修改地址栏后，相对路径
 * `fonts/...` 解析到错误位置。
 */
private const val FONT_URL = "/fonts/NotoSansSC-Subset.otf"

/**
 * @JsFun 内部用 chunked btoa：直接 btoa(String.fromCharCode(...)) 大数组
 * 会爆 call stack；按 0x8000 分块拼接最稳。
 */
@JsFun(
    """async (url) => {
        const r = await fetch(url);
        if (!r.ok) throw new Error('font fetch failed: ' + r.status + ' ' + url);
        const buf = await r.arrayBuffer();
        const bytes = new Uint8Array(buf);
        let s = '';
        const chunk = 0x8000;
        for (let i = 0; i < bytes.length; i += chunk) {
            s += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        }
        return btoa(s);
    }""",
)
private external fun fetchAsBase64(url: String): Promise<JsString>

/**
 * 用 try/catch 而非 runCatching：runCatching 的 block 不是 suspend 函数，
 * 在里面调 [await] 会编译失败（Type mismatch / 推断不出 T）。
 */
@OptIn(ExperimentalEncodingApi::class)
private suspend fun loadCJKFontBytes(): ByteArray? = try {
    val jsStr: JsString = fetchAsBase64(FONT_URL).await()
    Base64.decode(jsStr.toString())
} catch (e: Throwable) {
    consoleError("loadCJKFontBytes failed: ${e.message}")
    null
}

/**
 * 给 [App] 用的 Composable：在后台异步加载字体；返回的 State 一旦非 null
 * 就把它装进 Typography.defaultFontFamily。
 */
@Composable
fun rememberCJKFontFamily(): State<FontFamily?> {
    val state = remember { mutableStateOf<FontFamily?>(null) }
    LaunchedEffect(Unit) {
        val bytes = loadCJKFontBytes() ?: return@LaunchedEffect
        try {
            val font = Font(identity = "NotoSansSC", data = bytes)
            state.value = FontFamily(font)
        } catch (e: Throwable) {
            consoleError("Font registration failed: ${e.message}")
        }
    }
    return state
}

@JsFun("(msg) => console.error(msg)")
private external fun consoleError(msg: String?)

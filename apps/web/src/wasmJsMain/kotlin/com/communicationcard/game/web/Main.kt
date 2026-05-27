package com.communicationcard.game.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.communicationcard.game.web.ui.App
import com.communicationcard.game.web.util.DebugLogManager

/**
 * 浏览器入口：由 communicationCardWeb.js 在 index.html 加载后调用。
 *
 * 步骤：
 * 1. 初始化 [DebugLogManager]（feature_spec N6）+ 全局未捕获异常 hook。
 * 2. ComposeViewport 接管 #composeTarget div，渲染整个应用。
 * 3. 安排在下一帧把 #loader 元素从 DOM 中移除 —— 否则它会以 position:fixed
 *    悬浮在 Compose 画布上方，造成"一直在加载"的错觉。
 *
 * 使用 viewportContainerId 字符串重载（CMP 1.6.x+ 提供）避免依赖
 * kotlinx.browser.document —— Kotlin 1.9.24 wasmJs target 不打算引入
 * kotlinx-browser:0.1（要求 Kotlin 2.0+）。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // 先装 JS 全局错误 handler（在 init 之前装，防止 init 自身抛错也能被记下）
    installGlobalErrorHandler()
    DebugLogManager.init()
    DebugLogManager.i("App", "main() invoked, ComposeViewport bootstrapping")

    ComposeViewport(viewportContainerId = "composeTarget") {
        App()
    }
    // Compose 第一帧在 requestAnimationFrame 后才落到画布；这里把移除 loader
    // 推迟一帧，避免出现"白屏 → 闪烁 → UI"的过渡瑕疵。CSS 上有 200ms 透明度
    // 渐变兜底，即使移除时机略早肉眼也基本无感。
    scheduleHideLoader()
}

// JsFun 内部用纯 ASCII：避免某些 wasmJs / webpack 工具链对 UTF-8 字面量的处理差异。
@JsFun(
    """() => {
        const hide = () => {
            const el = document.getElementById('loader');
            if (!el) return;
            el.classList.add('hidden');
            setTimeout(() => { if (el.parentNode) el.parentNode.removeChild(el); }, 250);
        };
        if (typeof requestAnimationFrame === 'function') {
            requestAnimationFrame(() => requestAnimationFrame(hide));
        } else {
            setTimeout(hide, 50);
        }
    }""",
)
private external fun scheduleHideLoader()

/**
 * 安装全局 JS 错误 hook（feature_spec N6 配套）：把 `window.error` /
 * `unhandledrejection` 累积到 localStorage 中转 key `debug_log_pending_errors`，
 * 由 [DebugLogManager.init] 启动时排空到主日志。
 *
 * 中转方案的原因：Kotlin/wasmJs 1.9.24 不支持把 Kotlin function 作为参数
 * 直接传给 `@JsFun`（wasmJs 2.0+ 才稳定）；localStorage 是双方都能同步访问
 * 的零依赖通道。
 *
 * 不调用 e.preventDefault() —— 让浏览器默认行为（DevTools 红字 / Sentry 等）
 * 照常工作；我们只是**额外**记录到本地日志。
 */
@JsFun(
    """() => {
        try {
            const KEY = 'debug_log_pending_errors';
            const MAX_PENDING = 50;
            const push = (msg, stack) => {
                try {
                    const raw = window.localStorage.getItem(KEY) || '';
                    const lines = raw ? raw.split('\n').filter(s => s.length > 0) : [];
                    const line = JSON.stringify({ t: Date.now(), m: msg, s: stack || '' });
                    lines.push(line);
                    while (lines.length > MAX_PENDING) lines.shift();
                    window.localStorage.setItem(KEY, lines.join('\n'));
                } catch (ignored) {}
            };
            window.addEventListener('error', (e) => {
                try {
                    const msg = e.message || (e.error && e.error.message) || 'Unknown error';
                    const stack = (e.error && e.error.stack) ? String(e.error.stack) : null;
                    push(msg, stack);
                } catch (ignored) {}
            });
            window.addEventListener('unhandledrejection', (e) => {
                try {
                    const reason = e.reason;
                    const msg = (reason && reason.message) ? String(reason.message) :
                                (typeof reason === 'string') ? reason :
                                'Unhandled Promise rejection';
                    const stack = (reason && reason.stack) ? String(reason.stack) : null;
                    push(msg, stack);
                } catch (ignored) {}
            });
        } catch (e) {}
    }""",
)
private external fun installGlobalErrorHandler()

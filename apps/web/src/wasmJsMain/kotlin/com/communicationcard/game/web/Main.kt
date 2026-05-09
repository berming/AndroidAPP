package com.communicationcard.game.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.communicationcard.game.web.ui.App

/**
 * 浏览器入口：由 communicationCardWeb.js 在 index.html 加载后调用。
 *
 * 步骤：
 * 1. ComposeViewport 接管 #composeTarget div，渲染整个应用。
 * 2. 安排在下一帧把 #loader 元素从 DOM 中移除 —— 否则它会以 position:fixed
 *    悬浮在 Compose 画布上方，造成"一直在加载"的错觉。
 *
 * 使用 viewportContainerId 字符串重载（CMP 1.6.x+ 提供）避免依赖
 * kotlinx.browser.document —— Kotlin 1.9.24 wasmJs target 不打算引入
 * kotlinx-browser:0.1（要求 Kotlin 2.0+）。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
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

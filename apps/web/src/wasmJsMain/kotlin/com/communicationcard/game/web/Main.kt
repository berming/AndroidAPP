package com.communicationcard.game.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.communicationcard.game.web.ui.App

/**
 * 浏览器入口：由 communicationCardWeb.js 在 index.html 加载后调用。
 *
 * Compose Viewport 接管 #composeTarget div，渲染整个应用。
 *
 * 使用 viewportContainerId 重载（CMP 1.5.x+ 提供）避免依赖 kotlinx.browser.document
 * —— Kotlin 1.9.24 wasmJs target 不打算引入 kotlinx-browser:0.1（该包要求 Kotlin 2.0+）。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "composeTarget") {
        App()
    }
}

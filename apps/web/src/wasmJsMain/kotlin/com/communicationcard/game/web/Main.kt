package com.communicationcard.game.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.communicationcard.game.web.ui.App
import kotlinx.browser.document

/**
 * 浏览器入口：由 communicationCardWeb.js 在 index.html 加载后调用。
 *
 * Compose Viewport 接管 #composeTarget div，渲染整个应用。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val target = document.getElementById("composeTarget")
        ?: error("index.html 中缺少 id=composeTarget 的元素")
    ComposeViewport(target) {
        App()
    }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Compose Multiplatform 插件
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    // 不显式 set repositoriesMode → 默认 PREFER_PROJECT。Kotlin Gradle Plugin
    // 1.9.x 的 NodeJsSetupTask 在 wasmJs target 启用时往 project 级注册
    // "Node Distributions at https://nodejs.org/dist" 仓库，必须让该 project
    // 仓库参与解析，否则 org.nodejs:node:22.0.0 找不到。
    //
    // 之前用过 FAIL_ON_PROJECT_REPOS（项目仓库直接报 InvalidUserCodeException）
    // 与 PREFER_SETTINGS（仓库注册成功但解析时被跳过，导致 Node 仍找不到）。
    // KGP 2.0+ 把 Node 仓库注册到 settings 级，那时可恢复严格模式。
    repositories {
        google()
        mavenCentral()
        // Compose Multiplatform 制品
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "AndroidApps"

// 跨平台共享模块（协议 / 牌型规则 / 结算 / AI），Android、Web 等所有客户端共用
include(":shared")

// Communication Card Game (沟通牌)
include(":apps:communication-card")
// Web 客户端（Compose Multiplatform / Wasm-JS，浏览器版本）
include(":apps:web")
// 服务端（Ktor + WebSocket）。PR-H3 起并入 root build；依赖 :shared 消除约束 1/4。
include(":server")

// Gomoku (五子棋)
include(":apps:gomoku")

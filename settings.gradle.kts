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
    // 用 PREFER_SETTINGS（不是 FAIL_ON_PROJECT_REPOS）：Kotlin Gradle Plugin 的
    // NodeJsSetupTask 会在 wasmJs target 启用时往 project-level repositories 注册
    // "Node Distributions" ivy 仓库（KGP 1.9.x 行为，2.0+ 改为 settings-level）。
    // 严格模式下会触发 InvalidUserCodeException：
    //   "Build was configured to prefer settings repositories over project
    //    repositories but repository 'Node Distributions at https://nodejs.org/dist'
    //    was added by unknown code"
    // PREFER_SETTINGS 仍然让 settings 仓库优先，仅允许 KGP 注册它的 Node 仓库。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
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

// Gomoku (五子棋)
include(":apps:gomoku")

// 注意: server/ 是独立的 Ktor 项目，需单独构建
// 启动方式: cd server && ./gradlew run

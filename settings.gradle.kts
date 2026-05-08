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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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

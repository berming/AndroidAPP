pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroidApps"

// Communication Card Game (沟通牌)
include(":apps:communication-card")

// Gomoku (五子棋)
include(":apps:gomoku")

// 注意: server/ 是独立的 Ktor 项目，需单独构建
// 启动方式: cd server && ./gradlew run

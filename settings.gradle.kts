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

// Multiplayer Server (多人游戏服务器)
include(":server")

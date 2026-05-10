plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    // PR #47 引入：Stage 1 menu parity 用 @Serializable 持久化
    // UserPreferences / Statistics 到 localStorage。不 apply 此 plugin
    // 则 @Serializable 注解被忽略，serializer() 不生成 → wasmJs 编译挂。
    id("org.jetbrains.kotlin.plugin.serialization")
}

@OptIn(org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl::class)
kotlin {
    wasmJs {
        moduleName = "communicationCardWeb"
        browser {
            commonWebpackConfig {
                outputFileName = "communicationCardWeb.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                // 注 1：不再依赖 kotlinx-browser（0.1 版要求 Kotlin 2.0+，与本项目
                // 1.9.24 不兼容）。WebSocket / document 等 DOM API 通过
                // 各文件内的 @JsFun external 声明直接 interop（见 net/WebSocketTransport.kt）。
                // 注 2：不引入 compose.components.resources —— CI 警告
                // "Compose resources publication requires Kotlin Gradle Plugin >= 2.0"，
                // 当前项目固定 1.9.24；未使用 Res.* 资源 API，无功能损失。
            }
        }
    }
}

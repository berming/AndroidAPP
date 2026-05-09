plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
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
                implementation(compose.components.resources)
                // 注：不再依赖 kotlinx-browser（0.1 版要求 Kotlin 2.0+，与本项目
                // 1.9.24 不兼容）。WebSocket / document 等 DOM API 通过
                // 各文件内的 @JsFun external 声明直接 interop（见 net/WebSocketTransport.kt）。
            }
        }
    }
}

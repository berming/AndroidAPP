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
                // Kotlin 1.9.24 wasmJs stdlib 不直接包含 org.w3c.dom.*；
                // 由 kotlinx-browser 提供 WebSocket / document / window 等浏览器绑定。
                // KMP 自动按 target 选择正确变体（这里会拉取 -wasm-js 后缀）。
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.1")
            }
        }
    }
}

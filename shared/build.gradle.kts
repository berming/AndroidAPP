plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.library")
}

@OptIn(org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl::class)
kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                // 必须与 :apps:android 一致（jvmTarget = "1.8"）；
                // 不一致 AGP 会报 "Inconsistent JVM-target compatibility..."。
                jvmTarget = "1.8"
            }
        }
        // KMP 默认只发布 release 变体；Android 消费方（:apps:android）
        // 同时需要 debug 变体来跑 assembleDebug。不显式声明会出现
        // "Could not find a variant of project :shared that matches the consumer
        //  attributes" 这类 AGP variant resolution 错误。
        publishLibraryVariants("release", "debug")
    }

    jvm()

    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // 用 api 暴露给消费方，否则 Android/Web 调用 GameMessage.json.encodeToString 时拿不到 serializer。
                // 1.6.0 没有 wasmJs variant（CI 失败原因：`No matching variant ...
                // attribute 'wasm.target' = 'js' but: ...`），最早提供 wasmJs 的是 1.6.3。
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                // 协程 1.7.3 没有 wasmJs target；最早的支持版本是 1.8.0。
                // 1.8.1 是 Kotlin 1.9.24 兼容线上最稳的一档。
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        val androidMain by getting

        val jvmMain by getting
        val jvmTest by getting {
            dependencies {
                // 显式声明 JVM 测试 runner，避免 KMP 依赖解析回落到不可用的实现
                implementation(kotlin("test-junit"))
            }
        }

        val wasmJsMain by getting
    }
}

android {
    namespace = "com.communicationcard.game.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        // 与 :apps:android 对齐
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

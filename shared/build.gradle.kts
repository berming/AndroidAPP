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
                jvmTarget = "17"
            }
        }
    }

    jvm()

    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // 用 api 暴露给消费方，否则 Android/Web 调用 GameMessage.json.encodeToString 时拿不到 serializer
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

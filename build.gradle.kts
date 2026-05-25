// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// 质量工具链（Issues #78-#83 / UC11 Critical 整改）：
// - JaCoCo (#78)：在 :server 子模块 build.gradle.kts 中启用
// - detekt-formatting (#79)：本文件加 detektPlugins 依赖；规则需 baseline 后启用
// - 复杂度阈值 (#82)：detekt.yml 现有 threshold=25 维持；高风险模块严格 12 走单独 task
// - OWASP DC (#80)：插件已加载，task 名 dependencyCheckAnalyze；不入 :check，按需 / 定时跑
// - binary-compat (#81)：插件已加载；apiValidation 块全 ignore 直到首次 apiDump bootstrap
// - kotlinx.benchmark (#83)：在 :shared 子模块单独配置
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("com.android.library") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.multiplatform") version "1.9.24" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("org.jetbrains.compose") version "1.6.10" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    // Issue #81 — API 二进制兼容校验
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.14.0"
    // Issue #80 — OWASP Dependency-Check
    id("org.owasp.dependencycheck") version "9.2.0"
    // Issue #83 — 基准测试（KMP 友好；kotlinx.benchmark）
    id("org.jetbrains.kotlinx.benchmark") version "0.4.10" apply false
    id("org.jetbrains.kotlin.plugin.allopen") version "1.9.24" apply false
}

// Issue #79 — detekt-formatting plugin（ktlint 集成）
// 当前仅安装依赖；formatting 规则默认未在 detekt.yml 启用，需 baseline 后逐步开启
dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")
}

detekt {
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
    buildUponDefaultConfig = true
    allRules = false
    // baseline 文件存在则自动使用（detekt 1.23 行为：文件不存在不报错，正常跑）
    baseline = file("$rootDir/config/detekt-baseline.xml")
    // 测试代码允许 !! 等模式（assertNotNull 后立即 !! 访问），不在 detekt 范围内
    source.setFrom(
        files(
            "apps/android/src/main/java",
            "apps/web/src/wasmJsMain/kotlin",
            "shared/src/commonMain/kotlin",
            "server/src/main/kotlin",
        )
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
    }
}

// Issue #82 — 高风险模块严格复杂度阈值（≤12 vs 默认 ≤25）
// 5 个高风险模块（AQR rev=3）：
//   shared/.../engine, network, ai · server/.../admin · server/.../ServerGameManager.kt
// **此 task 不挂到 :check**，避免立即阻断 CI；CI 单独 `./gradlew detektHighRisk` 或本地手动跑
val detektHighRisk by tasks.registering(io.gitlab.arturbosch.detekt.Detekt::class) {
    description = "Strict complexity checks for high-risk modules (AQR rev=3 HR threshold=12)"
    parallel = true
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml", "$rootDir/config/detekt-high-risk.yml")
    setSource(
        files(
            "shared/src/commonMain/kotlin/com/communicationcard/game/engine",
            "shared/src/commonMain/kotlin/com/communicationcard/game/network",
            "shared/src/commonMain/kotlin/com/communicationcard/game/ai",
            "server/src/main/kotlin/com/communicationcard/server/admin",
            "server/src/main/kotlin/com/communicationcard/server/ServerGameManager.kt",
        )
    )
    include("**/*.kt")
    baseline = file("$rootDir/config/detekt-high-risk-baseline.xml")
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

// Issue #81 — API 二进制兼容校验配置
// 当前全 ignoredProjects = 没有 .api 文件可对比，CI apiCheck no-op 通过。
// Bootstrap 流程：
//   1. 从 ignoredProjects 移除 ":shared"
//   2. ./gradlew :shared:apiDump → 生成 shared/api/shared.api
//   3. 提交该文件 → 后续 apiCheck 自动比对
apiValidation {
    ignoredProjects.addAll(listOf("apps", "android", "web", "admin", "shared", "server"))
    nonPublicMarkers.add("kotlin.PublishedApi")
}

// Issue #80 — OWASP Dependency-Check 配置
// 默认不在 :check 中（plugin 行为）；CI 单独 job 或定时任务跑 dependencyCheckAnalyze
dependencyCheck {
    failBuildOnCVSS = 7.0f  // 高危 CVSS ≥ 7 阻断
    suppressionFile = "$rootDir/config/owasp-suppressions.xml"
    formats = listOf("HTML", "JSON", "XML")
    // 加速：禁用与 JVM 项目无关的分析器
    analyzers.apply {
        assemblyEnabled = false
        nuspecEnabled = false
        nodeAuditEnabled = false
        nodeEnabled = false
        retirejs.enabled = false
    }
}

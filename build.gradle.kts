// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

detekt {
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
    buildUponDefaultConfig = true
    allRules = false
    // 测试代码允许 !! 等模式（assertNotNull 后立即 !! 访问），不在 detekt 范围内
    source.setFrom(files("apps/communication-card/src/main/java"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
    }
}

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
    source.setFrom(
        files(
            "apps/communication-card/src/main/java",
            "apps/communication-card/src/test/java"
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

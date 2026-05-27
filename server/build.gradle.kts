plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
    application
    jacoco
}

group = "com.communicationcard"
version = "1.0.0"

application {
    mainClass.set("com.communicationcard.server.ApplicationKt")
}

// detekt 配置由 root build.gradle.kts 集中管理（已包含 server/src/main/kotlin
// 在 source.setFrom）；本子项目无需重复定义 detekt 块。

val ktorVersion = "2.3.6"
val kotlinxSerializationVersion = "1.6.3"
val logbackVersion = "1.4.11"

dependencies {
    // 共享模块：协议 DTO + 牌型规则 + 结算公式（消除约束 1/4 的根因）。
    // PR-H3 起，server 与所有客户端使用同一份 GameMessage / CardRules /
    // SettlementCalculator —— 编译期保证两端等价。
    implementation(project(":shared"))

    // Ktor Server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Kotlinx Serialization（与 :shared 对齐到 1.6.3——后者是首个支持 wasmJs 的版本）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Coroutines（与 :shared 对齐到 1.8.1——首个支持 wasmJs 的版本）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    // PR 5c：JSON 结构化日志（logstash-logback-encoder）；admin 模块 + Ktor 自身的
    // SLF4J 日志输出为单行 JSON，方便 jq / Loki / ELK 等聚合工具消费
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // PR 1: admin 后台依赖
    // SQLite：admin_users / admin_sessions / 后续 games / game_players / alerts 全部落库
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    // bcrypt：密码哈希。jbcrypt 0.4 是稳定版本，无传递依赖
    implementation("org.mindrot:jbcrypt:0.4")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

// SWD-DT-COV-001 — JaCoCo 覆盖率（Issue #78）
// 目标值见 AQR rev=3：全量 ≥75%，高风险模块 ≥85%。
// 当前仅产出报告，不阻断 PR；接到 CI artifact 后由 software-quality-agent
// 在 checkpoint 比较实际值，违阈值才返回 HOLD。
jacoco {
    toolVersion = "0.8.11"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)   // CI 上传 / software-quality-agent 解析
        html.required.set(true)  // 本地 dev 查看
        csv.required.set(false)
    }
    // 排除生成代码 + 测试代码本身
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "**/*Test*.class",
                    "**/*\$Companion.class",
                    "**/*\$*\$serializer.class"
                )
            }
        })
    )
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

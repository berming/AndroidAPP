package com.communicationcard.server.admin

import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig

/**
 * 包装 application.conf 中 `admin { ... }` 段的强类型读取。
 *
 * application.conf 已经在 PR 0 创建并预留 admin.* 段；这里只是把字符串配置
 * 翻译成 Kotlin 字段，方便 [AdminContext] 注入下游模块。
 */
data class AdminConfig(
    val dbPath: String,
    val cookieDomain: String?,
    val cookieSecure: Boolean,
    val sessionTtlSeconds: Long,
    val initialUsername: String,
    val initialPassword: String,
) {
    companion object {
        fun fromApplication(app: Application): AdminConfig = fromConfig(app.environment.config)

        fun fromConfig(config: ApplicationConfig): AdminConfig {
            val admin = config.config("admin")
            return AdminConfig(
                dbPath = admin.property("db.path").getString(),
                cookieDomain = admin.propertyOrNull("cookieDomain")?.getString()
                    ?.takeIf { it.isNotBlank() && it != "_" },
                cookieSecure = admin.propertyOrNull("cookieSecure")?.getString()?.toBoolean() ?: true,
                sessionTtlSeconds = admin.propertyOrNull("sessionTtlSeconds")?.getString()
                    ?.toLong() ?: 28_800L,
                initialUsername = admin.propertyOrNull("initial.username")?.getString().orEmpty(),
                initialPassword = admin.propertyOrNull("initial.password")?.getString().orEmpty(),
            )
        }
    }
}

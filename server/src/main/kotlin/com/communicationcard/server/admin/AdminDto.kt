package com.communicationcard.server.admin

import kotlinx.serialization.Serializable

/**
 * 登录 / 改密 / `/me` 系列接口的请求 + 响应 DTO。
 *
 * 强制约束（约束 10 / 见 CLAUDE.md PR 1 起）：
 * - **绝不**把 password_hash / 任何密钥字段序列化进 DTO
 * - last_login_at 以 ISO-8601 字符串返回（前端 dayjs 直接 parse）
 */

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class ChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String,
)

@Serializable
data class AdminUserDto(
    val id: Long,
    val username: String,
    val role: String,
    val permissions: List<String>,
    val lastLoginAt: String?,
) {
    companion object {
        fun from(user: AdminUser): AdminUserDto = AdminUserDto(
            id = user.id,
            username = user.username,
            role = user.role.name,
            permissions = ROLE_PERMISSIONS[user.role].orEmpty().map { it.name }.sorted(),
            lastLoginAt = user.lastLoginAt?.let { java.time.Instant.ofEpochMilli(it).toString() },
        )
    }
}

@Serializable
data class LoginResponse(val user: AdminUserDto)

@Serializable
data class ErrorResponse(val error: String)

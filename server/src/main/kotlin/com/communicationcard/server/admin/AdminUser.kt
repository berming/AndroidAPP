package com.communicationcard.server.admin

/**
 * Admin 用户（来自 admin_users 表）。
 *
 * 注意：[passwordHash] 字段**绝不**进任何返回 DTO；只在服务端比对密码时使用。
 * `password` 永远不持有明文（登录时立刻 hash + 比对完丢弃）。
 */
data class AdminUser(
    val id: Long,
    val username: String,
    val passwordHash: String,
    val role: AdminRole,
    val isActive: Boolean,
    val createdAt: Long,
    val lastLoginAt: Long?,
)

/**
 * Admin session（来自 admin_sessions 表）。
 *
 * [token] 是 cookie 携带的随机字符串（32 字节 base64url）；
 * 服务端用它查这张表，找到对应 [adminId]。
 * [expiresAt] 由 [AdminAuthService.validate] 每次访问滚动续期。
 */
data class AdminSession(
    val token: String,
    val adminId: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val lastUsedAt: Long,
    val userAgent: String?,
    val ip: String?,
)

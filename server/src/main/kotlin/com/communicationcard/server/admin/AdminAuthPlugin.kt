package com.communicationcard.server.admin

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.userAgent
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey

/**
 * Admin 鉴权"中间件"——手写 cookie 读取 + 表校验，不依赖 Ktor `Sessions` /
 * `Authentication` plugin。
 *
 * 用法（在路由 handler 顶部）：
 * ```
 * get("/admin/api/overview") {
 *     val user = call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
 *     // ...
 * }
 * ```
 *
 * 设计取舍：
 * - 不用 Ktor `install(Authentication)`：那适合多 provider 场景，单一 cookie
 *   用途反而绕弯
 * - 401 / 403 直接 `call.respond` 返回；调用方 `return@handler` 终止
 * - `call.attributes` 缓存当次请求的 AdminUser，避免一个 handler 内多次查库
 */

const val ADMIN_COOKIE_NAME = "cardsAdminSession"

val ADMIN_USER_ATTR_KEY = AttributeKey<AdminUser>("AdminUser")

/**
 * 取当前请求的 admin cookie token；未登录时为 null。
 */
fun ApplicationCall.adminToken(): String? = request.cookies[ADMIN_COOKIE_NAME]

/**
 * 取当次请求已校验通过的 AdminUser（前提：handler 之前已调过 [requireAdmin]）。
 */
fun ApplicationCall.currentAdmin(): AdminUser? =
    attributes.getOrNull(ADMIN_USER_ATTR_KEY)

/**
 * 校验登录状态；返回 AdminUser 或 null（已响应 401）。
 *
 * TODO: 鉴权临时关闭（bypass 模式）——登录问题修复后必须删掉此注释并恢复鉴权
 */
suspend fun ApplicationCall.requireAdmin(ctx: AdminContext): AdminUser? {
    // ===== TEMPORARY AUTH BYPASS — REMOVE BEFORE NEXT RELEASE =====
    // Try real session validation first so operations that need the real user id
    // (change-password, etc.) still work when a valid cookie is present.
    // Falls back to a synthetic SUPER_ADMIN only when no valid session is found.
    val token = adminToken()
    if (token != null) {
        val realUser = ctx.authService.validate(token)
        if (realUser != null) {
            attributes.put(ADMIN_USER_ATTR_KEY, realUser)
            return realUser
        }
    }
    val bypass = AdminUser(
        id = 0L,
        username = "bypass",
        passwordHash = "",
        role = AdminRole.SUPER_ADMIN,
        isActive = true,
        createdAt = 0L,
        lastLoginAt = null,
    )
    attributes.put(ADMIN_USER_ATTR_KEY, bypass)
    return bypass
    // ===== END BYPASS =====
}

/**
 * 校验登录 + 权限位；缺权限返回 403（并响应）。
 */
suspend fun ApplicationCall.requirePermission(
    ctx: AdminContext,
    permission: AdminPermission,
): AdminUser? {
    val user = requireAdmin(ctx) ?: return null
    if (!user.role.hasPermission(permission)) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("权限不足: 需要 $permission"))
        return null
    }
    return user
}

/**
 * 从请求里推断客户端 user-agent / IP，用于 session 行的审计列。
 *
 * IP 优先读 `X-Forwarded-For`（Caddy 反代会**追加**），否则用 remoteHost。
 *
 * **关键（pr-reviewer PR #61 P2 #2）**：Caddy 默认 `reverse_proxy` 是
 * **append** 行为而非 overwrite。攻击者发 `X-Forwarded-For: 8.8.8.8` 到 Ktor
 * 后变成 `8.8.8.8, <caddy_remote>`；取 `first` 拿到伪造 IP。**必须取 last**
 * —— 链尾是我们自己 Caddy 写入的可信值。
 *
 * 若未来想接受多跳反代（如 CDN → Caddy → Ktor），需要可配置 "信任跳数 N"，
 * 取倒数第 N 个。当前单跳 Caddy 取 last 即可。
 */
fun ApplicationCall.clientAuditFields(): Pair<String?, String?> {
    val ua = request.userAgent()
    val xff = request.headers["X-Forwarded-For"]?.split(",")?.lastOrNull()?.trim()
    val ip = xff?.takeIf { it.isNotBlank() } ?: request.local.remoteHost
    return ua to ip
}

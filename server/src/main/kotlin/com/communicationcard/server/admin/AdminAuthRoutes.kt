package com.communicationcard.server.admin

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.util.pipeline.PipelineContext

/**
 * `/admin-auth/{login,logout,me,change-password}` 路由。
 *
 * 4 个端点：
 * - `POST /admin-auth/login`     —— 验密 → Set-Cookie + 200 LoginResponse / 401
 * - `POST /admin-auth/logout`    —— 删 session + 清 cookie + 204
 * - `GET  /admin-auth/me`        —— 取当前登录 admin（含权限列表）/ 401
 * - `POST /admin-auth/change-password` —— 改密 + 删其他 session / 401 / 400
 *
 * Cookie 属性：HttpOnly + Secure + SameSite=Lax + Path=/，过期跟 sessionTtlSeconds 走。
 * Secure 由配置开关：本地开发 application.conf 可设 false，生产 true。
 */
fun Route.adminAuthRoutes(ctx: AdminContext) {
    route("/admin-auth") {
        post("/login") { handleLogin(ctx) }
        post("/logout") { handleLogout(ctx) }
        get("/me") { handleMe(ctx) }
        post("/change-password") { handleChangePassword(ctx) }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.handleLogin(ctx: AdminContext) {
    val req = try {
        call.receive<LoginRequest>()
    } catch (e: Throwable) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求体无效: ${e.message}"))
        return
    }
    val (userAgent, ip) = call.clientAuditFields()
    val token = ctx.authService.login(req.username, req.password, userAgent, ip)
    if (token == null) {
        // 故意不区分"用户不存在" / "密码错" / "账号被禁"——避免账号枚举
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("用户名或密码错误"))
        return
    }
    call.response.cookies.append(buildSessionCookie(ctx, token))

    val user = ctx.authService.validate(token)
    if (user == null) {
        // 极端情况：刚写入又被并发清理。fall through 让前端重试登录
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("会话创建失败，请重试"))
        return
    }
    call.respond(LoginResponse(AdminUserDto.from(user)))
}

private suspend fun PipelineContext<Unit, ApplicationCall>.handleLogout(ctx: AdminContext) {
    val token = call.adminToken()
    ctx.authService.logout(token)
    call.response.cookies.append(buildExpiredCookie(ctx))
    call.respond(HttpStatusCode.NoContent)
}

private suspend fun PipelineContext<Unit, ApplicationCall>.handleMe(ctx: AdminContext) {
    val user = call.requireAdmin(ctx) ?: return
    call.respond(AdminUserDto.from(user))
}

private suspend fun PipelineContext<Unit, ApplicationCall>.handleChangePassword(ctx: AdminContext) {
    val user = call.requireAdmin(ctx) ?: return
    val req = try {
        call.receive<ChangePasswordRequest>()
    } catch (e: Throwable) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求体无效: ${e.message}"))
        return
    }
    if (req.newPassword.length < AdminAuthService.MIN_PASSWORD_LEN) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("新密码至少 ${AdminAuthService.MIN_PASSWORD_LEN} 位"),
        )
        return
    }
    val result = ctx.authService.changePassword(
        adminId = user.id,
        keepToken = call.adminToken(),
        oldPassword = req.oldPassword,
        newPassword = req.newPassword,
    )
    when (result) {
        AdminAuthService.ChangePasswordResult.Success ->
            call.respond(HttpStatusCode.NoContent)
        AdminAuthService.ChangePasswordResult.WrongOldPassword ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("旧密码错误"))
        AdminAuthService.ChangePasswordResult.AdminNotFound ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("账号不存在"))
    }
}

private fun buildSessionCookie(ctx: AdminContext, token: String): Cookie = Cookie(
    name = ADMIN_COOKIE_NAME,
    value = token,
    maxAge = ctx.config.sessionTtlSeconds.toInt(),
    path = "/",
    domain = ctx.config.cookieDomain,
    secure = ctx.config.cookieSecure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
    encoding = CookieEncoding.RAW,
)

private fun buildExpiredCookie(ctx: AdminContext): Cookie = Cookie(
    name = ADMIN_COOKIE_NAME,
    value = "",
    maxAge = 0,
    path = "/",
    domain = ctx.config.cookieDomain,
    secure = ctx.config.cookieSecure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
    encoding = CookieEncoding.RAW,
)

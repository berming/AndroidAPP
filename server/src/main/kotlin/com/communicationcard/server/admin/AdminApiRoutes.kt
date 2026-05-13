package com.communicationcard.server.admin

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * `/admin/api/...` 监控端点（PR 2）。
 *
 * 6 个 GET 端点 + 1 个权限策略：所有路由都先调
 * [requirePermission]（`MONITOR_READ` 或 `GAME_HISTORY_READ`）。
 *
 * 设计原则（CLAUDE.md 约束 9 + 10）：
 * - 房间维度数据走 [SnapshotBuilder.room]：短暂持锁取 [RoomSnapshot]，
 *   锁外渲染 DTO
 * - 跨房间数据（overview / players / sessions）lock-free 读
 *   [java.util.concurrent.ConcurrentHashMap]
 * - DTO 字段全部脱敏（前 8 hex UUID）；不暴露 hands 内容
 */
fun Route.adminApiRoutes(ctx: AdminContext) {
    route("/admin/api") {
        get("/overview") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            call.respond(ctx.snapshotBuilder.overview())
        }

        get("/rooms") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            call.respond(ctx.snapshotBuilder.rooms())
        }

        get("/rooms/{id}") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            val id = call.parameters["id"].orEmpty()
            val detail = ctx.snapshotBuilder.room(id)
            if (detail == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("房间不存在"))
            } else {
                call.respond(detail)
            }
        }

        get("/players") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            call.respond(ctx.snapshotBuilder.players())
        }

        get("/sessions") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            call.respond(ctx.snapshotBuilder.sessions())
        }

        get("/games") {
            call.requirePermission(ctx, AdminPermission.GAME_HISTORY_READ) ?: return@get
            val from = call.request.queryParameters["from"]?.toLongOrNull()
            val to = call.request.queryParameters["to"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respond(ctx.historyStore.listSummaries(from, to, limit))
        }

        get("/games/{id}") {
            call.requirePermission(ctx, AdminPermission.GAME_HISTORY_READ) ?: return@get
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("id 不是数字"))
            val detail = ctx.historyStore.findDetail(id)
            if (detail == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("游戏不存在"))
            } else {
                call.respond(detail)
            }
        }

        // PR 3: 告警 ----------------------------------------------------

        get("/alerts") {
            call.requirePermission(ctx, AdminPermission.MONITOR_READ) ?: return@get
            val unackOnly = call.request.queryParameters["unack"]?.toBooleanStrictOrNull() ?: false
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val list = if (unackOnly) {
                ctx.alertStore.listUnacked(limit)
            } else {
                ctx.alertStore.listRecent(limit)
            }
            call.respond(list)
        }

        post("/alerts/{id}/ack") {
            val user = call.requirePermission(ctx, AdminPermission.ALERT_ACK) ?: return@post
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("id 不是数字"))
            val ok = ctx.alertStore.ack(id, user.id, System.currentTimeMillis())
            if (ok) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("告警不存在或已 ack"))
            }
        }
    }
}

package com.communicationcard.server

import java.util.concurrent.ConcurrentHashMap

/**
 * 把 [ServerRoomManager] / [ServerGameManager] / sessions 三个全局状态容器
 * 打包成一个不可变引用，方便后续模块（admin 监控、告警引擎、历史持久化）
 * 在 Application 启动后拿到统一的注入点。
 *
 * 设计原则：data class 字段全部 val；内部容器仍是可变的（rooms / sessions
 * 是 ConcurrentHashMap）。本对象只是"句柄打包"，不接管生命周期。
 *
 * `startedAtEpochMs` 在 PR 2 的 /admin/api/overview 接口里返回 uptime；
 * PR 0 顺手填好。
 */
data class ServerContext(
    val roomManager: ServerRoomManager,
    val gameManager: ServerGameManager,
    val sessions: ConcurrentHashMap<String, GameSession>,
    val startedAtEpochMs: Long,
)

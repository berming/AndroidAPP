package com.communicationcard.server.admin

/**
 * Admin 角色（Phase 1：仅两种）。
 *
 * 后续模块（4 玩家纪律 / 6 配置写 / 7 admin 管理）会扩展更细颗粒度；
 * 当前 MVP 范围（数据总览 + 实时监控）下，SUPER 和 OPS 都有读权限，
 * 仅 SUPER 可以做"管理 admin / 改配置"等敏感动作（路由在未来 PR 加）。
 */
enum class AdminRole {
    SUPER_ADMIN,
    OPS_ADMIN;

    companion object {
        fun fromString(value: String): AdminRole? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Admin 权限位（细颗粒度）。
 *
 * 设计原则：路由声明"我需要哪些权限"，角色定义"我拥有哪些权限"；
 * 新增功能加一个 [AdminPermission]，按角色矩阵分配到 [ROLE_PERMISSIONS]。
 */
enum class AdminPermission {
    /** 读看板 / 房间 / 玩家 / 会话 / 历史游戏概要 */
    MONITOR_READ,

    /** 标记告警为已处理（acked） */
    ALERT_ACK,

    /** 读历史游戏详情（含座位 / 队伍 / 分值）*/
    GAME_HISTORY_READ,

    /** 改 admin 账号 / 角色（模块 7，MVP 不暴露） */
    USER_MANAGE,

    /** 禁言 / 封禁玩家（模块 4，MVP 不暴露） */
    PLAYER_DISCIPLINE,

    /** 写运行时配置：维护模式 / 公告等（模块 6，MVP 不暴露） */
    CONFIG_WRITE,
}

/**
 * 角色 → 权限矩阵。**硬编码**，新增角色或调整权限位需改代码 + 测试。
 *
 * MVP 实际生效的只有 MONITOR_READ / ALERT_ACK / GAME_HISTORY_READ；
 * 其余位为后续 PR 预留，不在本 PR 暴露 API。
 */
val ROLE_PERMISSIONS: Map<AdminRole, Set<AdminPermission>> = mapOf(
    AdminRole.SUPER_ADMIN to AdminPermission.entries.toSet(),
    AdminRole.OPS_ADMIN to setOf(
        AdminPermission.MONITOR_READ,
        AdminPermission.ALERT_ACK,
        AdminPermission.GAME_HISTORY_READ,
    ),
)

fun AdminRole.hasPermission(permission: AdminPermission): Boolean =
    ROLE_PERMISSIONS[this]?.contains(permission) == true

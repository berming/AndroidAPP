package com.communicationcard.server.admin.alert

/**
 * 一条告警**候选**，[AlertEngine] 在每次 tick 中对每条规则 evaluate 后产出 0..N 条
 * candidate；engine 再按 cooldown 去重，决定是否真的入 alerts 表。
 *
 * 把"产出"和"入库"分离：规则只负责"现在这状态是否值得告警"，去重 / 持久化 / API
 * 暴露由上层管。这样规则单元测试可以纯函数式验证（给定 ctx → 期待 candidate）。
 */
data class AlertCandidate(
    val rule: String,
    val severity: String,             // "INFO" | "WARN" | "ERROR"
    val roomId: String? = null,
    val playerIdMasked: String? = null,
    val message: String,
    val payload: Map<String, String> = emptyMap(),
)

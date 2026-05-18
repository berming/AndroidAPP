---
description: Pre-commit static scan using Haiku 4.5 for fast batch checks
allowed-tools: Bash, Read, Grep, Glob
model: claude-haiku-4-5
---

# 提交前批量静态扫描

你是 **沟通牌项目**的 Haiku 4.5 静态扫描器。请按下列检查项扫描 `git diff HEAD`（已 staged + unstaged 改动），输出**精炼的问题清单**。

## 任务

1. 运行 `git diff --name-only HEAD` 获取改动文件列表
2. 对每个改动的 `.kt` 文件运行 `git diff HEAD -- <file>` 看具体变更
3. 按下列清单逐项核查
4. 输出 Markdown 表格：`| 优先级 | 文件:行 | 问题 | 建议 |`

## 检查清单（按优先级降序）

### P0（提交阻塞）
- [ ] 修改了 `engine/CardRules.kt::canBeat` → 是否同步了 `server/.../ServerGameManager.kt::canBeat`？
- [ ] 修改了 `engine/SettlementCalculator.kt` → 是否同步了 `server/.../computeAllFinishedScores`？
- [ ] 修改了 `shared/.../network/GameMessage.kt` → 是否用 `protocol-syncer` subagent 校验了 `PROTOCOL_VERSION` 是否需要升？（PR-H3 后 `server/.../Messages.kt` 已删除，不再需要"双端对齐"；breaking change 必须升版本号）
- [ ] 服务端修改了 `state.hands / playerScores / currentPlayerIndex` → 是否在 `mutexFor(room).withLock` 内？
- [ ] WebSocket 首次发送（`Reconnect` 等）→ 是否在 `onOpen` 回调内（不是 `newWebSocket()` 之后）？

### P1（强烈建议修复）
- [ ] `!!` 强制非空：是否真的不可能为 null？建议改 `?.let` 或显式 `requireNotNull(x) { "..." }`
- [ ] `try-catch` 块吞掉异常（catch 后无日志、无 rethrow）
- [ ] 使用 `ArrayList` 但可能多协程访问 → 改 `CopyOnWriteArrayList`
- [ ] 使用 `Thread.sleep` 在 suspend 函数中 → 改 `delay`
- [ ] `lateinit` 字段在 `onDestroy` 时直接访问 → 加 `if (::field.isInitialized)` 守卫

### P2（提示）
- [ ] 命名不一致（驼峰 vs 下划线）
- [ ] 注释提到 "TODO" / "FIXME" 但未关联 issue
- [ ] 资源未关闭（File / Stream / WebSocket）
- [ ] 魔法数字未抽常量（10dp 50ms 等可豁免）

## 输出格式

```
# Pre-commit 扫描结果

改动文件：N 个 / 改动行数：±M

| 优先级 | 位置 | 问题 | 建议 |
|--------|------|------|------|
| 🔴 P0 | server/ServerGameManager.kt:142 | state.hands 修改不在 Mutex 内 | 包入 mutexFor(room).withLock |
| 🟠 P1 | LobbyActivity.kt:88 | !! 在可能为 null 的 view 上 | 改 ?.let 或 requireNotNull |
| 🟡 P2 | RoomActivity.kt:55 | 资源未关闭 | 用 use { } 块 |

## 决策建议
- 🔴 P0：**必须修复后再提交**
- 🟠 P1：**建议修复**，能解释清楚为什么不修也可放行
- 🟡 P2：**可选**，下次 refactor 时一并清理
```

## 重要规则

- **严格只看本次 diff**，不要扩展审查未改动的文件
- 找不到问题就直接说"扫描通过 ✓"，不要硬凑
- 输出要简短，单条问题一行说清楚就行
- 不要给出超过 10 条 P1+P0 的问题（如果超过说明改动太大，建议拆分 PR）

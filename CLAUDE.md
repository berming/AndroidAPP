# 沟通牌项目 — Claude Code 工作规约

> 适用于：Android 客户端 (`apps/communication-card`) + Ktor 服务端 (`server/`)
> 本文是 Claude Code 在本仓库工作时必读的"工作手册"。

---

## 一、模型选择策略

| 任务类型 | 模型 | 用法 |
|---------|------|------|
| 架构设计 / 协议定义 / 根因分析 | **Claude Opus 4.7**（1M 上下文）| 复杂逻辑必走 Opus；新会话开 `/model claude-opus-4-7` |
| 主要实现 / 重构 / PR review | **Claude Sonnet 4.6** | 默认 |
| 静态扫描 / TDD 用例生成 / 批量小修 | **Claude Haiku 4.5** | `/pre-commit-scan` 默认用 Haiku |

切换：在 Claude Code 内 `/model <model-id>`。

---

## 二、关键工程约束（生成代码前必读）

这些约束源于本项目历史 Bug 教训，违反任一条都会导致回归。

### 约束 1：单机 / 联网共享逻辑必须一致
| 单机（客户端） | 联网（服务端） | 说明 |
|---------------|--------------|------|
| `engine/CardRules.kt` 的 `canBeat` | `server/ServerGameManager.kt` 的 `canBeat` | 两份必须等价 |
| `engine/SettlementCalculator.kt` | `server/ServerGameManager.kt` 的 `computeAllFinishedScores` | 公式必须等价 |
| 回合归属 | `handleRoundEnd` 设 `currentPlayerIndex = winnerId` | 赢家是下轮首家 |

> 改动其中一份，必须同步另一份；否则联网游戏会出现两端不一致的 Bug。

### 约束 2：服务端并发安全
- 任何修改 `ServerGameState.hands / playerScores / currentPlayerIndex` 的代码 → **必须在 `mutexFor(room).withLock { ... }` 内**
- **广播必须在锁外**（避免慢客户端阻塞房间所有动作）
- `room.players` 必须是 `CopyOnWriteArrayList`（不能用普通 ArrayList）

### 约束 3：WebSocket 时序
- 客户端 `OkHttp` 的 `client.newWebSocket()` 是异步的，**返回时连接仍在 CONNECTING**
- 任何首次发送（如 `Reconnect` 消息）**必须在 `onOpen` 回调内**，否则 `send()` 会静默丢弃

### 约束 4：协议消息双端对齐
- `apps/.../network/GameMessage.kt`（客户端）
- `server/src/main/kotlin/.../Messages.kt`（服务端）
- **任一字段增删改都必须同步另一边**
- 枚举值（如 `CardSuit`）两端的字符串必须一致（不要一边 `CLUB` 一边 `clubs`）

### 约束 5：会话 ID 完整性
- 服务端 `sessionId` 用**完整 36 字符 UUID**，不要截断（避免碰撞）

---

## 三、关键路径强制 TDD

修改以下文件**必须先写失败测试**，再实现修复：

| 文件 | 测试位置 |
|------|---------|
| `engine/SettlementCalculator.kt` | `apps/.../test/.../SettlementCalculatorTest.kt`（已有 15 用例）|
| `engine/CardRules.kt` | 待补 |
| `server/.../ServerGameManager.kt` 的 `canBeat` / `handleRoundEnd` / `checkGameEnd` / `computeAllFinishedScores` | 待补 |

> 教训：单机版结算因为有 15 个测试，3 个月没出过 Bug；联网版没测试，反复出问题。

---

## 四、提交前自查

提交前运行：
```
/pre-commit-scan
```
（使用 Haiku 4.5 批量扫描 null safety / 异常路径 / 共享逻辑一致性）

---

## 五、PR 流程（4 关）

提交 PR 后必须等齐 4 个绿灯：

1. **CI 绿**（GitHub Actions：tests + detekt 全部通过）
2. **Codex Bot review**（chatgpt-codex-connector 评论无 P1）
3. **Claude PR review**（开新会话 `/review`，确认无 P0/P1）
4. **真机验证**（至少 happy path + 1 个边界场景）

详见 `.github/pull_request_template.md`。

---

## 六、构建命令速查

```bash
# 客户端
./gradlew :apps:communication-card:assembleDebug
./gradlew :apps:communication-card:test

# 服务端（独立 Gradle 工程）
cd server && ./gradlew test
cd server && ./gradlew run

# 静态分析
./gradlew detekt
```

---

## 七、文档参考

- `docs/architecture.md` — 项目架构总览
- `docs/multiplayer_guide.md` — 联网部署 / 协议 / 调试
- `docs/settlement_verification.md` — 结算公式（含 15 验证用例）
- `docs/dev_summary.md` — 开发实践总结（多 AI 协同方案）

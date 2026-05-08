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

---

## 八、Commit 署名规范（遵循 Linux 内核《AI 编程助手》）

每次 AI 辅助的提交必须在 commit 信息底部加上**两行**：

```
Signed-off-by: <人类作者> <人类邮箱>
AI-Assisted-By: <提交此 commit 时实际使用的模型>
```

> ⚠️ **`AI-Assisted-By` 必须反映该次 commit 实际使用的模型**，不能写成固定值。
> 切换模型（`/model` 命令）后，下一次 commit 必须用新模型的标识。

### 当前会话如何确定模型

Claude Code 会话中：
- 会话启动时的 system context 会注明 "powered by the model named ..."
- `/model` 命令切换后立即生效，下一次 commit 即按新模型署名

### 模型标识写法（严格匹配模型 ID）

| 实际模型 | `AI-Assisted-By:` 值 |
|---------|---------------------|
| Claude Opus 4.7（含 1M 上下文）| `Claude Opus 4.7 (claude-opus-4-7, 1M context)` |
| Claude Opus 4.7（标准）| `Claude Opus 4.7 (claude-opus-4-7)` |
| Claude Sonnet 4.6 | `Claude Sonnet 4.6 (claude-sonnet-4-6)` |
| Claude Haiku 4.5 | `Claude Haiku 4.5 (claude-haiku-4-5-20251001)` |
| 其他第三方 AI | `<vendor> <model> (<model-id>)` |

### 完整示例（用 Opus 实际工作的 commit）

```
feat: add per-room mutex to serialize state mutations

Multiple coroutines could race on state.hands. Each room now has
its own kotlinx.coroutines.sync.Mutex; mutations happen inside
withLock, broadcasts outside the lock to avoid I/O blocking.

Signed-off-by: berming <bermin@live.cn>
AI-Assisted-By: Claude Opus 4.7 (claude-opus-4-7, 1M context)
```

### 多模型协同的写法

一次提交跨多个模型（如 Opus 设计 + Sonnet 实现）时，列多行 `AI-Assisted-By:`：

```
Signed-off-by: berming <bermin@live.cn>
AI-Assisted-By: Claude Opus 4.7 (claude-opus-4-7, 1M context) — architecture
AI-Assisted-By: Claude Sonnet 4.6 (claude-sonnet-4-6) — implementation
AI-Assisted-By: Claude Haiku 4.5 (claude-haiku-4-5-20251001) — pre-commit scan
```

### 不需要署名的情况

- 纯人工修改（无 AI 介入）
- 自动化工具生成（如 lint --fix、formatter）

> 责任原则：`Signed-off-by` 行的人类作者对该提交承担最终责任，AI 仅为辅助工具记录。


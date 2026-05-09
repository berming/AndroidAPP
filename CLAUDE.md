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

> **2026-05 更新**：客户端的牌型规则、结算公式、游戏引擎、AI、协议 DTO 全部抽到了
> `:shared` 多平台模块。Android 客户端、Web 客户端（`:apps:web`）以及未来的
> iOS/桌面客户端都依赖同一份 `:shared`，**客户端侧不再可能出现"两份 canBeat 不一致"
> 的回归**。但服务端仍持有自己的 `Messages.kt`/`canBeat`，下表的同步约束仍然有效。

| 多平台共享（客户端） | 联网（服务端） | 说明 |
|---------------------|--------------|------|
| `shared/.../engine/CardRules.kt` 的 `canBeat` | `server/ServerGameManager.kt` 的 `canBeat` | 两份必须等价 |
| `shared/.../engine/SettlementCalculator.kt` | `server/ServerGameManager.kt` 的 `computeAllFinishedScores` | 公式必须等价 |
| 回合归属 | `handleRoundEnd` 设 `currentPlayerIndex = winnerId` | 赢家是下轮首家 |

> 改动 `:shared` 中的任一份，必须同步服务端对应实现；否则联网游戏会出现两端不一致的 Bug。
> （后续可考虑让 server 也直接依赖 `:shared` 以彻底消除这条约束。）

### 约束 2：服务端并发安全
- 任何修改 `ServerGameState.hands / playerScores / currentPlayerIndex` 的代码 → **必须在 `mutexFor(room).withLock { ... }` 内**
- **广播必须在锁外**（避免慢客户端阻塞房间所有动作）
- `room.players` 必须是 `CopyOnWriteArrayList`（不能用普通 ArrayList）

### 约束 3：WebSocket 时序
- 客户端 `OkHttp` 的 `client.newWebSocket()` 是异步的，**返回时连接仍在 CONNECTING**
- 任何首次发送（如 `Reconnect` 消息）**必须在 `onOpen` 回调内**，否则 `send()` 会静默丢弃

### 约束 4：协议消息双端对齐
- `shared/src/commonMain/kotlin/.../network/GameMessage.kt`（**所有客户端共享**：Android / Web / 未来 iOS/Desktop）
- `server/src/main/kotlin/.../Messages.kt`（服务端）
- **任一字段增删改都必须同步另一边**
- 枚举值（如 `CardSuit`）两端的字符串必须一致（不要一边 `CLUB` 一边 `clubs`）
- 客户端侧由于已抽到 `:shared`，多端之间不会再出现协议漂移；只需对齐 client ↔ server。

### 约束 5：会话 ID 完整性
- 服务端 `sessionId` 用**完整 36 字符 UUID**，不要截断（避免碰撞）

---

## 三、关键路径强制 TDD

修改以下文件**必须先写失败测试**，再实现修复：

| 文件 | 测试位置 | 状态 |
|------|---------|------|
| `shared/.../engine/SettlementCalculator.kt` | `shared/src/commonTest/.../SettlementCalculatorTest.kt` | ✓ 15 用例 |
| `shared/.../engine/CardRules.kt` | `shared/src/commonTest/.../CardRulesTest.kt` | ✓ ~30 用例（PR-H2 引入）|
| `server/.../ServerGameManager.kt` 的 `canBeat` / `handleRoundEnd` / `checkGameEnd` / `computeAllFinishedScores` | `server/src/test/.../ServerGameManagerTest.kt` | ✓ ~25 用例（PR-H2 引入）|

CI 的 `tdd-gate` job 会 mechanically 校验：上表中任一文件被改但对应
`*Test.kt` 没动，CI 直接红。详见 `.github/workflows/android-ci.yml`。

> 教训：单机版结算因为有 15 个测试，3 个月没出过 Bug；联网版没测试，反复出问题。
> 历史 Bug 的完整存档：`docs/regressions.md`（每条带防回归测试名）。

> 修 Bug 的标准流程（"修了又坏"防火墙）：`docs/playbooks/bug-triage.md`。

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

新功能开发的标准流程：`docs/playbooks/feature-development.md`（Loop A）。
CI 失败排错路由：`docs/playbooks/ci-failure-triage.md`（Loop D）。

---

## 六、构建命令速查

```bash
# 共享模块（所有客户端依赖）
./gradlew :shared:jvmTest                       # 跑 commonTest（含 15 个结算用例）
./gradlew :shared:assemble                      # 编译所有 target

# Android 客户端
./gradlew :apps:communication-card:assembleDebug

# Web 客户端（Compose Multiplatform / Wasm-JS，浏览器版本）
./gradlew :apps:web:wasmJsBrowserDevelopmentRun  # 启本地 dev 服务器（默认 8080），热重载
./gradlew :apps:web:wasmJsBrowserDistribution    # 输出生产包到 apps/web/build/dist/wasmJs/productionExecutable

# 服务端（PR-H3 起为 :server 子项目，依赖 :shared）
./gradlew :server:test
./gradlew :server:run                            # 监听 :8080，提供 /game WebSocket

# 静态分析
./gradlew detekt
```

### 模块层次

```
:shared                          KMP（android + jvm + wasmJs targets）
  commonMain/
    model/      Card · Deck · Player
    engine/     CardRules · SettlementCalculator · GameEngine
    ai/         AIPlayer
    network/    GameMessage（+ 所有 SerializedXxx DTO）
  commonTest/   SettlementCalculatorTest（15 用例，kotlin.test）

:apps:communication-card         Android 视图层（依赖 :shared）
:apps:web                        Compose Multiplatform / Wasm-JS（依赖 :shared）
:server                          Ktor 后端（PR-H3 起依赖 :shared，约束 1/4 已编译期消除）
```

### Web 客户端架构速读

- 入口：`apps/web/src/wasmJsMain/kotlin/.../web/Main.kt` → `ComposeViewport(document.body) { App() }`
- 状态机：`viewmodel/AppViewModel.kt` 持有 `StateFlow<Screen>`；屏幕枚举 `Screen.{Home,Lobby,Room,Game,Settlement}`
- 网络：浏览器原生 `WebSocket` 包装在 `net/WebSocketTransport.kt`；
  `NetworkClient` / `RoomManager` / `GameSyncManager` 与 Android 端职责对等
- 单机模式：`singleplayer/SinglePlayerEngine.kt` 包装 `:shared` 的 `GameEngine`，
  把本地 `Card/Player` 状态映射成 `SerializedGameState` 后由 UI 渲染（与联网模式同一套渲染层）

---

## 七、文档参考

**项目档案**

- `docs/architecture.md` — 项目架构总览
- `docs/multiplayer_guide.md` — 联网部署 / 协议 / 调试
- `docs/settlement_verification.md` — 结算公式（含 15 验证用例）
- `docs/dev_summary.md` — 开发实践总结（多 AI 协同方案）

**Harness Engineering**（PR-H1/H2 引入）

- `docs/regressions.md` — 历史 Bug 数据库；每条 8 字段（症状 / 根因 /
  修复 commit / 教训 / 防回归测试）。新会话开局必扫一遍，避免重复踩坑。
- `docs/playbooks/feature-development.md` — Loop A：新功能开发 happy path
- `docs/playbooks/bug-triage.md` — Loop B："修了又坏"防火墙
- `docs/playbooks/ci-failure-triage.md` — Loop D：CI 失败标准化排错
  （含"沙箱里读不到 CI 日志？把 gradle 输出 exfil 到 PR 评论"模式）

**Slash commands & Hooks**（`.claude/` 下）

- `/test-fast` — `:shared:jvmTest` 30s 快反馈
- `/ship-check` — push 前 4 关本地校验
- `/pre-commit-scan` — Haiku 4.5 批量扫 5 大约束
- `/align-server-shared` — diff 客户端/服务端 canBeat & Messages（PR-H3 后退役）
- `.claude/hooks/{SessionStart,PostToolUse,UserPromptSubmit}.sh` — 启动摘要 /
  关键路径 TDD 提醒 / push 关键词提醒
- `.githooks/{pre-push,commit-msg}` — 自动跑 :shared:jvmTest / 校验署名两行
  （需要 `git config core.hooksPath .githooks` 一次性启用）

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


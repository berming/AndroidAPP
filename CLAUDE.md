# 沟通牌项目 — Claude Code 工作规约

> 适用于：Android 客户端 (`apps/android`) + Ktor 服务端 (`server/`)
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

### 约束 6：单机 / 联网 共用同一套游戏 UI（不许两套渲染层）

**每个端**的"单机 AI 对战"和"多人联网对战"必须共用**同一套游戏屏幕渲染代码**
（同一组 Composable / Activity / View tree），不允许各做一套 UI 后反复双向同步调优。

- 实现方式：单机引擎把本地 `GameEngine` 的状态**映射成与服务端推送同构的
  `SerializedGameState`**，再喂给同一渲染层；联网模式直接用服务端推送。
- 参考实现：`apps/web/.../singleplayer/SinglePlayerEngine.kt` 包装
  `:shared.GameEngine`，与 `GameSyncManager` 输出同一数据形状，
  `GameScreen` 不区分来源。
- Android 端的 `GameActivity`（单机）与 `OnlineGameActivity`（联网）**应当**收敛到
  同一套渲染（共享 fragment / 自定义 view），新功能默认两边同时可见、同时调优。
- **反模式**：
  - 复制一份 game UI 给单机用并悄悄改样式
  - 单机走"快路径"绕过 `SerializedGameState`、自己渲染 Composable
  - 在某一模式下私自加交互而不在另一模式同步

> 教训：UI 双份必然漂移；改一边忘另一边是典型的"修了又坏"。把两条数据路径
> 合到同一渲染入口能把回归面减半。

### 约束 7：UI 适配以「Android 优化版」为基线

目前 **Android 客户端的 UI 已经过若干轮优化**，作为各端布局的事实基线。新端 / 新形态
默认**先参考 Android 版的布局结构、间距、信息密度**，再按目标终端屏幕尺寸适配，
不要从零重新设计。

- 参考维度：手牌区位置、玩家头像排布、中央出牌区、操作按钮分组、字号层级
- 适配方向：保留 Android 版的**信息架构**，按 LayoutMode 三档（Compact / Medium /
  Expanded）调整尺寸 / 列数 / 是否折行，**不动信息层级**
- 偏离 Android 基线的设计需要在 PR 描述里写明理由（例如平台 HIG 强制要求）

### 约束 8：设备分级与玩家数 / 牌副数上限（小屏禁用 8+）

**两条正交规则**：先看终端 → 算允许的玩家数；再看玩家数 → 算允许的牌副数。

**(a) 终端 → 玩家数**

| 终端类型 | LayoutMode | 允许的玩家数模式 |
|---------|-----------|------------------|
| 手机（普通竖屏 / 折叠机折叠态）| Compact (< 600 dp) | **仅 6 人** |
| 平板 / 三折手机展开态 | Medium (600–1200 dp) | 6 / 8 人 |
| 桌面 / Web 大窗 | Expanded (≥ 1200 dp) | 6 / 8 / 10 / 12 人 |

**(b) 玩家数 → 牌副数**

| 玩家数 | 允许牌副数 | 每人手牌张数（取 max 副数）|
|-------:|-----------|--------------------------:|
| 6 人 | 4 / 6 副 | 36 / 54 |
| 8 人 | 6 / 8 副 | 40 / 54 |
| 10 人 | 8 / 10 副 | 43 / 54 |
| 12 人 | 8 / 10 / 12 副 | 36 / 45 / 54 |

> 副数下限的设计原则：保证每人至少 ~36 张手牌；上限固定为"每人一副"
> （54 张），让 12 人局也能合理分到分值牌。

**强制规则**

- **小屏 MUST 禁用 8 / 10 / 12 人模式**（创建房间 UI 隐藏或 disable，并提示"该模式
  需要平板及以上屏幕"）
- 服务端在 `CreateRoom` 收到带 `expectedPlayerCount > 6` 但客户端 `screenClass = Compact`
  的请求时**应拒绝**（防止前端绕过；具体 enforcement 路径见
  `docs/feature_spec.md` §2.7）
- 服务端 MUST 校验 `(playerCount, deckCount)` 组合落在上表 (b) 内，否则拒绝
- `Deck.deal()` 当前 `require(playerCount in listOf(6, 8, 10, 12))`；`Deck.reset()`
  当前固定 4 副。扩展到 6/8/10/12 副需先在 `:shared/Deck.kt` 把 `reset(deckCount)`
  参数化、补 `commonTest`，再放到 UI（详见 `docs/feature_spec.md` §2.7）

### 约束 9：admin 端点必须 lock-safe，绝不在 mutex 内做 IO

**适用范围**：`/admin-auth/*` 和 `/admin/api/*` 下的所有路由（PR 1 起；PR 2
监控 + PR 3 告警 + PR 5 历史等都遵守此约束）。

- admin 路由的处理路径**不得**在 `mutexFor(room).withLock { ... }` 内做：
  - 文件 IO / SQLite 写入 / 网络发送
  - JSON 序列化 / DTO 渲染（toList / toMap 也尽量在锁外）
- 允许的两种模式：
  - **(a) 短暂持锁取 immutable snapshot**：进锁 → `RoomSnapshot` data class
    defensive copy → 出锁 → 渲染 JSON。参考 PR 2 的 `SnapshotBuilder`
  - **(b) 完全 lock-free 读取 `ConcurrentHashMap`**：`rooms.values.toList()`
    / `roomsByCode.entries.toList()` 等弱一致快照后再处理（适合 overview 这种
    粗粒度查询）
- 写 SQLite 走 `AdminDb.withConnection`：内部已经 `Mutex + Dispatchers.IO`
  序列化；admin 调用方**不要**再嵌套 `mutexFor`

**禁忌示例**：
```kotlin
// ❌ 反例：把 SQLite 写入嵌进游戏 mutex
mutexFor(room).withLock {
    val record = GameRecord.capture(room, gameResult)
    historyStore.insertSync(record)   // ← 磁盘 IO 阻塞所有动作
}

// ✓ 正例：锁内只构造 immutable record，锁外 enqueue 异步入库
val record = mutexFor(room).withLock { GameRecord.capture(room, gameResult) }
historyStore.enqueue(record)          // 锁外 Channel send，纳秒级
```

**教训**：admin 路由属于"运维"层，慢的运维查询绝不能阻塞游戏关键路径；同时
admin 读取必须看到一致的房间状态（不可在并发写中迭代裸 MutableMap）。

### 约束 10：admin DTO 必须脱敏，绝不暴露手牌或完整 UUID

任何 admin 模块的 `@Serializable` DTO（含 `/admin/api/*` 响应 + 历史
持久化表行）：

- `playerId` / `sessionId` MUST 截断为前 8 hex + `...`（如 `fa8c91d2...`）；
  保留 `AI_N` 形式的 AI id 原样
- `hands` / `card` 等真实牌面 MUST NOT 出现；只允许 `handSize`（牌的数量）
- `password_hash` / session token / 任何密钥字段 MUST NOT 序列化进任何返回 DTO
- 玩家姓名 `playerName` 可以原样保留（运维需要靠名字定位玩家）
- IP / user-agent 仅出现在 admin_sessions 行（自己人审计用），**不**进面向
  前端的 DTO

**教训**：admin 是"运维"层，任何完整 UUID / 真实牌面泄漏都会让运维变成
上帝模式。把约束写在协议层比写在 review checklist 更可靠。

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
3. **Claude PR review**（`/review-pr <PR#>` 调 pr-reviewer subagent；
   PR-H5 引入；确认无 P0/P1）
4. **真机验证**（至少 happy path + 1 个边界场景）

详见 `.github/pull_request_template.md`。

> **常驻行为约定**：Claude Code 主会话**每次 `git push` 之后都应主动**：
> (a) 等 60 秒；(b) 拉 PR review_comments + check_runs；(c) 发现 P0/P1/P2 直接修 + 回复 thread + push；
> (d) CI 红则查 PR comment 里 exfil 的 gradle 日志，定位 + 修。
> 不要等用户再提醒。`.claude/hooks/PostToolUse.sh` 的 Bash 分支会在 push 后注入这条提醒。

新功能开发的标准流程：`docs/playbooks/feature-development.md`（Loop A）。
CI 失败排错路由：`docs/playbooks/ci-failure-triage.md`（Loop D）。

---

## 六、构建命令速查

```bash
# 共享模块（所有客户端依赖）
./gradlew :shared:jvmTest                       # 跑 commonTest（含 15 个结算用例）
./gradlew :shared:assemble                      # 编译所有 target

# Android 客户端
./gradlew :apps:android:assembleDebug

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

:apps:android         Android 视图层（依赖 :shared）
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
- `docs/game_rules.md` — **沟通牌玩法权威定义**（玩家手册 + 开发参考；改规则先看这）
- `docs/multiplayer_guide.md` — 联网部署 / 协议 / 调试
- `docs/settlement_verification.md` — 结算公式（含 15 验证用例，数学规约）
- `docs/feature_spec.md` — 跨端功能规格（MUST/SHOULD/MAY 矩阵）
- `docs/client_implementation_guide.md` — 实现新客户端（iOS / Desktop / CLI）参考路径
- `docs/dev_summary.md` — 开发实践总结（多 AI 协同方案）

**Harness Engineering**（PR-H1/H2 引入）

- `docs/regressions.md` — 历史 Bug 数据库；每条 8 字段（症状 / 根因 /
  修复 commit / 教训 / 防回归测试）。新会话开局必扫一遍，避免重复踩坑。
- `docs/playbooks/feature-development.md` — Loop A：新功能开发 happy path
- `docs/playbooks/bug-triage.md` — Loop B："修了又坏"防火墙
- `docs/playbooks/ci-failure-triage.md` — Loop D：CI 失败标准化排错
  （含"沙箱里读不到 CI 日志？把 gradle 输出 exfil 到 PR 评论"模式）
- `docs/playbooks/adversarial-review.md` — 跨 vendor 对抗审查节奏
  （Codex 自动 / Opus 新会话 /review / 季度第二 vendor / 真机最后一关）
  （含"沙箱里读不到 CI 日志？把 gradle 输出 exfil 到 PR 评论"模式）

**Slash commands & Hooks**（`.claude/` 下）

- `/test-fast` — `:shared:jvmTest` 30s 快反馈
- `/ship-check` — push 前 4 关本地校验
- `/pre-commit-scan` — Haiku 4.5 批量扫 5 大约束
- `/trace-bug` — 用户 bug 报告 → 失败测试 commit（Loop B 入口；PR-H4 引入）
- `/review-pr <PR#>` — 调 pr-reviewer subagent 做对抗审查
  （PR-H5 引入；替代"开新会话 /review"）

**Subagents**（`.claude/agents/` 下）

- `protocol-syncer` — 当 GameMessage.kt 改动时校验 PROTOCOL_VERSION
  bump（PR-H4 引入）
- `tdd-scaffolder` — 关键路径函数 → 失败测试骨架（PR-H4 引入；
  被 `/trace-bug` 调用）
- `pr-reviewer` — 独立 context 拉 PR diff 跑 P0/P1/P2 rubric
  （PR-H5 引入；被 `/review-pr` 调用；模型 Opus 4.7）
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


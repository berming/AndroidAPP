# 沟通牌项目 · 架构总览

> 本文档反映 main 截至 PR #70（2026-05）的架构。任何模块边界变化必须在
> 同 PR 内更新本文。

---

## 一、4 模块布局

```
AndroidAPP/                                    Kotlin Multiplatform monorepo
├── settings.gradle.kts                        多模块入口
├── build.gradle.kts                           plugins (KMP 1.9.24, CMP 1.6.10)
├── gradle.properties / gradlew
│
├── :shared/                                   ★ 跨平台核心（Android + JVM + wasmJs）
│   └── src/
│       ├── commonMain/kotlin/com/communicationcard/game/
│       │   ├── model/        Card · CardRank · CardSuit · Deck · Player · Team
│       │   ├── engine/       CardRules · GameEngine · SettlementCalculator
│       │   ├── ai/           AIPlayer · AIDifficulty
│       │   └── network/      GameMessage（PROTOCOL_VERSION + 所有 Serialized*）
│       ├── commonTest/kotlin/.../engine/
│       │   ├── CardRulesTest.kt              ~30 用例
│       │   └── SettlementCalculatorTest.kt   ~15 用例
│       └── jvmTest/...                       同 commonTest 的 JVM target 入口
│
├── :server/                                   Ktor + Netty 后端（WebSocket /game + REST /admin/*）
│   └── src/main/kotlin/com/communicationcard/server/
│       ├── Application.kt                    主入口、WebSocket 路由、握手协议版本
│       ├── GameSession.kt                    每连接一个会话封装
│       ├── ServerRoomManager.kt              房间生命周期
│       ├── ServerGameManager.kt              游戏状态、AI 决策、回合计时
│       └── admin/                            Admin REST API（PR #61-70）
│           ├── AdminAuthRoutes.kt            /admin-auth/{login,logout,me,change-password}
│           ├── AdminApiRoutes.kt             /admin/api/{overview,rooms,games,alerts}
│           ├── AdminAuthPlugin.kt            requireAdmin / requirePermission（含临时 bypass）
│           ├── AdminDb.kt                    SQLite（/var/lib/communication-card/admin.db）
│           ├── AdminAuthService.kt           bcrypt 验密 + session token（URI_ENCODING）
│           ├── GameHistoryStore.kt           游戏历史持久化（Channel 异步入库）
│           ├── SnapshotBuilder.kt            lock-safe 快照（约束 9）
│           └── alert/                        AlertStore + AlertEngine（监控告警）
│
├── :apps:android/                  Android 客户端（XML 视图层）
│   └── src/main/java/com/communicationcard/game/
│       ├── ui/ (Activity 与 Adapter)
│       │   ├── MainActivity / GameActivity (单机)
│       │   └── ui/multiplayer/ (LobbyActivity → RoomActivity → OnlineGameActivity)
│       ├── network/ (NetworkManager · RoomManager · GameSyncManager · TextChatManager)
│       └── util/ (DebugLogManager 等)
│
├── :apps:web/                                 Web 客户端（Compose Multiplatform / Wasm-JS）
│   └── src/wasmJsMain/
│       ├── kotlin/com/communicationcard/game/web/
│       │   ├── Main.kt                       ComposeViewport 入口 + loader 移除
│       │   ├── ui/                           Home/Lobby/Room/Game/Settlement/Settings/Stats/Help
│       │   ├── viewmodel/                    AppViewModel + Screen sealed
│       │   ├── net/                          WebSocketTransport（@JsFun interop）
│       │   ├── singleplayer/                 SinglePlayerEngine（包装 :shared GameEngine）
│       │   └── storage/                      LocalStorage + UserPreferences + Statistics
│       └── resources/
│           ├── index.html                    + #loader 加载提示
│           └── fonts/NotoSansSC-Subset.ttf   GB2312 + 项目符号子集 (~3 MB)
│
├── apps/admin/                                Admin 控制台 SPA（Vue 3 + Element Plus）
│   └── dist/                                 npm run build 产物（rsync → /var/www/communication-card-admin/）
│
├── deploy/                                    自有服务器部署脚手架
│   ├── install.sh                            Ubuntu 22.04 一次性 bootstrap
│   ├── Caddyfile                             Caddy 反代模板（A: IP / B: HTTPS 域名）
│   └── communication-card-server.service     systemd unit
│
├── .github/                                   CI / harness
│   ├── workflows/
│   │   ├── android-ci.yml                    build + tdd-gate + sync-checkbox + 失败评论
│   │   └── deploy.yml                        push to main → SSH rsync 到自有服务器（opt-in）
│   └── pull_request_template.md              影响面 + 4 关验证清单
│
├── .claude/                                   Claude Code harness
│   ├── agents/ (protocol-syncer · tdd-scaffolder · pr-reviewer)
│   ├── commands/ (/test-fast · /pre-commit-scan · /ship-check · /trace-bug · /review-pr)
│   ├── hooks/ (SessionStart · PostToolUse · UserPromptSubmit)
│   └── settings.json                         hooks 注册 + 权限 allowlist
│
├── .githooks/                                 Git pre-push / commit-msg
│
└── docs/
    ├── architecture.md                       本文档
    ├── game_rules.md                         **沟通牌玩法权威定义**（玩家 + 开发者同款）
    ├── multiplayer_guide.md                  联网部署 / 协议 / 调试
    ├── web_client_architecture.md            Web 客户端深度文档
    ├── feature_spec.md                       Android vs Web 功能矩阵 + roadmap
    ├── client_implementation_guide.md        实现新客户端（iOS / Desktop / CLI）的参考路径
    ├── settlement_verification.md            结算公式 + 15 验证用例（数学规约）
    ├── regressions.md                        历史 P0/P1 Bug 数据库
    ├── dev_summary.md                        开发实践回顾（PR #1-#54 时期）
    ├── dev_summary.html                      ↑ 渲染版（含 inline SVG 架构图，浏览器打开）
    ├── build_html.py                         dev_summary.md → dev_summary.html 构建脚本
    └── playbooks/
        ├── adversarial-review.md             4 关 review 节奏
        ├── bug-triage.md                     "修了又坏"防火墙
        ├── ci-failure-triage.md              CI 失败排错
        ├── feature-development.md            新功能 Loop A
        └── web-deploy.md                     Web + Server 部署到自有服务器
```

---

## 二、技术栈

| 层 | 技术 |
|---|---|
| **共享核心** | Kotlin 1.9.24 Multiplatform（commonMain → android / jvm / wasmJs） |
| Android 客户端 UI | Android XML 布局（不使用 Jetpack Compose）|
| Web 客户端 UI | Compose Multiplatform 1.6.10 / Wasm-JS（Skia 渲染到 `<canvas>`）|
| 客户端语言 | Kotlin + Coroutines + Flow |
| Android WebSocket | OkHttp |
| Web WebSocket | 浏览器原生 WebSocket（`@JsFun` 包装；不引入 kotlinx-browser）|
| 服务端 | Ktor + Netty + WebSockets |
| 序列化 | kotlinx.serialization (JSON)，所有 Serialized* DTO 在 `:shared` |
| 构建 | AGP 8.5 + Gradle 8.14 + KGP 1.9.24 + CMP 1.6.10 |
| 静态分析 | detekt 1.23.7（覆盖 Android / Web / Server / shared）|
| Web 字体 | Noto Sans CJK SC GB2312 子集，~3 MB（fonts/build-subset.sh 可重生成）|
| 部署 | Caddy（80/443 反代）+ systemd（:server）+ GitHub Actions SSH rsync |

---

## 三、模块依赖关系

```
                       ┌────────────────────────┐
                       │      :shared           │
                       │  (commonMain / 跨平台)  │
                       │                        │
                       │  ▸ engine: CardRules · │
                       │    SettlementCalc ·    │
                       │    GameEngine          │
                       │  ▸ model: Card · Player│
                       │  ▸ ai: AIPlayer        │
                       │  ▸ network: GameMessage│
                       │    + PROTOCOL_VERSION  │
                       │  ▸ commonTest:         │
                       │    CardRulesTest +     │
                       │    SettlementCalcTest  │
                       └────────────────────────┘
                            ▲   ▲   ▲   ▲
                            │   │   │   │
   ┌────────────────────────┘   │   │   └────────────────────────────┐
   │                            │   │                                │
   ▼                            │   │                                ▼
┌──────────────────────┐        │   │           ┌─────────────────────────────────┐
│ :apps:communication- │        │   │           │ :apps:web                       │
│   card (Android)     │        │   │           │ (Compose Multiplatform/Wasm-JS) │
│                      │        │   │           │                                 │
│  • XML UI            │        │   │           │  • Compose UI (Home/Lobby/...)  │
│  • OkHttp WebSocket  │        │   │           │  • @JsFun WebSocketTransport    │
│  • NetworkManager    │        │   │           │  • SinglePlayerEngine           │
│  • MultiplayerEngine │        │   │           │  • localStorage 偏好/战绩持久化  │
└──────────────────────┘        │   │           │  • CommunicationCardTheme       │
            │                   │   │           │  • LayoutMode 响应式断点         │
            │                   │   │           └─────────────────────────────────┘
            │                   │   │                       │
            │                   │   │                       │
            │                   ▼   │                       │
            │     ┌─────────────────────────┐               │
            └───→ │  :server (Ktor)         │ ←─────────────┘
            ws   │                         │       ws (Caddy 反代 :80/443
            8080 │  • WebSocket /game      │            → 127.0.0.1:8080)
                 │  • Application.kt 路由  │
                 │  • ServerRoomManager    │
                 │  • ServerGameManager    │
                 │    (per-room Mutex)     │
                 │  • 协议握手:            │
                 │    PROTOCOL_VERSION 校验 │
                 └─────────────────────────┘
```

**单一真相来源**（PR-H3 之后已落地）：
- 所有牌型 / 结算 / AI / 协议 DTO 都在 `:shared` —— Android、Web、Server **同一份编译器校验**
- 历史上"客户端 / 服务端各自一份 canBeat、容易漂移"的约束（约束 1/4）已编译期消除
- 修 `:shared/.../engine/CardRules.kt` 必同改 `shared/src/commonTest/.../CardRulesTest.kt`
  → CI tdd-gate 强制（详见 `CLAUDE.md` 第三章 + `.github/workflows/android-ci.yml`）

---

## 四、客户端能力矩阵

详见 [`docs/feature_spec.md`](feature_spec.md)。简表：

| 功能 | Android | Web (Stage 4 已合) | 备注 |
|---|---|---|---|
| 单机 AI 对战 | ✅ | ✅ | :shared 同一引擎 |
| 联网多人 | ✅ | ✅ | 同一 :server 后端 |
| 主菜单 5 入口 | ✅ | ✅ | PR #47 (Stage 1) |
| 设置 / 统计 / 帮助 | ✅ | ✅ | PR #47 |
| 响应式（多 form factor） | N/A | ✅ Compact/Medium/Expanded | PR #48 (Stage 2) |
| 视觉动画（CardView 阴影 / 选中 spring） | ⚠️（XML 实现简单）| ✅ | PR #49 (Stage 3) |
| 每玩家最近出牌缩图 | ✅ | ✅ | PR #50 (Stage 4) |
| 房间内聊天 | ✅ | ❌ backlog | TextChatManager 未在 Web 实现 |
| 历史回看 / 出牌回放 | ✅ | ❌ backlog | |
| 托管 / 自动出牌 | ✅ | ❌ backlog | |

---

## 五、关键不变量（违反必出 Bug）

源自 `CLAUDE.md` 第二章 + 历史 P0/P1 教训。

### 约束 1（曾经）：客户端 / 服务端逻辑双份对齐
**已被 PR-H3 编译期消除** —— `:shared` 单一真相来源。新代码不再适用此约束。

### 约束 2：服务端并发安全
- 任何修改 `ServerGameState.hands / playerScores / currentPlayerIndex` 必须**在 `mutexFor(room).withLock { ... }` 内**
- 广播必须**在锁外**（避免慢客户端阻塞房间所有动作）
- `room.players` 必须是 `CopyOnWriteArrayList`（不能 `ArrayList`）

### 约束 3：WebSocket 时序
- Android `OkHttp.client.newWebSocket()` 返回时连接仍 CONNECTING；首次 send 必须在 `onOpen` 内
- Web `@JsFun` `new WebSocket(url)` 同样异步；首次 send 由 `WebSocketTransport.connect(onOpen=...)` 在回调内执行

### 约束 4（曾经）：协议消息双端对齐
**已被 PR-H3 编译期消除** —— GameMessage.kt 在 `:shared`，client/server 同一份。

新约束：**协议字段加 / 改时**：
- 加字段且有默认值 → non-breaking，不必升 PROTOCOL_VERSION
- 字段类型变 / 删字段 / 枚举 rename → breaking，**必须在同 commit 升 PROTOCOL_VERSION**
- 校验机制：`.claude/agents/protocol-syncer.md` subagent + `Application.handleReconnect` 握手拒老客户端

### 约束 5：会话 ID 完整性
服务端 `sessionId` 用**完整 36 字符 UUID**，**不要截断**（曾经因 `take(8)` 触发碰撞，Codex 抓到，已修）

### 约束 6（UI）：单机 / 联网 同端共用同一套渲染层
每个客户端的单机 AI 模式与多人联网模式必须复用**同一套游戏屏幕渲染**——
单机引擎把本地 `GameEngine` 状态映射成与服务端推送同构的 `SerializedGameState`
后再交给 UI；不允许为单机/联网各写一套 game screen 然后双向同步调优。

- Web 端参考实现：`apps/web/.../singleplayer/SinglePlayerEngine.kt` →
  `SerializedGameState` → 同一 `GameScreen` Composable
- Android 端：`GameActivity`（单机）与 `OnlineGameActivity`（联网）**应当**收敛
  到同一渲染入口（共享 fragment / view），新增交互必须两端同时落地

### 约束 7（UI）：以 Android 优化版作为各端布局基线
Android 客户端 UI 已经经过若干轮优化，是各端布局的事实基线。新端 / 新 form factor
**先按 Android 版的信息架构（手牌位 / 头像排布 / 中央出牌 / 按钮分组 / 字号层级）
作为起点**，再按目标屏幕尺寸适配，不要每端从头重设计。

### 约束 8（UI）：设备分级与玩家数 / 牌副数上限

两条正交规则：终端 → 玩家数；玩家数 → 牌副数。

| 终端 | LayoutMode | 允许玩家数 |
|------|-----------|-----------|
| 手机 | Compact (< 600 dp) | **仅 6 人** |
| 平板 / 三折手机展开态 | Medium (600–1200 dp) | 6 / 8 人 |
| 桌面 / Web 大窗 | Expanded (≥ 1200 dp) | 6 / 8 / 10 / 12 人 |

| 玩家数 | 允许牌副数 |
|-------:|-----------|
| 6 人 | 4 / 6 副 |
| 8 人 | 6 / 8 副 |
| 10 人 | 8 / 10 副 |
| 12 人 | 8 / 10 / 12 副 |

- 小屏（Compact）MUST 在创建房间 UI 上 disable / 隐藏 8+ 人选项
- 服务端对 `expectedPlayerCount > 6` 但客户端声明自己是 Compact 的请求**应拒绝**
- 服务端 MUST 校验 `(playerCount, deckCount)` 组合落在上表内
- `:shared/Deck.kt` 当前固定 4 副；多副选项需先把 `Deck.reset()` 参数化并补
  `commonTest`，才能放到 UI（详见 `docs/feature_spec.md` §2.7）

---

## 六、客户端 ↔ 服务端消息流

### Android（同步说法见 `multiplayer_guide.md`）
1. `LobbyActivity` 创建 `NetworkManager` + `RoomManager`，`connect(SERVER_URL)`
2. 创建/加入房间 → `CreateRoom` / `JoinRoom` → 服务端回 `RoomCreated` / `RoomJoined`
3. `RoomManager` 触发 `RoomEvent.RoomCreated/Joined` → `LobbyActivity.navigateToRoom` → `RoomActivity`
4. 房主 `StartGameRequest` → `fillWithAI(6)` → 广播 `RoomUpdate(IN_GAME)` 与每玩家的 `GameStart(state)`
5. `OnlineGameActivity` 接管：`GameSyncManager` 收 `game.*` 消息推 Flow 给 `MultiplayerGameEngine`

### Web（PR #41 起）
同 Android 流程，但：
- `WebSocketTransport` 用 `@JsFun` 包浏览器原生 WebSocket（不依赖 OkHttp）
- 默认 URL `defaultServerUrl()` 走**同源 `/game`**（Caddy 反代到本机 :8080）
- 单机模式由 `SinglePlayerEngine` 包装 `:shared.GameEngine`，UI 与联网模式同一套 Composable 渲染

---

## 七、构建命令

```bash
# 共享模块测试（关键路径必跑，~30s）
./gradlew :shared:jvmTest

# Android 客户端
./gradlew :apps:android:assembleDebug

# Web 客户端
./gradlew :apps:web:wasmJsBrowserDevelopmentRun     # 本地 dev server（热重载）
./gradlew :apps:web:wasmJsBrowserDistribution       # 生产产物 → apps/web/build/dist/wasmJs/productionExecutable

# 服务端
./gradlew :server:test
./gradlew :server:run                               # 监听 :8080，提供 /game WebSocket
./gradlew :server:installDist                       # 出 server/build/install/server/{bin,lib} 给 systemd 用

# 静态分析（覆盖 :shared / :server / :apps:web / :apps:android）
./gradlew detekt

# 一键 push 前自查（详见 .claude/commands/ship-check.md）
/ship-check
```

---

## 八、部署链路

详见 [`docs/playbooks/web-deploy.md`](playbooks/web-deploy.md)。

```
开发者 push to main
  │
  ▼
GitHub Actions (.github/workflows/deploy.yml)
  │ - if vars.DEPLOY_ENABLED == 'true'
  │ - paths filter（apps/web/** | apps/admin/** | shared/** | server/** | deploy/**）
  │ - pre-flight: 验证 /var/lib/communication-card/ 目录 + admin.db 文件均可写
  │               （cards 用户；不可写则 exit 1 并打印 chown 修复命令）
  ▼
build :apps:web:wasmJsBrowserDistribution + apps/admin npm ci && npm run build + :server:installDist
  │
  ▼ rsync via SSH (DEPLOY_SSH_KEY)
腾讯云 Ubuntu 22.04（用户的服务器）
  │
  ├── /var/www/communication-card-web/   (Web 客户端静态产物)
  ├── /var/www/communication-card-admin/ (Admin SPA 静态产物)
  ├── /opt/communication-card/server/    (Ktor server installDist)
  │    └── systemd: communication-card-server.service
  │              (cards 用户 / SERVER_OPTS / 重启策略)
  └── /var/lib/communication-card/admin.db  (SQLite Admin DB；cards 用户写权限 — SQLITE_READONLY 根源)

服务器对外（Caddy 监听 80/443）：
  /              → 静态文件 /var/www/communication-card-web/
  /game          → reverse_proxy 127.0.0.1:8080 (Upgrade ws)
  /admin/*       → 静态文件 /var/www/communication-card-admin/
  /admin-auth/*  → reverse_proxy 127.0.0.1:8080
  /admin/api/*   → reverse_proxy 127.0.0.1:8080

防火墙双层（缺一不可，详见 web-deploy.md §3）：
  ufw       (host 层，install.sh 自动配 80/443/22)
  云安全组   (网络边界层，必须手动配)
```

---

## 九、Harness（PR-H1..H5）

CLAUDE.md 第七章列了完整清单。摘要：

| 类别 | 工件 | 作用 |
|---|---|---|
| Slash command | `/test-fast` | `:shared:jvmTest` 30s 反馈 |
| Slash command | `/pre-commit-scan` | Haiku 4.5 批量扫 5 大约束 |
| Slash command | `/ship-check` | push 前 4 关本地校验 |
| Slash command | `/trace-bug` | bug 报告 → 失败测试 commit |
| Slash command | `/review-pr <N>` | 调 pr-reviewer subagent 审 PR |
| Subagent | `protocol-syncer` | GameMessage.kt 改动校验 PROTOCOL_VERSION |
| Subagent | `tdd-scaffolder` | 关键路径函数生成失败测试骨架 |
| Subagent | `pr-reviewer` | 独立 context 拉 PR diff 跑 8 桶 P0/P1/P2 rubric |
| Hook | `SessionStart` | 启动时印分支 + commit + 关键文档提示 |
| Hook | `PostToolUse` | (a) Edit 关键路径 → TDD 提醒; (b) Bash `git push` → "subscribe_pr_activity 订阅 / 主动查 review" 提醒 |
| Hook | `UserPromptSubmit` | 检测 push 关键词 → ship-check 提醒 |
| GitHub Action | `update-ci-checkbox` | CI 终态自动勾 PR 描述里 "CI 绿" 那格 |
| GitHub Action | exfil 失败日志为 PR 评论 | 沙箱无法读 CI 日志时的反向通道；评论默认折叠（PR #51）|
| `settings.json` allowlist | GitHub MCP 只读工具 + subscribe / ack 工具（PR-H5）| 允许 pr-reviewer subagent 无需每次审批直接调用 GitHub MCP |

---

## 十、PR 流程（4 关）

详见 `.github/pull_request_template.md`。每个 PR 必须等齐 4 个绿灯：

1. **CI 绿**：build + tests + detekt + tdd-gate 全通过
2. **Codex Bot review**：`chatgpt-codex-connector` 评论无 P1
3. **Claude PR review**：`/review-pr <PR#>`（pr-reviewer subagent）输出无 P0/P1
4. **真机验证**：覆盖 happy path + 至少 1 个边界场景

> 每次 push 后 Claude Code 主会话**应主动**：云端会话调用 `subscribe_pr_activity` 订阅 PR 事件，等 `<github-webhook-activity>` 到达（不轮询）；本地会话 fallback 等 60s 后拉 check_runs + review_comments。出现 P0/P1/P2 → 直接修 + 回复 + push。详见 CLAUDE.md 第五章常驻行为约定 + `docs/playbooks/adversarial-review.md` §1.5。

---

## 十一、扩展新客户端（iOS / Desktop / CLI / 其他）

本项目从设计上**对新客户端开放**。现有 Android / Web 是参考实现；iOS / Desktop（JVM 或 Native）/ CLI 等都可以加入，**前提是遵守下面的分层契约**。

### 11.1 三档共享：必须 / 推荐 / 可选

```
新客户端必须用 :shared，其它部分可选
─────────────────────────────────────────────────────────
【必须 - 复用 :shared/commonMain】
  ✓ engine/CardRules           牌型 / canBeat
  ✓ engine/SettlementCalculator 结算公式
  ✓ engine/GameEngine          单机引擎
  ✓ ai/AIPlayer                AI 决策
  ✓ model/{Card,Deck,Player,Team}
  ✓ network/GameMessage         协议 DTO + PROTOCOL_VERSION

→ 新客户端的"游戏逻辑层"复用 100%，**不允许重新实现牌型 / 结算 / AI**
  （历史教训：曾经客户端 / 服务端各一份 canBeat 持续漂移；约束 1）

【推荐 - 平台原生 + thin adapter】
  ◇ WebSocket transport       平台原生 API（Apple URLSession / java.net.http /
                                tokio-tungstenite / etc），thin 包装暴露
                                connect()/send()/close() + state Flow
  ◇ 偏好持久化                 iOS UserDefaults / JVM Preferences /
                                CLI ~/.config/...
  ◇ 战绩持久化                 同上
  ◇ Screen sealed class        如果用类似 Compose 的声明式 UI，可复制
                                Web 端 viewmodel/Screen.kt 结构

【可选 - 按平台体验定制】
  ▪ UI 框架                    SwiftUI / Compose Desktop / TUI (curses) /
                                Web Compose Multiplatform（已有）
  ▪ 视觉风格                   Theme（建议遵循 GreenTableColors 但 token 各端可改）
  ▪ 响应式断点                 类似 LayoutMode（Compact/Medium/Expanded）
  ▪ 字体                       WebGB2312 子集 ~3MB 是浏览器 sandbox 限制；
                                iOS/Desktop 用系统字体即可，无需打包
─────────────────────────────────────────────────────────
```

### 11.2 添加新 KMP target（推荐路径）

如果新端是 Kotlin 友好的（iOS / JVM Desktop / Native），最简：

1. 在 `:shared/build.gradle.kts` 加 target（`iosArm64()` / `jvm("desktop")` / `linuxX64()` 等）
2. 不必改 `commonMain` 任何一行 —— `engine/`/`model/`/`ai/`/`network/` 自动可用
3. 客户端层：新建 `:apps:<platform>/`（参考 `:apps:web` 结构）
4. 实现 `WebSocketTransport`（platform-specific，但接口同 Web 端）
5. 实现 `LocalStorage` 等价物（platform 持久化）
6. 复用 / 抄 `viewmodel/AppViewModel.kt` 的状态机逻辑（业务无平台依赖）
7. 写 UI 层（platform-native 或 Compose Multiplatform）

### 11.3 添加非 Kotlin 客户端（如 Rust CLI / Swift native）

如果新端语言不是 Kotlin（极少见，但有可能 e.g. CLI 用 Rust / iOS Swift 不走 KMP）：

1. **协议契约必须人手对齐** —— 翻译 `:shared/.../network/GameMessage.kt` 到目标语言
2. **`PROTOCOL_VERSION` 必须匹配** —— 握手时发同一个数字，否则 server 拒
3. **CardRules 等如果端上自己跑（比如离线模式）** —— 必须用单元测试验证与 `:shared` 行为完全等价；服务端权威是兜底
4. **维护成本 = (字段数量 + 牌型规则数量) × 端数量**，能用 KMP 就用 KMP

### 11.4 跨端功能规格一致性

新端发布前必须满足"必须 / 推荐"档位的功能（详见 [`docs/feature_spec.md`](feature_spec.md)）：

```
新端 v1.0 必须含：
  ✓ 主菜单（联网 + 单机 入口）
  ✓ 联网大厅 / 房间 / 游戏 / 结算 4 屏
  ✓ 单机 AI 对战
  ✓ 玩家昵称设置（持久化）
  ✓ 出牌 / 过牌 / 提示
  ✓ 当前轮 / 已收分 / 各队总分 显示
  ✓ 各玩家最近出牌可见（缩图或文字）
  ✓ 结算详情：每玩家分数 + 完成顺序

推荐（v1.0 可缺，v1.1 应补）：
  ◇ 设置：音效 / 动画 / 速度
  ◇ 战绩统计 + 重置
  ◇ 帮助 / 规则
  ◇ 添加 AI 进房间（房主权限）
  ◇ 响应式（如平台需要）

可选（按平台体验决定）：
  ▪ 历史回看 / 出牌回放
  ▪ 房间内聊天
  ▪ 托管 / 自动出牌
```

### 11.5 客户端测试矩阵

每个新客户端发布前，至少跑：

1. **单机 happy path**：完整一局、AI 走完、结算正确
2. **联网 happy path**：连服务端、创建房间、6 人开局、出牌 / 过牌、结算正确
3. **断网重连**：游戏中断网 → 等 30 秒 → 网回 → server 推 ReconnectSuccess + 状态恢复
4. **协议版本不匹配**：人为改本地 PROTOCOL_VERSION → 应在握手时被 server 拒
5. **字段缺失容忍**：服务端发的 JSON 多/少一个非必填字段 → 客户端不崩

详见 `docs/playbooks/feature-development.md` 的 Loop A。


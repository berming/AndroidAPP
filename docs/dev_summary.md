# AI 辅助联网游戏开发——完整实践总结

> 约 25 分钟 | 目标受众：移动端 / 后端 / 全栈开发团队

---

## 第一章：项目背景

### 产品简介

**沟通牌**：4 副牌 216 张，6 人 3v3 卡牌游戏，支持三种运行模式：
- **单机模式**：本机 5 个 AI 对手
- **联网对抗**：WebSocket 实时 6 人对战
- **Web 浏览器版**：同一套游戏逻辑，Compose Multiplatform / Wasm-JS 渲染

### 技术栈

| 层 | 技术 |
|----|------|
| 共享逻辑 | **Kotlin Multiplatform (KMP)** — android + jvm + wasmJs targets；含 CardRules / SettlementCalculator / GameEngine / AIPlayer / GameMessage DTO |
| Android 客户端语言 | Kotlin + Coroutines + Flow |
| Android UI | Android XML 布局（不使用 Compose） |
| Android WebSocket | OkHttp |
| Web 客户端 | **Compose Multiplatform 1.6.10 (wasmJs)**，浏览器原生 WebSocket（@JsFun interop） |
| 服务端框架 | Ktor 2.3.6 + Netty + WebSockets（`:server` Gradle 子项目，依赖 `:shared`）|
| 序列化 | kotlinx.serialization 1.6.3（JSON，所有 target 共用）|
| 反代 / 部署 | Caddy（80/443）→ 反代 → `127.0.0.1:8080`；systemd；GitHub Actions auto-deploy |
| 构建 | AGP 8.5 / KMP 1.9.24 / Compose MP 1.6.10 / Gradle 8.x（单一 root project）|
| CI | GitHub Actions：jvmTest + tdd-gate + detekt + assembleDebug + wasmJsBrowserDistribution |

### 代码规模（PR #54 后）

| 模块 | Kotlin 文件数 | 行数 |
|------|-------------|-----|
| `:apps:android`（Android UI + 网络层）| 19 | ~6,300 |
| `:apps:web`（Compose MP / wasmJs）| 23 | ~3,470 |
| `:shared`（KMP 公共逻辑，commonMain）| 9 | ~2,670 |
| `:server`（Ktor 服务端）| 4 | ~1,960 |
| 测试（commonTest + serverTest）| 4 | ~1,530 |
| Android XML 布局 | 20 个 | ~3,320 |
| **合计** | **59 个 Kotlin 文件** | **约 19,250 行**|

关键大文件：`OnlineGameActivity.kt`（1,051 行）、`ServerGameManager.kt`（955 行）、
`AppViewModel.kt`（509 行）、`Application.kt`（554 行）

---

## 第二章：开发过程全貌

### 开发阶段时间线

| 阶段 | 时间 | PR | 主要内容 |
|------|------|----|---------|
| 单机游戏开发 | 2026-02-02 / 02-07~02-12 / 02-24 | #1–14 | 游戏引擎 / 牌型 / AI / 结算 / UI；约 11 轮人工反馈 |
| 联网模式首次完整实现 | 2026-04-30 | #16 | 服务端 + 客户端网络层 + 联网 UI；一次性 6,529 行 |
| 构建与编译修复 | 2026-05-01 | #17–21 | CI 失败 / Gradle 缺失 / 编译错误 |
| 部署与连通性修复 | 2026-05-03 | #22–31 | 服务器 URL / 网络安全 / 503 调试 / Lobby UI |
| Lobby 崩溃与 UI 调整 | 2026-05-04 | #32–33 | 枚举不匹配 / AI 离线 / 房间列表 |
| 联网游戏逻辑深度修复 | 2026-05-07 | #34 | AI 全量审查 ×4 轮 / 8 次 commit / ~50 个 Bug |
| KMP 重构 + Web 客户端 | 2026-05-08 | #35 | 抽取 `:shared` KMP；Compose MP wasmJs 浏览器端；8 层工具链兼容适配 |
| Harness 基础设施（H1–H5）| 2026-05-08 | #36–40, #43 | settings / hooks / TDD / server 合并 / subagents / pr-reviewer；CI tdd-gate 硬关 |
| 服务器自托管 + 自动部署 | 2026-05-08 | #41–44 | Caddy 反代；systemd；GitHub Actions auto-deploy；双层防火墙修复 |
| Web UI 功能补齐（4 阶段）| 2026-05-09 | #47–50 | Stage1 菜单 / Stage2 响应式 / Stage3 视觉 / Stage4 Android 同等功能 |
| Web CJK 字体 / Android URL | 2026-05-09 | #45–46 | 中文豆腐块修复；去 :8080 走 Caddy 80 |
| AI 托管 + 速度配置 | 2026-05-10 | #52–53 | feature_spec G34-G38；PROTOCOL_VERSION = 3；Android + Web 跨端 |
| 单机按钮去重 / 文档刷新 | 2026-05-10 | #51, #54 | CI 折叠；SP 重复托管按钮修复 |

### 版本总量
- 总 PR：**54 个** | 总 commit：**约 170 次（非 merge）** | 开发跨度：**约 4 个月**（**有效开发 18 天**：2 月 8 天 + 3 月 1 天 + 4 月 2 天 + 5 月 7 天）

---

## 第三章：架构设计

### 整体架构：多端共享 + 服务端权威

```
┌──────────────────────────────────────────────────────────────────────┐
│  :apps:android（Android 客户端，XML 布局）                            │
│  ui/ → GameActivity (单机) / OnlineGameActivity (联网)               │
│  network/ → NetworkManager / RoomManager / GameSyncManager           │
│  engine/ → MultiplayerGameEngine（桥接 :shared GameEngine）          │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ 依赖 :shared
┌──────────────────────────────────▼───────────────────────────────────┐
│  :apps:web（Compose Multiplatform wasmJs，浏览器端）                  │
│  AppViewModel → 统一状态机；Screen.{Home/Lobby/Room/Game/Settlement}  │
│  SinglePlayerEngine → 包装 :shared GameEngine                        │
│  net/ → 浏览器原生 WebSocket（@JsFun）；NetworkClient 与 Android 同职 │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ 依赖 :shared
┌──────────────────────────────────▼───────────────────────────────────┐
│  :shared（KMP：android + jvm + wasmJs）                               │
│  model/   Card · Deck · Player                                        │
│  engine/  CardRules · SettlementCalculator · GameEngine               │
│  ai/      AIPlayer                                                    │
│  network/ GameMessage（所有 sealed class + SerializedXxx DTO）        │
│  commonTest/ CardRulesTest / SettlementCalculatorTest /               │
│             GameMessageSerializationTest                              │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ 依赖 :shared
┌──────────────────────────────────▼───────────────────────────────────┐
│  :server（Ktor + Netty，Gradle 子项目）                               │
│  Application.kt → ServerRoomManager（房间 / AI 填充）                 │
│              └→ ServerGameManager（权威状态 / AI / 计时）              │
│       • 每房间一把 Mutex 串行化所有状态修改                             │
│       • 每房间 30s 超时计时器 + broadcastForceAdvance 兜底             │
│       • 三级 AI 回退 + force-advance 强制推进                          │
└──────────────────────────────────┬───────────────────────────────────┘
              WebSocket /game（JSON + sealed class classDiscriminator）
                                   ▲
            Caddy（80 / 443 TLS）——┘   反代 → 127.0.0.1:8080
                                   ▲
                       公网客户端 /game（ws:// 或 wss://）
```

### 关键架构决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 状态权威性 | 服务端权威，客户端乐观响应 | 避免作弊，简化冲突解决 |
| 状态同步策略 | 全量状态 + 单调递增 `version` | 比增量简单，便于断线重连 |
| 序列化协议 | JSON + sealed class + `classDiscriminator` | 可读性 + 类型安全 |
| 并发模型 | 协程 + 每房间 Mutex | 避免线程开销，状态修改串行化 |
| 重连机制 | `sessionToken = playerId`（重连不变）| 断线 30s 内可无缝恢复 |
| **共享逻辑** | **PR #35 + H3：`:shared` KMP 模块** | **编译期保证一致，约束 1/4 消除** |
| 反代拓扑 | Caddy 80/443 → `:8080`（仅 loopback）| TLS 终止；客户端统一走 80/443 |
| 协议版本 | `PROTOCOL_VERSION = 3`（`Reconnect` 携带）| 服务端握手时拒绝旧版客户端 |

### 关键不变量（设计契约）

```
• player.id = 创建/加入时 session.id，断线重连不变
• state.version 单调递增，客户端丢弃倒退状态
• 服务端权威：humanPlay/humanPass 是乐观响应，最终以服务端广播为准
• AI 替补：玩家中途退出 → 标记 isAISubstitute，不删玩家槽
• PROTOCOL_VERSION：Reconnect 消息携带；不匹配服务端拒绝连接
• 所有客户端 URL：走 Caddy 80（或 wss:// 443），不直接打 :8080
```

### 架构演进路径（从遗憾到修复）

| 遗憾（初版）| 状态 | 修复 |
|-----------|------|------|
| 未提取 KMP 共享模块 → `canBeat` 双份 | ✅ 已解决 | PR #35 + H3：`:shared` + 编译期唯一份 |
| 无协议版本号 → 客服端协议演进无强制检查 | ✅ 已解决 | PR-H3：`PROTOCOL_VERSION` + 握手 |
| 无事件溯源 → 全量状态调试困难 | ⚪ 未规划 | 全量同步目前够用，溯源暂不规划 |

---

## 第四章：问题发现全景——人工 vs AI

### 汇总：发现来源与数量（PR #1–54 全程）

| 来源 | 数量 | 占比 | 特点 |
|------|------|------|------|
| **人工测试 / 反馈** | ~35 | 27% | UI 体验、部署环境、运行时崩溃；真机发现 |
| **Claude Code（主会话）**<br/>claude-opus-4-7 / sonnet-4-6 | ~55 | 43% | 全量扫描、跨文件链路、并发/工具链陷阱 |
| **Claude pr-reviewer**<br/>（Opus 4.7 独立 context，PR-H5 后）| ~15 | 12% | 独立角度审查功能完整性、协议契约、跨文件一致性 |
| **ChatGPT Codex Review Bot**<br/>chatgpt-codex-connector[bot] | ~13 | 10% | PR 自动审查；语句级边界、entropy、UI 文案 |
| **重叠 / 联合发现** | ~10 | 8% | Codex 标记 → Claude 深挖根因 |
| **合计** | **~128** | 100% | |

> **核心规律**：人工发现"能看见的问题"，Claude 主会话发现"藏在代码里的问题"，
> pr-reviewer 发现"功能完整性 + 跨文件契约"，Codex 发现"语句级细粒度风险"。
> 四种视角几乎不重叠 —— 只用其中任意一种都会漏掉大量问题。

---

### 人工发现的问题（~35 个）

#### 单机游戏阶段（约 11 个，PR #1–14）

| # | 症状 / 反馈 | 对应修复 |
|---|-----------|---------|
| 1 | 看不到每个玩家打出了什么牌 | 重新设计 UI，每玩家槽显示出牌 (#2) |
| 2 | 玩家 ID 映射错位，玩家 2 不显示 | ID 从 1 开始但代码从 2 映射 (#5) |
| 3 | 队伍积分只显示个人，不是全队合计 | 改为 totalCollectedScore (#6) |
| 4 | 炸弹牌重叠比例不对，显示拥挤 | 调整为 20% 重叠 (#7/#8) |
| 5 | AI 过于激进，动不动打炸弹 | 炸弹作为最后手段策略 (#8) |
| 6 | 手牌顺序混乱，炸弹应排在前面 | 按炸弹优先排序 (#11) |
| 7 | 更新 APK 提示签名不匹配 | 添加固定 debug keystore (#11) |
| 8 | 重构后卡片圆角消失 | 恢复 10dp 圆角 (#12) |
| 9 | 五子棋图标在旧 Android 上崩溃 | 修复 API < 26 矢量图兼容性 (#1/#3) |
| 10 | 字体大小不统一 | 统一为 14sp (#5) |
| 11 | 布局太拥挤 | 两行手牌 / 水平玩家排列 (#3/#4) |

#### 联网构建与部署阶段（约 9 个，PR #17–33）

| # | 症状 | 对应修复 |
|---|------|---------|
| 12 | CI 失败：服务端模块被 Android 构建拉入 | 从 settings.gradle 移除服务端 (#18) |
| 13 | 服务端无 Gradle Wrapper，无法启动 | 补充 gradlew + wrapper (#17) |
| 14 | 联网模块编译错误 | 修复 CardGroup/GameResult/ConnectionState (#19) |
| 15 | 服务端 JVM 工具链配置错误 | 修复 toolchain 配置 (#20) |
| 16 | ChatAdapter 引用不存在的 View ID | 修正为实际存在的 tvSender (#21) |
| 17 | 部署腾讯云后连接不上（URL 未更新）| 更新服务器地址 (#24) |
| 18 | Android 9+ 报 cleartext 连接被拒绝 | 添加 network_security_config.xml (#26/#28) |
| 19 | 连接时返回 503，无法诊断 | 添加健康检查接口 + 详细日志 (#29/#30) |
| 20 | "开始游戏"按钮让用户困惑 | 改为"单机游戏" (#32) |

#### 联网游戏功能阶段（约 10 个，PR #31–34）

| # | 症状 | 对应修复 |
|---|------|---------|
| 21 | Loading 遮罩断线后永远不消失 | 断线后直接反馈错误 (#31) |
| 22 | 单个中文昵称被 2 字符限制拒绝 | 移除最低长度限制 (#31) |
| 23 | **进入大厅崩溃**（CardSuit 枚举不匹配）| 统一枚举值 (#33) |
| 24 | AI 玩家显示为离线状态 | AI 默认 isConnected=true (#33) |
| 25 | 房间内没有踢人按钮 | 添加房主踢人功能 (#33) |
| 26 | 看不到有哪些房间可以加入 | 添加房间列表功能 (#33) |
| 27 | **截图：等待电脑 54 出牌，卡死** | canBeat 炸弹逻辑 + AI 回退链 (#34) |
| 28 | **截图：修复后依然卡死**（反复 3 次）| 并发 Mutex + 兜底推进 (#34) |
| 29 | **截图：已收分全是 0** | playerScores 追踪 + 结算公式 (#34) |
| 30 | 游戏逻辑反复修复反复复现 | 触发 4 轮 AI 全量自查 (#34) |

#### Web / 部署 / 功能阶段（约 5 个，PR #35–54）

| # | 症状 | 对应修复 |
|---|------|---------|
| 31 | Web 所有中文字符显示为白色豆腐块 | 打包 Noto Sans CJK SC 子集 (#45) |
| 32 | 公网 curl 持续 timeout（双层防火墙）| install.sh 自动配 ufw + 分层自检 playbook (#44) |
| 33 | 同步 main 后 Android 联网超时（:8080 硬编码）| 去 :8080，改走 Caddy 80 (#46) |
| 34 | 顺子规则在多端解释不一致 | 移除 straight card type，更新 game_rules.md (#52) |
| 35 | 单机模式出现多余"AI 接管"按钮 | 按钮仅在多人模式显示 (#54) |

---

### AI（Claude 主会话）自主发现的问题（~55 个，跨 4 轮深度审查 + post-#34 持续）

> **AI 来源**：Claude Code Agent（claude-opus-4-7 / claude-sonnet-4-6）
> **工作模式**：Level 3-4 — 截图 / 症状 → AI 自主推理；或开放性指令"自查自纠"全量扫描
> **核心规律**：每轮"在问不同的问题"，症状被消除后下一层根因才暴露 ⇒ 单轮无法到底

#### PR #1–34 阶段（~35 个，4 轮统一明细）

**第 1 轮：综合审查（~20 个）**

| 类别 | 数量 | 主要问题 |
|------|------|---------|
| 协议 / 序列化 | 4 | sealed class 缺 `classDiscriminator`；枚举值客户端 / 服务端不对齐；JSON 反序列化抛 SerializationException；老消息字段无默认值 |
| 会话 / 重连 | 3 | sessionToken 创建房间后未设置；leaveRoom 未清空 token；同一玩家被映射到两个房间 |
| 房间状态机 | 4 | `handleStartGame` 未检查 WAITING；玩家退出不补 AI；重复加入同一房间；STARTING → IN_GAME 缺保护 |
| UI / 状态同步 | 6 | seatIndex%2 误算队伍；初始化后未刷新按钮；onDestroy 未 guard lateinit；ChatAdapter 引用不存在 View ID；玩家槽错位；状态广播粒度过粗 |
| 版本控制 | 1 | `applyState` 版本比较方向反了（接受了旧状态）|
| 其他 | 2 | senderId 用不稳定 session.id；`generateRoomCode` 碰撞无重试 |

**第 2-3 轮：深层审查（~8 个）**

| # | 问题 | 类型 |
|---|------|------|
| 1 | `handleRoundEnd` 赢家未设为下轮 `currentPlayerIndex`（回合错位）| 状态机契约违反 |
| 2 | `ArrayList` → `CopyOnWriteArrayList`（玩家列表并发修改异常）| 并发安全 |
| 3 | AI 回退链完全缺失（首选失败 → 游戏永久挂起）| 兜底逻辑缺失 |
| 4 | `getStateForPlayer` 中 `collectedScore` 硬编码为 0 | 占位符未实装 |
| 5 | `handleDisconnect` 对 FINISHED 房间处理不当 | 状态机边界 |
| 6 | 多处空指针风险（`seats` 为 null 时无保护）| null safety |
| 7 | `kickPlayer` 后未广播房间更新 | 事件遗漏 |
| 8 | `addAI` 在 IN_GAME 状态下被允许 | 状态机契约 |

**第 4 轮：专项根因审查（~7 个）**

| # | 问题 | 根因层级 |
|---|------|---------|
| 1 | WebSocket `send()` 在 CONNECTING 状态静默丢弃（重连失效根本原因）| 异步 API 时序陷阱 |
| 2 | 多协程无锁并发写 `state.hands`（卡死的并发根因）| 共享可变状态 |
| 3 | AI 失败无最终兜底（`broadcastForceAdvance` 缺失）| 兜底链路缺口 |
| 4 | `playerScores` 字段整体缺失 → `collectedScore` 永远为 0 | 数据模型缺字段 |
| 5 | 结算公式漏算"输方未走完玩家已收分"（金额错算）| 公式遗漏分支 |
| 6 | `computeAllFinishedScores` 两端逻辑不一致（约束 1 违反）| 双份代码漂移 |
| 7 | `checkGameEnd` 提前结算条件判断有误（200 分阈值边界）| 边界条件错 |

#### PR #35–54 阶段（~20 个，新增明细）

| # | 问题 | 类别 | 修复 |
|---|------|------|------|
| 1 | `assertNull` 未导入 → 测试编译失败 | wasmJs 工具链 | commit 3bd979a |
| 2 | `kotlinx-coroutines-core:1.7.3` 没 wasmJs variant | wasmJs 工具链 | commit fe1aa60（升 1.8.1）|
| 3 | androidTarget jvmTarget 与 consumer 不齐 → AGP 报错 | wasmJs 工具链 | commit ae00dfc |
| 4 | 跨模块对 `var message.state` smart-cast 失败 | wasmJs 工具链 | commit 16782d9（local val 快照）|
| 5 | `kotlinx-browser:0.1` 要求 Kotlin 2.0+ | wasmJs 工具链 | commit 2eff0ca（@JsFun interop）|
| 6 | `kotlinx-serialization-json:1.6.0` 没 wasmJs | wasmJs 工具链 | commit b332375（升 1.6.3）|
| 7 | `compose.components.resources` 要求 K2.0+ | wasmJs 工具链 | commit b332375（移除）|
| 8 | `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 阻断 KGP NodeJsSetupTask | wasmJs 工具链 | commit a6cc8dd |
| 9 | Caddy reverse_proxy 缺 WebSocket upgrade 头 | 部署 | PR #41 self-review P0 |
| 10 | systemd service 缺 `Restart=always` + `WorkingDirectory` | 部署 | PR #41 self-review P1 |
| 11 | install.sh 未自动配 ufw（用户得手动）| 部署 | commit f26a66d |
| 12 | GitHub Actions deploy workflow 缺 secret 授权 | 部署 | PR #42 dogfood P1 |
| 13 | 健康检查端点暴露内部状态 | 部署 | PR #42 dogfood P1 |
| 14 | Web stuck "loading" overlay（flow collector 未取消）| Web 功能 | commit 400917e |
| 15 | Web SinglePlayer `lastPlayerId` 初始化错（首轮显示空）| Web 功能 | commit d484dbc |
| 16 | Web `kotlinx-serialization` plugin 未 apply → DTO 序列化抛 | Web 功能 | commit 7f83753 |
| 17 | Web `GreenTableColors.teamA/teamB` 缺 → 编译失败 | Web 功能 | commit 8dcba8e |
| 18 | Web 出牌后 selection 未清空（下一手仍高亮）| Web 功能 | commit 7f83753 |
| 19 | `processAITurn` `delay()` 后只重检 currentPlayerIndex，漏检 isAISubstitute | AI 托管 race | commit c9988fd（Codex P2 触发）|
| 20 | AI 炸弹决策阈值用 `rank.value`（1-based），比较值偏移 | AI 托管 | commit 49ded62（Codex P2 触发）|

> **跨会话观察**：第 1 轮发现的多是"代码长得不对"（语法 / 命名 / 类型）；
> 越往后越是"行为长得不对"（时序 / 并发 / 跨文件契约）；
> PR #35 后的 wasmJs 8 层是工具链兼容性，**完全不在前 4 轮模式覆盖范围内**——
> 这印证了 §8.3："训练相关性盲区"不会被多轮自审消除，只能靠真实环境暴露。


---

### Claude pr-reviewer 发现的问题（~15 个，PR-H5 后引入）

> **角色**：Claude Opus 4.7 新会话（零上下文），独立拉 PR diff 审查
> **互补定位**：功能完整性 + 跨文件契约；Codex 偏语句级边界

| PR | 发现 | 优先级 | 处置 |
|----|------|--------|------|
| #41（部署自审）| Caddy config 缺 `header_up Host` / `flush_interval -1`；service 文件无 Restart 策略；健康检查无 auth | P0 + 2×P1 | ✅ 同 PR 修复 |
| #42（dogfood）| 3 个 deploy 安全 / 配置 P1 | 3×P1 | ✅ PR #42 fix commit |
| #47（web 菜单）| 统计 key 名与序列化 DTO 不符；Hint 逻辑边界 | P1 + 3×P2 | ✅ 同 PR fix |
| #49（视觉）| CardView recompose 触发过多（unstable lambda）；pass 按钮在本轮先手时逻辑错 | P1 + 4×P2 | ✅ 同 PR fix |
| #53（AI 托管）| game_rules.md 声称"随机首出"但服务端是 ♠3 先出（文档漂移）| P1 | ✅ 同 PR 修正 game_rules |

> **发现规律**：pr-reviewer 对"新会话看到的逻辑矛盾"最敏感；对 delay 之后
> 的 race condition 不敏感（由 Codex 补盲）。两者组合才完整。

---

### ChatGPT Codex Review Bot（~13 条，全程）

> **工作模式**：PR 创建时自动触发；聚焦语句级风险，按 P1 / P2 标注

| # | PR | 优先级 | 位置 | 意见 | 处置 |
|---|----|--------|------|------|------|
| 1 | #29 | P1 | Application.kt | UUID 截到 8 字符 → 32 位熵，会话碰撞 | ✅ commit 06d445c（完整 36 字符 UUID） |
| 2 | #31 | P1 | LobbyActivity | Reconnecting 路径不触发 hideLoading，遮罩永久卡住 | ✅ PR #33 三状态都加 hideLoading |
| 3 | #33 | P2 | MainActivity | UI 提示"房间名"但服务端只解析 roomCode，必失败 | ✅ UI 改为房间列表点击加入 |
| 4 | #35 | P1 | detekt | `!!` 和 swallowed exceptions 超阈值 | ✅ PR #34/35 null safety 修复 + baseline |
| 5 | PR-H1 | P2 | PreCommitScan | MultiEdit matcher 潜在误匹配 + doc-only filter 绕过 | ✅ commit 369e682 |
| 6 | PR-H3 | P2 | ServerGameManager | AI 炸弹决策阈值用 rank.value（1-based），比较值偏移 | ✅ commit 49ded62（`>= 8` / `<= 3`）|
| 7 | PR-H4 | P2 | trace-bug | allowed-tools 声称的工具权限与实际不符；缺 git add/commit | ✅ commit c888ad5 |
| 8 | #43 | P2 | settings.json | allowed-tools Bash 表达式语法不精确 | ✅ commit 6ef2aea |
| 9 | #45 | P2 | fonts | 字体子集漏包"·"/"—"/"♠♣♥♦"等 UI 字符 | ✅ commit ce74b39 |
| 10 | #47 | P1+P2 | web | kotlinx.serialization plugin 缺 apply；出牌后未清空 selection | ✅ commit 7f83753 |
| 11 | #49 | P1 | web GameScreen | pass 按钮条件逻辑在本轮先手场景有误 | ✅ commit 1779890 |
| 12 | #50 | P2 | web SinglePlayer | lastPlayerId 中央 fallback 缺失；SP 结束状态判断 | ✅ commit d484dbc |
| 13 | #53 | P2 | ServerGameManager | processAITurn `delay()` 后只重检 currentPlayerIndex，漏检 isAISubstitute | ✅ commit c9988fd |

**关键观察**：
- Codex 与 pr-reviewer 互补：Codex 抓"语句级边界 / entropy / 偏移量"，pr-reviewer
  抓"功能完整 / 文档漂移 / 跨文件契约"。第 13 条（race condition）只被 Codex 发现。
- 13 条 Codex 意见中 **1 条 P0、5 条 P1、7 条 P2**，全部已修复。
- UUID 截断（#1）在 PR #29 审查后拖了 4 天才修，其余 P1 全部在当 PR 内修。

---

## 第五章：人工与 AI 协同模式深度解析

### 协同的四个层次

```
Level 1：AI 执行人工指令        （传统：人主导）
   人工写完整指令 → AI 按指令完成 → 等待下一条
   缺点：人工成为瓶颈，AI 沦为"会编程的工具"

Level 2：AI 提建议，人工决策    （审稿：人审 AI）
   AI 完成后输出方案 + 备选 → 人工选择 / 调整 / 驳回

Level 3：人工反馈现象，AI 自主排查  ← 本项目大量使用
   人工：截图 + "还卡住" / "分数错了"
   AI：看代码 + 推理 + 多轮自查 + 修复

Level 4：AI 主动审查，人工验证  ← 最高效模式
   人工：开放性指令（"自查自纠所有问题"）
   AI：全量扫描 + 输出清单 + 修复
   人工：真机验证，反馈未覆盖场景
```

### 实际分工矩阵

| 任务类型 | 人工占比 | AI 占比 | 协同方式 |
|---------|---------|---------|---------|
| 需求定义 | 100% | 0% | 人工口述 / 截图 |
| 架构设计 | 60% | 40% | 人工拍板，AI 提供方案对比 |
| 编码实现 | 3% | 97% | AI 主导，人工偶尔修正方向 |
| UI 调试 | 60% | 40% | 人工真机截图，AI 改代码 |
| 协议/逻辑 Bug 排查 | 15% | 85% | 人工提症状，AI 深挖根因 |
| 部署/网络问题 | 80% | 20% | 人工诊断环境，AI 改配置 |
| 文档编写 | 10% | 90% | AI 起草，人工指出遗漏 |
| 代码审查 | 20% | 80% | Claude 主会话 + pr-reviewer + Codex 三层 |

### AI 显著优于人工的场景

| 场景 | 说明 |
|------|------|
| 全量代码审查 | 单次 ~35 个问题，覆盖整个代码库 |
| 跨文件链路追踪 | 消息从客户端到服务端处理的完整链路 |
| 重复模式识别 | 多处类似并发问题一次性发现 |
| 工具链兼容性排查 | wasmJs 8 层兼容性问题，逐层按序剥 |
| 测试用例生成 | CardRulesTest ~30 用例 / ServerGameManagerTest ~25 用例 自动覆盖边界 |

### 人工不可替代的场景

| 场景 | 原因 |
|------|------|
| 真机环境验证 | cleartext / 503 / 字体豆腐块，AI 看不见 |
| 时序竞争复现 | 需真机才能稳定复现 |
| 用户体验判断 | "布局太挤" / "字体不对"，AI 无视觉感知 |
| 部署决策 | 云服务器选择、密钥管理，AI 不应自主决定 |
| 业务规则确认 | 顺子是否保留、AI 速度默认值，人工最终拍板 |

### 协同反模式（要避免）

| 反模式 | 后果 |
|--------|------|
| 过度信任 AI，不做真机验证 | 表层修复未触根因，反复复现 |
| 模糊指令"把这个 bug 修了" | AI 只修表象，根因仍在 |
| 一次到位幻想 | 本项目实际经历 4 轮才彻底解决卡死问题 |
| 只用一个 AI 工具 | Claude + Codex + pr-reviewer 三层才完整 |
| CI 红不读报错只猜 | 沙箱看不到 CI 日志，应走 exfil channel（§9.9.5）|

### 高效协同最佳实践

> 6 条经验自原 PR #1-34 阶段沉淀；PR #35-54 阶段加入 4 条 harness-era 补充。

**原始 6 条（症状-修复回路）**

1. **症状描述要具体**："等待电脑 54 出牌不动了"比"卡了"信息量大 10 倍。
   屏幕里看到的具体牌点 / 玩家 ID / 时机，AI 可以直接 grep 锁定代码路径
2. **截图优于文字**：UI / 现象类问题，截图让 AI 直接获得视觉上下文，省去
   "你的意思是…吗" 的来回；本项目 #27/#28/#29 三个截图各自直接定位到根因
3. **允许多轮迭代**：第 1 轮修表象，第 2 轮挖根因，第 3 轮加防护；
   "卡死"经历 4 轮才彻底，**强行要求一次到位反而出更多 Bug**
4. **关键决策人工拍板**：架构、协议、依赖选择，AI 提方案、人工选；
   AI 不应擅自决定"用哪个序列化库"或"是否引入新模块"
5. **人工把守发布闸门**：commit / push 前人工最终 review；CLAUDE.md 第八章
   commit 署名规范确保责任归属
6. **开放性指令激发全量审查**："全部自查自纠"比"修这个 bug"更有效——
   前者触发系统性扫描，后者只修指定行；本项目 4 轮根因审查全部由开放
   指令激发

**Harness-era 新增 4 条（结构-自动化）**

7. **优先用基础设施替代自律**：每条"约束"都问"能不能让机器自动检查"——
   能就写 hook / CI gate / lint 规则，不能再写文档；
   本项目 5 大约束（CLAUDE.md 第二章）3 条已机器化（tdd-gate / commit-msg
   hook / detekt baseline），剩 2 条仍靠 review
8. **多 AI 互补而非替代**：Codex（语句级）+ Claude 主会话（全局根因）+
   pr-reviewer（功能完整 / 文档漂移）+ Haiku 静态扫描，**4 套都跑**比
   "找最强的一套" 更可靠；本项目 13 条 Codex 意见中 7 条 Claude 自查没发现
9. **CI red 走 exfil channel，不要猜**：沙箱里读不到 GitHub Actions 日志，
   `.github/workflows/android-ci.yml` 把 gradle stderr 自动 post 成 PR
   comment；AI 拉 comment = 远程读 CI 日志（详见 §9.9.5 + ci-failure-triage.md §5）
10. **每条 Bug 入 regressions.md**：修完不仅 push 代码，**还要写 8 字段
    Bug 卡片**（症状 / 根因 / commit / 教训 / 防回归测试）；新会话开局即可
    扫一遍，杜绝重复踩坑；本项目从 PR-H1 起共 13 条入库

### 协同效率数据

| 指标 | 数值 |
|------|------|
| 人工总投入时间 | ~50 小时（需求 + 反馈 + 真机测试，含两个会话）|
| AI 等效工作时间 | ~500 小时（按工程师正常速度估算）|
| **提速比** | **约 10 倍** |
| 代码提交（非 merge）| 约 170 次 |
| 从"单机 Android"到"双端 + 服务端 + Harness"| 约 4 个月 |
| 单次修复成功率 | ~12%（卡死问题 8 次 commit 才彻底解决） |
| → 启示 | 提速的代价是迭代次数增加，需要轻量 review 流程 + harness 兜底 |

---

## 第六章：关键技术修复

### 游戏卡死——四层防御

```
[症状] 等待电脑出牌，永不响应
    ↓
[层1] canBeat 炸弹比较错误
      修复：大张数直接胜，张数相同再比牌点
    ↓
[层2] AI 失败无任何回退
      修复：首选 → 过牌 → 最小单张（三级回退）
    ↓
[层3] 多协程无锁并发写 state.hands
      修复：每房间一把 Mutex，修改在锁内，广播在锁外
    ↓
[层4] 所有回退均失败时游戏仍卡住
      修复：broadcastForceAdvance 强制推进，同步所有客户端
```

### 重连失效——异步时序陷阱

```kotlin
// ❌ 修复前：ws 还在 CONNECTING，send() 返回 false 被静默丢弃
ws = client.newWebSocket(request, listener)
sessionToken?.let { send(Reconnect(it)) }   // BUG

// ✅ 修复后：在 onOpen 回调内，确保 ws 已 OPEN
override fun onOpen(ws: WebSocket, response: Response) {
    sessionToken?.let { send(Reconnect(it)) }  // OK
}
```

> **教训**：WebSocket 异步 API 中，"创建连接" ≠ "连接已建立"

### 两端结算不一致——统一公式

```
赢方得分 = 赢方所有已收
         + 输方未走完玩家（已收 + 手牌分）  ← 旧版漏了这项
输方得分 = 输方已走完玩家的已收

新增：state.playerScores: MutableMap<Int, Int>
      → 追踪每人实时已收分（原来硬编码 0）
```

### Web 中文字体（CJK 豆腐块）

```
[症状] 浏览器中所有中文显示为白色方块 □□□；tab 标题正常
[根因] CMP wasmJs 用 Skia 在 <canvas> 渲染；Skia 在 wasm 沙箱拿不到
      OS 字体；默认打包字体只覆盖 Latin
[修复] PR #45：Noto Sans CJK SC GB2312 子集（7540 字，~3MB）打入
      wasmJs resources；Fonts.kt 用 @JsFun fetch + base64 加载；
      MaterialTheme typography 全局替换为该 FontFamily
[教训] Compose 跨平台 ≠ 字体跨平台；wasmJs target 必须显式打包字体
```

### 双层防火墙——部署对称陷阱

```
[症状] install.sh 跑完、Caddy 监听 80，从公网 curl 仍 connect timeout
[根因] 云厂商安全组（L1）+ 服务器内 ufw（L2）两层独立，任一未放行 80
      症状完全相同，无法从外部区分是哪层
[修复] PR #44：install.sh 自动配 ufw；playbook §3c "分层自检 3 步"
[教训] 部署 bug 的典型"对称陷阱"——必须给分层自检命令让用户 0 歧义
      定位哪层挂了，避免"重启 Caddy / 疑神疑鬼"乱试
```

### Android URL 漂移——拓扑变更盲区

```
[症状] 拉最新 main 后 Android 联网超时；Web 客户端正常
[根因] PR #41 把拓扑改为 Caddy 80 反代，:8080 不再外露；
      Web 端用相对路径自动适配；Android 端 SERVER_URL 仍硬编码 :8080
[修复] PR #46：两处 SERVER_URL 去 :8080，走默认 80
[教训] 大改部署拓扑必须扫一遍所有客户端；SERVER_URL 是配置项不是常量
```

---

## 第七章：工程经验与后续建议

### 核心经验

1. **"修了又坏"的根因**：只修表层症状，没往深挖根因
   - 卡死历经 4 层才彻底解决
   - **原则**：找到最小可复现场景，分层排除，验证到底

2. **Harness = 把记忆固化为基础设施**
   - 5 层架构（L0 记忆 / L1 权限钩子 / L2 命令代理 / L3 TDD / L4 对抗审查）
   - 不是再写一篇文档，而是让机器在每次 push / edit / session 开始时自动提醒
   - **原则**：结构性约束优于自律，编译期检查优于代码注释

3. **共享逻辑必须物理唯一**
   - 两份 canBeat / 两份 DTO → 三处不一致；最终必须 KMP 模块解决
   - **原则**：重复代码是 Bug 的温床，不是"性能优化"

4. **四层审查缺一不可**
   - Claude 主会话（全局逻辑）+ pr-reviewer（功能完整 / 文档漂移）+
     Codex（语句级边界 / entropy）+ 真机验证（看不见的环境问题）
   - **原则**：单一 AI 视角有系统性盲区，异构比同构更重要

5. **CI 是第二套反馈系统**
   - 沙箱里 90% 的改动可以快速验证（`:shared:jvmTest` ≤30s）
   - wasmJs / Android 构建必须 push 才知道对不对
   - **原则**：把 gradle stderr exfil 到 PR comment，让 AI 能读 CI 日志

### 交付成果（全程）

| 指标 | 数值 |
|------|------|
| 合并 PR 数 | 54 个 (#1–#54) |
| 非 merge commit 数 | 约 170 次 |
| 修复问题 | ~128 个 |
| 客户端 | Android (XML) + Web (CMP/wasmJs) |
| 共享模块 | `:shared` KMP（消灭约束 1/4）|
| Harness 基础设施 | L0–L4 五层，PR-H1~H5 落地 |
| 自动化测试 | ~70 个（CardRules + Settlement + ServerGameManager + 协议 round-trip）|
| 部署 | Caddy + systemd + GitHub Actions auto-deploy |

### 后续建议行动

1. ✅ **自动化集成测试**：PR-H2 落地 — `CardRulesTest.kt`（~30 用例）+
   `ServerGameManagerTest.kt`（~25 用例）+ CI tdd-gate 硬关
2. ✅ **共享规则层**：PR #35 + PR-H3 落地 — `:shared` KMP；约束 1/4 编译期消除
3. ⚪ **监控告警**：上线 `force-advance` 计数指标，触发即告警排查（暂未规划）
4. ⚪ **弱网测试**：集成限速工具，系统化回归重连场景（暂未规划）
5. ✅ **协议版本号**：PR-H3 落地 — `PROTOCOL_VERSION = 3`，握手时校验
6. ⚪ **SERVER_URL 集中化**（regressions #13 follow-up）：抽到 BuildConfig /
   资源文件，避免下次拓扑变更再漏改某端
7. ⚪ **iOS / Desktop targets**：KMP 骨架已就绪，按 `docs/client_implementation_guide.md`
   路径扩展，目前无规划

---

## 第八章：AI 质量改进路径——多 Claude 模型协同

### 8.1 反思：为什么 AI 写的代码 Bug 不少？

#### 生成阶段必然有 Bug 的结构性原因

| # | 原因 | 本项目例子 |
|---|------|---------|
| 1 | 生成 vs 验证是不同认知任务 | canBeat 套用"同张数比大小"模式，忽略大炸弹直胜规则 |
| 2 | 无执行反馈，时序/并发盲区 | WebSocket CONNECTING 时 send() 静默失败 |
| 3 | 自然语言规格隐式不完整 | "重连后游戏继续"——多少秒？保留多少状态？ |
| 4 | 模式匹配编码"常用 ≠ 正确" | `UUID.take(8)`、`ArrayList` 常见但联网场景错 |
| 5 | 跨文件一致性盲区 | 单机 CardRules vs 服务端 canBeat 双份 |

#### 多轮自审仍能发现新问题的原因

| # | 原因 | 本项目体现 |
|---|------|---------|
| 1 | 每轮"在问不同的问题" | R1 问整洁；R4 被迫问"为什么 3 次修了还卡" |
| 2 | 症状被消除后下一层才暴露 | canBeat → 回退链 → Mutex → 兜底，按序解锁 |
| 3 | 自审有确认偏差，用户"还卡住"打破 | 4 轮挖到 send() 静默失败 |
| 4 | 工具链兼容性是知识盲区 | wasmJs 8 层问题，每次 CI 红才暴露下一层 |

### 8.2 多 Claude 模型协同

**模式 1：开新会话（零成本）**

上下文重置 ≈ 换"陌生代码"审计视角 → 消除确认偏差

**模式 2：Generator / Reviewer 分工（推荐）**

```
Opus 4.7   架构设计 + 协议定义 + 根因分析
   ↓
Sonnet 4.6 主要实现（默认模型）
   ↓
Haiku 4.5  /pre-commit-scan 批量静态扫描
   ↓
Opus（pr-reviewer 新会话）  功能完整性 + 跨文件契约审查
```

**模式 3：对抗式审查（Adversarial Review）**

A 实例实现 → B 实例攻击（"找出所有让代码崩溃的输入"）→ C 实例仲裁

> 本项目结算公式若走过此流程，"输方未走完已收分漏算"大概率第一轮被找出。

**模式 4：TDD 反向流**

```
Haiku：先写边界测试用例（让测试红）
Sonnet：实现代码让测试绿
Opus（新会话）：审查"测试覆盖够吗"→ 补测试 → 循环
```

> 本项目 `SettlementCalculator` 有 15 个用例，3 个月无回归；联网版无测试，反复出问题。

### 8.3 多 Claude 协同的天花板

要诚实说：**多 Claude 协同有用，但有结构性上限。**

因为它们共享：

1. **同一训练语料** → 共享"常见模式"假设
2. **同一训练目标** → 共享"什么是好代码"的偏好
3. **同一架构** → 共享类似的注意力分布

→ **相关性盲区**会同时存在于所有 Claude 模型里。

本项目 **~13 个 Codex 意见**中，大量是多个 Claude 自查也大概率找不出来的：

- `UUID.take(8)`：训练语料里到处是，所有 LLM 都视为"常用模式"
- "房间名 vs 房间号"提示文本：UI 文案不一致，AI 共同弱项
- Loading 遮罩边界：UI 状态机 + 用户视角
- AI 炸弹阈值偏移（1-based vs 0-based）：Codex 逐行数，Claude 偏全局
- 语句级 race condition（delay 后漏检 isAISubstitute）：Codex 偏逐行问"这里的边界在哪？"

#### 真正补盲区的是"换 vendor + 加静态工具 + 人工真机"

| 组合 | 找到的问题类型 | 互补程度 |
|------|------------|--------|
| 多个 Claude 实例 | 全局架构 + 逻辑链路（多个角度）| 中等 |
| Claude + Codex（OpenAI）| 增加细粒度风险点 | **较高** |
| + Claude pr-reviewer（Opus 独立 context）| 功能完整 / 文档漂移 / 跨文件契约 | **更高** |
| Claude + Codex + Gemini | 不同训练目标，覆盖最广 | **最高**（季度手动）|
| Claude + 静态分析（Detekt / SpotBugs / kover）| 规则化盲区 | **必要** |
| + 人工真机验证 | 部署 / 字体 / cleartext 等环境问题 | **必要** |

### 8.4 本项目的实际实施结果

> 8.1-8.3 描述的协同方案在 PR-H1~H5 中**已全部落地**：

```
✅ 开发阶段：
  Opus 4.7（1M）  — 架构设计 + 协议定义（harness 五层设计）
  Sonnet 4.6     — 主要实现（默认会话）
  Haiku 4.5      — /pre-commit-scan（每次 push 前静态扫描）

✅ PR 阶段四道关：
  Codex Bot                        — 细粒度风险（PR 创建自动触发）
  Claude pr-reviewer（Opus 新会话）— 功能完整 / 跨文件契约
  CI（tdd-gate + detekt + tests）  — 规则化检查
  真机验证                          — 环境问题兜底

✅ 关键路径 TDD：
  CardRulesTest / ServerGameManagerTest /
  GameMessageSerializationTest / SettlementCalculatorTest
  全部先写红测试再实现，CI tdd-gate 强制
```

**实测效果**（PR #35–54 vs PR #1–34）：
- Codex P1 数：3 → 10（绝对值增加，但 PR 数也多了；都在当 PR 修）
- 因 "缺测试导致回归" 的 Bug：0（PR-H2 后无一例）
- `docs/regressions.md` 追踪的"修了又坏"事件：0（PR #35 后）
- 卡死类 P0：0（PR #34 之后未复发；force-advance + Mutex 兜底有效）
- 协议双端漂移：编译期消除（PR-H3 之后任一 GameMessage 改动两端同步）

预计前面"修了又坏"的 4 轮压到 1-2 轮的目标已**基本达成**：
PR #35 wasmJs 8 层是工具链问题不算"修了又坏"，PR #53 phase 3 因为
1 次 wasmJs psi2ir bug CI 红 2 轮，但属于 wasmJs 后端本身的编译器 bug
（外部依赖问题），不是逻辑回归。

### 8.5 核心洞察

> - 单一 Claude 多轮，本质是**用时间换覆盖率**
> - 多个 Claude 协同，是**用视角换覆盖率**
> - 多 vendor + 静态工具 + 真机验证，是**用异构换覆盖率**

**异构换覆盖率的边际收益最大**，应作为关键代码的标配。

本项目实证：

| 维度 | 投入 | 产出 |
|------|------|------|
| 时间换覆盖率（单 Claude 多轮）| 4 轮自审 | ~35 个 Bug |
| 视角换覆盖率（多 Claude 协同）| Opus + Sonnet + Haiku | +20 个 Bug（post-#34 阶段）|
| 异构换覆盖率（Codex + 真机）| 自动 + 季度手动 | +13 个 Codex + 5 个真机；**90% 是前两者找不到的** |

异构换覆盖率不只是"多找 Bug"，更重要的是它**找的是另一类 Bug**——
否则三种来源会大量重叠，边际收益迅速递减。本项目 13 个 Codex 与 ~55 个
Claude 发现几乎不重叠，正是异构有效的实证。

---

## 第九章：harness 跨会话经验（PR-H 系列 + AI 托管特性）

> 本章记录 PR-H1 / PR-H2 / PR-H3 之后的**跨大会话**协同经验。每条都来自
> 一次真实的"AI 与人协作出 bug → 复盘 → 沉淀回 harness"的闭环。

### 9.1 大特性的"Phase 分段"模式（PR #53 G34-G38 实战）

**问题**：PR #53 一次性想把 `feature_spec G34-G38`（5 个 AI 托管 + 速度配置
特性）打包发出，触及协议层（PROTOCOL_VERSION 升 2→3）/ 服务端 / Android /
Web / 测试，跨 ~700 行。

**实践**：拆 3 个 Phase，每个 Phase 独立 commit：
- Phase 1：协议层 + 服务端 + 单测（底层稳了再动客户端）
- Phase 2：Android UI（一份客户端先吃通）
- Phase 3：Web UI + 跨端协议 roundtrip 测

**沉淀**：
- Phase split 让 Codex / pr-reviewer 的反馈精确到单层
- 协议先行：Phase 1 落地后，Phase 2/3 即便未完成，server 已能跑

**反例**：本次 Phase 3 一次塞下 SP UI + Room speed picker + Web wiring +
12 个测试 ~320 行，结果 wasmJs 一个 psi2ir 隐藏 bug 把 CI 红了 2 轮（详见
9.3）。**教训**：Phase 内部还能再切，按"编译单元"切（Android / Web 拆成两个
commit）能更早发现编译错误——本来 Phase 3 应该至少拆为 "3a Web wiring
+ 测试" 和 "3b SP UI + Room speed picker"，前者过 CI 之后再叠后者。

### 9.2 同 commit `*Test.kt` 配对（tdd-gate 实战）

PR #53 每次改 `CardRules.kt` / `ServerGameManager.kt` / `SettlementCalculator.kt`
都在**同一 commit** 内附测试。tdd-gate 机械校验"关键路径文件改动 ⇒ 对应 `*Test.kt`
同改动"——一次都没误报，也一次都没漏报。

### 9.3 wasmJs 的 "jvmTest 过 ≠ wasmJs 过" 教训（PR #53 第二轮 CI 红）

Phase 3 commit `d976e81` 在 jvmTest 全过的前提下，wasmJs 编译报
`Backend Internal error: Exception during psi2ir + NullPointerException`。

**根因**：可空 lambda 传给 `@Composable` 参数时，wasmJs 后端对"可空 + 函数引用"
组合存在已知 NPE（详见 `docs/regressions.md` #12）。

**跨会话经验**：
- 写完 Web UI **必须 push 跑 CI**，本地永远验不了 wasmJs
- 防御 pattern：可空 lambda 用 local `val` 固化；函数引用写显式 lambda

### 9.4 Codex bot 与 Claude /review-pr 的互补（PR #53 双 P2 实战）

Codex 抓"语句级边界"（race condition 在 delay 后漏检 isAISubstitute）。
Claude pr-reviewer 抓"功能完整"（game_rules.md 文档漂移）。两个同时 pass
才算稳。

**跨会话经验**：
- Codex 评论时间戳可能早于最新 push：回复时**直接列 commit hash** 证明已修
- 两个 AI 工具缺一不可

### 9.5 push 后自动 review-check（hook 实战）

PR-H4 后 `.claude/hooks/PostToolUse.sh` 在每次 `git push` 后注入
"应主动拉 review_comments + check_runs 修 P0/P1"提醒。

**跨会话经验**：
- CI 红时**第一手是去 `get_comments` 拉 PR comment 里 exfil 的 gradle 日志**
- 单 commit push 后 60s 内 build 通常还在 queued，**别在 push 后立刻报"全绿"**

### 9.6 PR 流转的"分支 vs PR"错位（PR #52→#53 实战）

PR #52 合并后，在同一分支继续 push 5 个 commit 但没开新 PR，用户问"PR 怎么没看到"。

**跨会话经验**：一个分支 = 一个 PR。PR merge 后，下一组改动**开新分支**。

### 9.7 文档单一真相 + 自动同步检测（PR #53 P1 实战）

`pr-reviewer` 发现 `docs/game_rules.md §2.3` 误写"随机首出"，但服务端是 ♠3
先出。经典"文档自称权威但与代码漂移"。

**沉淀**：新 doc 自称"权威"前做一次"代码 grep 验证"；用户面文档与 game_rules.md
**必须同 commit 改**。

### 9.8 AI 接管 / 速度档位的设计取舍（feature_spec G36-G38 实战）

3 档预设（50/400/1000ms 单机；100/400/1000ms 多人）优于 slider：
- UI 简单（radio button）、服务端 clamp 简单、玩家心理负担低
- "默认 400ms"：比老 1000ms 缩短 60% 但仍可辨识 AI 决策过程

**跨会话经验**：当特性"看起来 slider 更灵活"时，先问"用户真的需要连续值？"
——多数情况 3 档够用，且压缩边界情况测试矩阵。

### 9.9 Harness L0-L4 体系搭建（PR-H1..H5 + #35 web 重构 实战）

> 与 9.1-9.8 同期推进的另一条主线：把"靠开发者记忆维护约束"系统化为
> "靠基础设施自动执行约束"。这条线产出 5 个 harness PR + 1 个 KMP 重构
> PR + 1 个部署拓扑修复 PR，全部已合并到 main。

#### 9.9.1 五层架构（L0-L4）一览

```
L0 记忆层      CLAUDE.md (主索引) + docs/regressions.md (Bug 冷藏库)
              + docs/playbooks/{feature-development, bug-triage,
                ci-failure-triage, adversarial-review}.md
              ▲ 每条 Bug 带 8 字段：症状 / 根因 / commit / 教训 / 测试

L1 权限&钩子层  .claude/settings.json   readonly bash 自动放行
              .claude/hooks/SessionStart.sh   开局打印分支/CI/最近 commit
              .claude/hooks/PostToolUse.sh    关键路径编辑→TDD 提醒；
                                              git push→拉 review/CI 提醒
              .claude/hooks/UserPromptSubmit.sh   ship/push 关键词→注入提醒
              .githooks/pre-push     push 前自动跑 :shared:jvmTest
              .githooks/commit-msg   校验 Signed-off-by + AI-Assisted-By

L2 命令&子代理   .claude/commands/{test-fast, ship-check, pre-commit-scan,
                trace-bug, review-pr}.md
              .claude/agents/{protocol-syncer, tdd-scaffolder,
                pr-reviewer}.md

L3 TDD 强制层    CardRulesTest (~30 用例) + ServerGameManagerTest (~25 用例)
              + GameMessageSerializationTest（协议 round-trip）
              + CI tdd-gate job：critical path 改动必带对应 *Test.kt 改动

L4 跨 vendor    Codex bot（自动，每 PR 跑）+ pr-reviewer subagent
              （Opus 4.7 独立 context）+ 季度手动第二 vendor + 真机
              4 关 PR 流程：CI / Codex / Claude /review-pr / 真机
```

#### 9.9.2 PR-H 系列实战回顾

| PR | 范围 | 实际产出 | 检验数据 |
|----|------|---------|---------|
| **PR-H1 Bedrock** | 纯基础设施，不动 Kotlin | settings.json / 3 个 hook / 4 个 slash command / 3 个 playbook | 新会话 SessionStart hook 立刻可见；commit-msg hook 缺署名确实拒绝 |
| **PR-H1.5 Deploy** | 部署 playbook + Caddy 拓扑 | install.sh + Caddy + systemd + web-deploy.md | 实战在 PR #41；双层防火墙踩坑 2 次（regressions #9）|
| **PR-H2 TDD** | 关键路径测试 + CI tdd-gate | CardRulesTest / ServerGameManagerTest / GameMessageSerializationTest；tdd-gate job；detekt 移除 continue-on-error | tdd-gate 在 PR #53 真触发；改 CardRules 未改测试立刻红 |
| **PR-H3 Server 合并** | 服务端改子项目，删 Messages.kt | settings.gradle 加 `:server`；删 server/Messages.kt（364 行）；ServerGameManager 委托 :shared；PROTOCOL_VERSION 握手 | 约束 1/4 编译期消除；后续协议变更只改 :shared |
| **PR-H4 Subagents** | 多 AI 角色化 + bug 修复入口 | protocol-syncer / tdd-scaffolder / trace-bug + adversarial-review playbook | trace-bug 在 PR #46 实战生效 |
| **PR-H5 pr-reviewer** | Opus 4.7 独立 context 评审 | pr-reviewer subagent + /review-pr | PR #42/47/49/53 均找出 Codex 未发现的问题 |

#### 9.9.3 PR #35 KMP 重构 — wasmJs 工具链 8 层"剥洋葱"

PR #35 把 model/engine/network/ai 抽到 `:shared` KMP 模块，加 wasmJs target。
CI 连续报 8 类不同的兼容性错误，必须按序逐层剥：

| # | 错误信号 | 根因 | 修法 |
|---|---------|------|------|
| 1 | `assertNull` unresolved | kotlin.test wasmJs 子集缺导入 | 显式 `import kotlin.test.assertNull` |
| 2 | coroutines 没 wasmJs variant | 1.7.3 不含 wasm | 升 `1.8.1` |
| 3 | jvmTarget 不一致 | androidTarget 默认与 consumer 不齐 | `kotlinOptions.jvmTarget = "1.8"` |
| 4 | 跨模块 smart-cast 失败 | Kotlin 不允许对跨模块 `var` smart-cast | 局部 `val` 快照 |
| 5 | kotlinx-browser:0.1 要求 K2.0+ | 当前 K1.9.24 不兼容 | 改 `@JsFun` 直接 interop |
| 6 | serialization-json 没 wasmJs | 1.6.0 不含 wasm | 升 `1.6.3` |
| 7 | compose.components.resources K2.0+ | 同 #5 | 移除（未用到）|
| 8 | FAIL_ON_PROJECT_REPOS 阻断 KGP NodeJsSetup | KGP 需要 project-level 仓库 | 移除该设置 |

**跨会话经验**：
- wasmJs 兼容性是**严格按 Kotlin 版本切片**的，必须查 Maven Central 找首个含
  `-wasm-js` 子产物的版本；错误信号顺序非常重要，上层掩盖下层
- 沙箱拉不到 wasmJs 工具链时**必须 push 跑 CI** — CI exfil channel（§9.9.5）

#### 9.9.4 双层防火墙 / Android URL 漂移（实战入 regressions）

- **regressions #9**：双层防火墙任一未放行 80/443 都 timeout，**症状完全相同**。
  修法：install.sh 自动配 ufw + playbook §3c "分层自检 3 步"
- **regressions #13**：PR #41 拓扑改 Caddy 反代，Web 用相对路径生效，
  **Android SERVER_URL 仍硬编码 `:8080`** → 联网失败

**教训**：拓扑大改必须扫所有客户端；SERVER_URL 是配置项不是常量。

#### 9.9.5 CI exfil channel：把 4 分钟反馈变成可读日志

```yaml
- name: Surface build error on failure
  if: failure()
  run: |
    { echo "## assembleDebug / wasmJs 失败"
      grep -nE "^e: |^error:|FAILURE:|^> Task .* FAILED" build.log | head -80
      tail -300 build.log
    } > comment.md
    gh pr comment "$PR_NUMBER" --body-file comment.md
```

**跨会话经验**：
- 沙箱只能读 PR comment；`get_comments` 拉到 gradle stderr 等于"远程 SSH 到 CI runner"
- `grep '^e:'` 抽硬错误（`^w:` 是警告），避免 warning 淹没真错误
- 模式已写入 `docs/playbooks/ci-failure-triage.md` §5

#### 9.9.6 Harness 见效的可观察证据

以 PR #46（Android URL 漂移）为例，每一层都触发：

1. **SessionStart hook** 列最近 5 commit → 发现 PR #41 拓扑改造
2. **PostToolUse hook** 编辑 MainActivity.kt → 弹 TDD 提醒 + "扫 LobbyActivity 同源"
3. **push hook** 注入"主动拉 review/CI"提醒
4. **Codex bot** 30s 出审查（无 P0/P1）
5. **/review-pr** 独立审查："治标；根因是常量硬编码两份"→ 登记 follow-up
6. **regressions.md #13** 同 commit 写入，承载教训
7. **playbook** 追加"改部署拓扑前 grep 所有客户端 SERVER_URL"步骤

每一关都有 hook / command / agent / test / doc 自动兜底——这就是 harness 与"靠记忆"的差距。

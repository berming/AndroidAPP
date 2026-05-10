# AI 辅助联网游戏开发——完整实践总结

> 约 20 分钟 | 目标受众：移动端 / 后端开发团队

---

## 第一章：项目背景

### 产品简介

**沟通牌**：4 副牌 216 张，6 人 3v3 卡牌游戏，支持两种模式：
- **单机模式**：本机 AI 对手
- **联网对抗**：WebSocket 实时对战

### 技术栈

| 层 | 技术 |
|----|------|
| 客户端语言 | Kotlin + Coroutines + Flow |
| 客户端 UI | Android XML 布局（不使用 Compose） |
| 客户端 WebSocket | OkHttp |
| 服务端框架 | Ktor + Netty + WebSockets |
| 序列化 | kotlinx.serialization (JSON) |
| 构建 | AGP 8.5 / Gradle 8.14（客户端）+ Gradle 8.4（服务端）|

### 代码规模

| 模块 | 文件数 | 行数 |
|------|-------|------|
| 客户端 Kotlin | 28 个 | 8,909 行 |
| 客户端 XML 布局 | 56 个 | 3,279 行 |
| 服务端 Kotlin | 5 个 | 2,161 行 |
| 测试 | 1 个 | 516 行 |
| **合计** | **90 个文件** | **约 14,865 行** |

关键大文件：`GameActivity.kt`（1,468行）、`OnlineGameActivity.kt`（1,001行）、`ServerGameManager.kt`（924行）

---

## 第二章：开发过程全貌

### 开发阶段时间线

| 阶段 | 时间 | PR 数 | 主要内容 |
|------|------|-------|---------|
| 单机游戏开发 | 2026-02 | #1–14 | 游戏引擎/牌型/AI/结算/UI，约11轮人工反馈 |
| 联网模式首次完整实现 | 2026-04-30 | #16 | 服务端+客户端网络层+联网UI，一次性6529行 |
| 构建与编译修复 | 2026-05-01 | #17–21 | CI失败/Gradle缺失/编译错误 |
| 部署与连通性修复 | 2026-05-03 | #22–31 | 服务器URL/网络安全/503调试/Lobby UI |
| Lobby崩溃与UI调整 | 2026-05-04 | #32–33 | 枚举不匹配/AI离线/房间列表 |
| 联网游戏逻辑深度修复 | 2026-05-07 | 本次 | AI全量审查×4轮/8次commit/50+个Bug |

### 版本总量
- 总 PR：**33 个** | 总 commit：**123 次** | 开发跨度：**约 3 个月**

---

## 第三章：架构设计

### 整体架构：单机 + 联网双模式共生

```
┌─────────────────────────────────────────────────────────────┐
│ Android 客户端                                              │
│                                                             │
│  ui/ ──── MainActivity ──┬─→ GameActivity (单机)            │
│                          └─→ LobbyActivity → RoomActivity   │
│                              → OnlineGameActivity (联网)    │
│                                                             │
│  engine/ ─── GameEngine (单机)                              │
│              MultiplayerGameEngine (联网适配器)             │
│              CardRules / SettlementCalculator (共享规则)    │
│                                                             │
│  network/ ── NetworkManager (WebSocket/心跳/重连)           │
│              RoomManager (房间事件 Flow)                    │
│              GameSyncManager (状态同步/版本号)              │
└─────────────────────────────────────────────────────────────┘
                          ▲ WebSocket /game (JSON)
                          ▼
┌─────────────────────────────────────────────────────────────┐
│ 服务端 (Ktor + Netty)                                       │
│  Application.kt ─── ServerRoomManager (房间/AI填充)        │
│                 └── ServerGameManager (权威状态/AI/计时)    │
│       • 每房间一把 Mutex 串行化所有状态修改                 │
│       • 每房间 30s 超时计时器                               │
│       • 三级 AI 回退 + 强制推进兜底                         │
└─────────────────────────────────────────────────────────────┘
```

### 关键架构决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 状态权威性 | 服务端权威，客户端乐观响应 | 避免作弊，简化冲突解决 |
| 状态同步策略 | 全量状态 + 单调递增 version | 比增量同步简单，便于断线重连 |
| 序列化协议 | JSON + sealed class + classDiscriminator | 可读性 + 类型安全 |
| 并发模型 | 协程 + 每房间 Mutex | 避免线程开销，状态修改串行化 |
| 重连机制 | sessionToken = playerId（重连不变） | 断线 30s 内可无缝恢复 |
| 共享逻辑 | 单机/联网各自实现 | **当前痛点**：导致两端不一致 |

### 关键不变量（设计契约）

```
• player.id = 创建/加入时 session.id，断线重连不变
• state.version 单调递增，客户端丢弃倒退状态
• 服务端权威：humanPlay/humanPass 是乐观响应，最终以服务端广播为准
• AI 替补：玩家中途退出 → 标记 isAISubstitute，不删玩家槽
```

### 架构中的妥协与遗憾

| 遗憾 | 影响 |
|------|------|
| 未提取 KMP 共享模块 | `CardRules` 与服务端 `canBeat` 双份，后期出现3处不一致 |
| 无协议版本号 | 客户端/服务端协议演进时无强制兼容检查 |
| 无事件溯源 | 全量状态同步，历史行为无法追溯，调试困难 |

---

## 第四章：问题发现全景——人工 vs AI

### 汇总：发现来源与数量

| 来源 | 数量 | 占比 | 特点 |
|------|------|------|------|
| **人工测试 / 反馈** | ~30 | 44% | 覆盖 UI 体验、部署环境、运行时崩溃 |
| **AI #1：Claude Code Agent**<br/>（claude-opus-4-7 / sonnet-4-6） | ~35 | 51% | 全量代码扫描、跨文件链路、并发陷阱 |
| **AI #2：ChatGPT Codex Review Bot**<br/>（chatgpt-codex-connector[bot]） | 3 | 5% | PR 自动审查、细粒度风险点 |

> **核心规律**：人工发现"能看见的问题"，AI 发现"藏在代码里的问题"。两类 AI 之间也几乎不重叠 —— 多 AI 交叉审查比单一审查更可靠。

---

### 人工发现的问题（~32 个）

#### 单机游戏阶段（约 11 个）

| # | 症状/反馈 | 对应修复 |
|---|---------|---------|
| 1 | 看不到每个玩家打出了什么牌 | 重新设计 UI，每玩家槽显示出牌 (#2) |
| 2 | 玩家 ID 映射错位，玩家2不显示 | ID从1开始但代码从2映射 (#5) |
| 3 | 队伍积分只显示个人，不是全队合计 | 改为 totalCollectedScore (#6) |
| 4 | 炸弹牌重叠比例不对，显示拥挤 | 调整为 20% 重叠 (#7/#8) |
| 5 | AI 过于激进，动不动打炸弹 | 炸弹作为最后手段策略 (#8) |
| 6 | 手牌顺序混乱，炸弹应排在前面 | 按炸弹优先排序 (#11) |
| 7 | 更新 APK 提示签名不匹配 | 添加固定 debug keystore (#11) |
| 8 | 重构后卡片圆角消失 | 恢复 10dp 圆角 (#12) |
| 9 | 五子棋图标在旧 Android 上崩溃 | 修复 API < 26 矢量图兼容性 (#1/#3) |
| 10 | 字体大小不统一 | 统一为 14sp (#5) |
| 11 | 布局太拥挤，信息看不清 | 两行手牌/水平玩家排列 (#3/#4) |

#### 联网构建与部署阶段（约 9 个）

| # | 症状/反馈 | 对应修复 |
|---|---------|---------|
| 12 | CI 失败：服务端模块被 Android 构建拉入 | 从 settings.gradle 移除服务端 (#18) |
| 13 | 服务端无 Gradle Wrapper，无法启动 | 补充 gradlew + wrapper (#17) |
| 14 | 联网模块编译错误（3 处 API 用法错误） | 修复 CardGroup/GameResult/ConnectionState (#19) |
| 15 | 服务端 JVM 工具链配置错误 | 修复 toolchain 配置 (#20) |
| 16 | ChatAdapter 引用了不存在的 View ID | 修正为实际存在的 tvSender (#21) |
| 17 | 部署腾讯云后连接不上（URL 未更新） | 更新服务器地址 (#24) |
| 18 | Android 9+ 报 cleartext 连接被拒绝 | 添加 network_security_config.xml (#26/#28) |
| 19 | 连接时返回 503，无法诊断 | 添加健康检查接口 + 详细日志 (#29/#30) |
| 20 | 主界面"开始游戏"按钮让用户困惑 | 改为"单机游戏" (#32) |

#### 联网游戏功能阶段（约 10 个）

| # | 症状/反馈 | 对应修复 |
|---|---------|---------|
| 21 | 大厅 loading 遮罩断线后永远不消失 | 断线后直接反馈错误 (#31) |
| 22 | 单个中文昵称被 2 字符限制拒绝 | 移除最低长度限制 (#31) |
| 23 | **进入大厅崩溃**（CardSuit 枚举不匹配） | 统一枚举值 (#33) |
| 24 | AI 玩家显示为离线状态 | AI 默认 isConnected=true (#33) |
| 25 | 房间内没有踢人按钮 | 添加房主踢人功能 (#33) |
| 26 | 看不到有哪些房间可以加入 | 添加房间列表功能 (#33) |
| 27 | **截图：等待电脑 54 出牌，长时间卡死** | canBeat 炸弹逻辑 + AI 回退链（本次）|
| 28 | **截图：修复后依然卡死**（反复 3 次） | 并发 Mutex + 兜底推进（本次）|
| 29 | **截图：已收分全是 0** | playerScores 追踪 + 结算公式（本次）|
| 30 | 游戏逻辑反复修复反复复现 | 触发 4 轮 AI 全量自查（本次）|

> 备注：Codex Review Bot 提的 3 条意见（PR #29/#31/#33）属于"AI 静态审查"，已移至下文 AI 章节统一统计。

---

### AI（Claude）自主发现的问题（~35 个，4 轮审查）

> **AI 来源**：Claude Code Agent  ·  模型：claude-opus-4-7（1M 上下文） + claude-sonnet-4-6
> **工作模式**：Level 4 — 用户开放性指令"自查自纠"，AI 全量代码扫描后输出问题清单 + 修复

#### 第 1 轮：综合审查（~20 个）

| 类别 | 数量 | 主要问题 |
|------|------|---------|
| 协议 / 序列化 | 4 | sealed class 缺 classDiscriminator；枚举值与客户端不对齐 |
| 会话 / 重连 | 3 | sessionToken 创建房间后未设置；leaveRoom 未清空 token |
| 房间状态机 | 4 | handleStartGame 未检查 WAITING；退出不补 AI；重复加入同一房间 |
| UI / 状态同步 | 6 | seatIndex%2 误算队伍；初始化后未刷新按钮；onDestroy 未 guard lateinit |
| 版本控制 | 1 | applyState 版本比较方向反了（接受了旧状态）|
| 其他 | 2 | senderId 用不稳定 session.id；generateRoomCode 碰撞无重试 |

#### 第 2–3 轮：深层审查（~8 个）

- `handleRoundEnd` 赢家未设为下轮 `currentPlayerIndex`（回合错位）
- `ArrayList` → `CopyOnWriteArrayList`（并发修改异常）
- AI 回退链完全缺失（首选失败 → 游戏永久挂起）
- `getStateForPlayer` 中 `collectedScore` 硬编码为 0
- `handleDisconnect` 对 FINISHED 房间处理不当
- 多处空指针风险（seats 为 null 时无保护）

#### 第 4 轮：专项根因审查（~7 个）

- WebSocket `send()` 在 CONNECTING 状态静默丢弃（重连失效根本原因）
- 多协程无锁并发写 `state.hands`（并发根本原因）
- AI 失败无最终兜底（`broadcastForceAdvance` 缺失）
- `playerScores` 字段整体缺失，`collectedScore` 永远为 0
- 结算公式漏算"输方未走完玩家已收分"
- `computeAllFinishedScores` 两端逻辑不一致
- `checkGameEnd` 提前结算条件判断有误

---

### 第二个 AI：ChatGPT Codex Review Bot（3 条）

> **AI 来源**：ChatGPT Codex Review（GitHub App `chatgpt-codex-connector[bot]`）
> **工作模式**：PR 创建时自动触发的 GPT 系列模型代码审查
> **特点**：聚焦于细粒度的代码缺陷与隐式风险，按 P1 / P2 优先级标注

| # | PR | 优先级 | 文件 / 位置 | 意见 | 处置 |
|---|-----|--------|-----------|------|------|
| 1 | #29 | P1 | `server/Application.kt:48` | UUID 截断到 8 字符 → entropy 仅 32 位，会话 ID 碰撞会让 `playerToRoom` 指向错误玩家，导致跨用户重连混乱 | ❌ 长期未修复 → ✅ 本次补修（commit `06d445c`，改用完整 36 字符 UUID） |
| 2 | #31 | P1 | `LobbyActivity.kt:219` | createRoom/joinRoom 帧丢失后，`Reconnecting → Connected` 路径不触发 `RoomEvent.Error`，loading 遮罩永久卡住 | ✅ 已修复（PR #33 在 Disconnected/Reconnecting/Error 三个状态都加了 hideLoading + Toast） |
| 3 | #33 | P2 | `MainActivity.kt` | UI 提示「房间号或名称」加入，但服务端 `joinRoom` 只解析 `roomsByCode`，输入名称必失败 | ✅ 已修复（UI 重构为「房间列表点击加入」，去掉了输入框；LobbyActivity 输入框 hint 改为「输入房间码」） |

**关键观察**：
- Codex 的 P1 UUID 意见在审查后约 4 天才被处理（被人工 + Claude 多轮调试遗漏，直到本次系统化复盘才发现）
- 两个 AI 的发现**几乎不重叠**：Claude 偏向"全局架构与逻辑链路"，Codex 偏向"细粒度风险点"
- 启示：单一 AI 工具仍有盲区，**多 AI 交叉审查**比单一审查更可靠

---

## 第五章：人工与 AI 协同模式深度解析

### 协同的四个层次

```
Level 1：AI 执行人工指令        （传统：人主导）
   人工写完整指令 → AI按指令完成 → 等待下一条
   缺点：人工成为瓶颈，AI 沦为"会编程的工具"

Level 2：AI 提建议，人工决策    （审稿：人审 AI）
   AI 完成后输出方案+备选 → 人工选择/调整/驳回
   优点：人工不必动手，但对方案质量负责

Level 3：人工反馈现象，AI 自主排查  ← 本项目大量使用
   人工：截图 + "还卡住"/"分数错了"
   AI：看代码 + 推理 + 多轮自查 + 修复

Level 4：AI 主动审查，人工验证  ← 本项目最高效模式
   人工：开放性指令（"自查自纠所有问题"）
   AI：全量扫描 + 输出清单 + 修复
   人工：真机验证，反馈未覆盖场景
```

### 实际分工矩阵

| 任务类型 | 人工占比 | AI 占比 | 协同方式 |
|---------|---------|---------|---------|
| 需求定义 | 100% | 0% | 人工口述/截图 |
| 架构设计 | 70% | 30% | 人工拍板，AI 提供方案对比 |
| 编码实现 | 5% | 95% | AI 主导，人工偶尔修正方向 |
| UI 调试 | 60% | 40% | 人工真机截图，AI 改代码 |
| 协议/逻辑 Bug 排查 | 20% | 80% | 人工提症状，AI 深挖根因 |
| 部署/网络问题 | 80% | 20% | 人工诊断环境，AI 改配置 |
| 文档编写 | 10% | 90% | AI 起草，人工指出遗漏 |
| 代码审查 | 30% | 70% | AI 全量扫描 + 人工 PR review |

### AI 显著优于人工的场景

| 场景 | 说明 |
|------|------|
| 全量代码审查 | 单次 35 个问题，覆盖 90 个文件，人工难以实现 |
| 跨文件链路追踪 | 消息从客户端发送到服务端处理的完整链路 |
| 重复模式识别 | 5 处类似并发问题一次性发现 |
| 测试用例生成 | 15 个结算用例自动覆盖边界条件 |
| 文档与代码同步 | 架构文档随代码演进自动更新 |

### 人工不可替代的场景

| 场景 | 原因 |
|------|------|
| 真机环境验证 | Android cleartext 限制、503 错误，AI 看不见 |
| 时序竞争复现 | 网络抖动/并发，需真机才能稳定复现 |
| 用户体验判断 | "布局太挤"/"字体不对"，AI 无视觉感知 |
| 部署决策 | 云服务器选择、密钥管理，AI 不应自主决定 |
| 业务规则确认 | 得分公式细节、AI 难度，依赖人工最终拍板 |

### 协同反模式（要避免）

| 反模式 | 表现 | 后果 |
|--------|------|------|
| 过度信任 | AI 说"已修复"就直接合入 | 表层修复未触根因，反复复现 |
| 过度怀疑 | 每个 AI 修改都逐行 review | 失去 AI 提速的核心价值 |
| 模糊指令 | "把这个 bug 修了" | AI 只修表象，根因仍在 |
| 一次到位幻想 | 期待一次审查解决所有问题 | 本项目实际经历 4 轮才彻底解决 |
| 跳过验证 | AI 修完直接发布 | 真机环境问题永远暴露不出来 |

### 高效协同最佳实践

1. **症状描述要具体**："等待电脑54出牌不动了"比"卡了"信息量大 10 倍
2. **截图优于文字**：UI/现象类问题，截图让 AI 直接获得上下文
3. **允许多轮迭代**：第一轮修表象，第二轮挖根因，第三轮加防护
4. **关键决策人工拍板**：架构、协议、依赖选择 AI 不应擅自决定
5. **人工把守发布闸门**：commit/push 前人工最终 review
6. **开放性指令激发全量审查**："全部自查自纠"比"修这个 bug"更有效

### 协同效率数据

| 指标 | 数值 |
|------|------|
| 人工总投入时间 | ~30 小时（沟通 + 真机测试）|
| AI 等效工作时间 | ~300 小时（按工程师正常速度估算）|
| **提速比** | **约 10 倍** |
| 单次修复成功率 | ~12%（8次 commit 才彻底解决） |
| → 启示 | 提速的代价是迭代次数增加，需要轻量 review 流程 |

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

---

## 第七章：工程经验与后续行动

### 核心经验

1. **"修了又坏"的根因**：只修表层症状，没往深挖根因
   - 卡死问题历经 4 层才彻底解决
   - **原则**：找到最小可复现场景，分层排除，验证到底

2. **单机/联网共享逻辑必须保持一致**
   - 炸弹比较/结算公式/回合归属：三处均发生不一致
   - **建议**：提取共享规则层（KMP 模块），或用自动化测试约束两端

3. **AI 辅助的正确分工**
   - AI 全量扫描：高效，但无法替代真机验证
   - 最优工作流：AI 审查 → 人工真机确认 → AI 生成修复

4. **协议与环境问题必须人工打通**
   - 服务器 URL、Android cleartext 限制、503 错误
   - 纯代码审查看不出来，必须人工部署验证

### 本次交付成果

| 指标 | 数值 |
|------|------|
| 全程 PR | 33 个 |
| 总 commit | 123 次 |
| 修复问题 | ~67 个 |
| 本次深度调试 commit | 8 次 |
| 服务端重写行数 | ~700 行 |

### 后续建议行动

1. **自动化集成测试**：炸弹比较/结算/重连流程写服务端单元测试
2. **共享规则层**：`canBeat` + `SettlementCalculator` 抽成 KMP 共享模块
3. **监控告警**：上线 `force-advance` 计数指标，触发即告警排查
4. **弱网测试**：集成限速工具，系统化回归重连场景
5. **协议版本号**：添加 `protocolVersion` 字段，强制兼容检查

---

## 第八章：AI 质量改进路径——多 Claude 模型协同

### 8.1 反思：为什么 AI 写的代码 Bug 不少？为什么多轮还能找出新问题？

#### 生成阶段必然有 Bug 的结构性原因

| # | 原因 | 本项目例子 |
|---|------|---------|
| 1 | 生成 vs 验证是不同认知任务，注意力优先于"看起来合理" | canBeat 套用常见模式"同张数比大小"，忽略大炸弹直胜规则 |
| 2 | 生成时无执行反馈，时序/并发/分布式问题盲区 | WebSocket CONNECTING 时 send() 静默失败，纯静态推理看不出 |
| 3 | 自然语言规格隐式不完整 | "重连后游戏继续"——多少秒？保留多少状态？均未明确 |
| 4 | 模式匹配编码"常用 ≠ 正确" | `UUID.take(8)`、`ArrayList`，常用模式但联网场景错 |
| 5 | 跨文件一致性是结构盲区 | 单机 CardRules vs 服务端 canBeat 双份，3 处不一致 |

#### 多轮自审仍能发现新问题的原因

| # | 原因 | 本项目体现 |
|---|------|---------|
| 1 | 每轮"在问不同的问题" | R1 问代码整洁；R4 被迫问"为什么 3 次修了还卡" |
| 2 | 自审有确认偏差，用户的"还卡住"才能打破 | 4 轮逐层挖到 send() 静默失败 |
| 3 | 症状被消除后，下一层根因才暴露 | canBeat → 回退链 → Mutex → 兜底，必须按序解锁 |
| 4 | 注意力有限，单轮无法深入分析全部代码 | 1M 上下文 ≠ 全部能仔细看 |

### 8.2 多 Claude 模型协同的可能性

#### 可用模型

| 模型 | 特点 | 适合的角色 |
|------|------|---------|
| **Claude Opus 4.7**（1M 上下文）| 推理深度最强，全局视角 | 架构设计、根因分析、深度审查 |
| **Claude Sonnet 4.6** | 平衡速度与能力 | 主要实现、PR review |
| **Claude Haiku 4.5** | 极快、便宜 | 静态扫描、测试用例生成、批量检查 |

#### 5 种协同模式

**模式 1：开新会话 = 等价于"另一个 Claude"（最实用，零成本）**

> 上下文重置就部分等价于换模型——自审偏差主要来自"我刚写的代码我觉得是对的"。开新会话后，那个心智模型消失，看代码接近"陌生代码"。

```
1. Opus 写完代码 → commit
2. 开新会话，Opus 用「审计员」角色读代码 → 输出问题清单
3. 第三个会话，Sonnet 验证修复
```

**模式 2：Generator / Reviewer 分工（推荐）**

```
Opus      架构设计 + 复杂逻辑实现
   ↓
Sonnet    实现细节 + PR review
   ↓
Haiku     静态扫描（命名/null/边界/异常路径）批量跑
   ↓
Opus（新会话）  根因审查（"假设有 bug，最可能在哪？"）
```

**模式 3：对抗式审查（Adversarial Review）**

适合**安全相关代码**和**金钱/分数计算**：

- **A 实例（实现者）**：写代码，提交 PR
- **B 实例（攻击者）**：明确指令"找出所有可能让这段代码崩溃的输入和场景"
- **C 实例（仲裁者）**：判断 B 的攻击哪些是真问题

> 本项目结算公式如果走过此流程，"输方未走完已收分"漏算大概率第一轮就被找出。

**模式 4：TDD 反向流（最能压低 Bug 数）**

```
Haiku：先写测试用例（边界 / 异常 / 并发）
   ↓
Sonnet：实现代码让测试通过
   ↓
Opus（新会话）：审查"测试覆盖够吗？还有什么场景没测到？"
   ↓ 补测试 → 循环
```

> 本项目 `SettlementCalculator` 有 15 个用例，单机版几乎没出过 Bug；联网版没用此流程，结果反复出问题。

**模式 5：Self-Consistency 校验**

同一任务让 Opus 跑 3 次，对比输出：
- 三次完全一致 → 高置信度
- 三次有差异 → 低置信度，标记为"不确定区"，需要人工 review

成本是 3 倍，适合关键代码（协议、并发原语）。

### 8.3 多 Claude 协同的天花板

要诚实说：**多 Claude 协同有用，但有结构性上限。**

因为它们共享：
1. **同一训练语料** → 共享"常见模式"假设
2. **同一训练目标** → 共享"什么是好代码"的偏好
3. **同一架构** → 共享类似的注意力分布

→ **相关性盲区**会同时存在于所有 Claude 模型里。

本项目 3 个 Codex Bot 找到的问题，多 Claude 自查也大概率找不出来：
- `UUID.take(8)`：训练语料里到处是，所有 LLM 都视为"常用模式"
- "房间名 vs 房间号"提示文本：UI 文案不一致，AI 共同弱项
- loading 遮罩边界：UI 状态机 + 用户视角

#### 真正补盲区的是"换 vendor + 加静态工具 + 人工真机"

| 组合 | 找到的问题类型 | 互补程度 |
|------|------------|--------|
| 多个 Claude 实例 | 全局架构 + 逻辑链路（多个角度）| 中等 |
| Claude + Codex（OpenAI）| 增加细粒度风险点 | **较高** |
| Claude + Codex + Gemini | 不同训练目标，覆盖最广 | **最高** |
| Claude + 静态分析（Detekt / SpotBugs / kover）| 规则化盲区 | **必要** |

### 8.4 给本项目的具体改进建议

如果重做联网模块，建议这样配置：

```
开发阶段：
  Opus 4.7（1M）  — 架构设计 + 协议定义
  Sonnet 4.6     — 客户端/服务端实现
  Haiku 4.5      — 提交前批量静态扫描
                   （null safety / 异常路径 / 命名）

PR 阶段（强制 4 道关）：
  Claude PR Review (Opus, 新会话)  — 全局 + 根因审查
  Codex Bot                        — 细粒度风险（已有，免费）
  Detekt / 静态分析                 — 规则化检查
  人工真机验证                      — AI 看不见的环境问题

测试驱动：
  关键路径（结算 / 协议 / 并发）必须先写测试再实现
  Haiku 4.5 批量生成边界用例
```

预计能把"修了又坏"的轮次从 4 轮压到 1-2 轮。

### 8.5 核心洞察

> - 单一 Claude 多轮，本质是**用时间换覆盖率**
> - 多个 Claude 协同，是**用视角换覆盖率**
> - 多 vendor + 静态工具 + 真机验证，是**用异构换覆盖率**

**异构换覆盖率的边际收益最大**，应作为关键代码的标配。

---

## 第九章：harness 跨会话经验（PR-H 系列 + AI 托管特性）

> 本章记录 PR-H1 / PR-H2 / PR-H3 之后的**跨大会话**协同经验。每条都来自
> 一次真实的"AI 与人协作出 bug → 复盘 → 沉淀回 harness"的闭环。

### 9.1 大特性的"Phase 分段"模式（PR #53 G34-G38 实战）

**问题**：PR #53 一次性想把 `feature_spec G34-G38`（5 个 AI 托管 + 速度配置
特性）打包发出，触及协议层（PROTOCOL_VERSION 升 2→3）/ 服务端（4 处 delay
重写 + 3 个 handler）/ Android 双 Activity / Web 双层 / 测试 4 个层面，跨
~700 行。一次提交风险面太大，PR 描述过长 reviewer 看不动。

**实践**：拆 3 个 Phase，每个 Phase 独立 commit + 自带说明：
- Phase 1：协议层 + 服务端 + 单测（**底层稳了再动客户端**）
- Phase 2：Android UI（一份客户端先吃通，验证 server 正常 work）
- Phase 3：Web UI + 跨端协议 roundtrip 测（最后补齐）

**沉淀**：
- Phase split 让每个 commit 的 review 半径可控（Codex / Claude /review-pr 都
  在单 phase 上跑，反馈精确）
- 协议先行：Phase 1 落地后，Phase 2/3 即便 UI 没写完，server 已经能跑
  （新客户端连老服务端会被踢，老客户端连新服务端用默认值兼容）
- 测试与代码同 commit：tdd-gate 不会因"先 commit code 再 commit test"误判

**反例**：本次 Phase 3 一次塞下 SP UI + Room speed picker + Web wiring +
12 个测试 ~320 行，结果 wasmJs 一个 psi2ir 隐藏 bug 把 CI 红了 2 轮（详见
9.3）。**教训**：Phase 内部还能再切，按"编译单元"切（Android / Web 拆成两个
commit）能更早发现编译错误。

### 9.2 同 commit `*Test.kt` 配对（tdd-gate 实战）

PR #53 共 6 个 commit，每次改 `CardRules.kt` / `ServerGameManager.kt` /
`SettlementCalculator.kt` 都在**同一 commit** 内附测试。`.github/workflows/
android-ci.yml` 的 `tdd-gate` job mechanically 校验"关键路径文件改动 ⇒ 对应
*Test.kt 同改动"——一次都没误报，也一次都没漏报。

**跨会话经验**：
- 在 hook（`.claude/hooks/PostToolUse.sh`）里看到"⚠️ TDD 提醒"时**不要
  立刻另开 commit 写测试**——只要保证最终 commit 同时包含两份就行
- 反过来：如果先写了测试 commit，后再 commit 实现，tdd-gate 在"实现
  commit"时仍会过（因为它看的是单 commit 内是否同改）—— 这个语义偶尔
  让人误以为 tdd-gate 被绕过，实则 OK

### 9.3 wasmJs 的 "jvmTest 过 ≠ wasmJs 过" 教训（PR #53 第二轮 CI 红）

Phase 3 commit `d976e81` 在 jvmTest 全过、Android 端编译过的前提下，wasmJs
target 编译报 `Backend Internal error: Exception during psi2ir +
NullPointerException`。详细根因 / 修法见
[`docs/regressions.md` #12](regressions.md#12-wasmjs-psi2ir-npe可空-lambda--compose-smart-cast-触发后端崩溃)。

**跨会话经验**：
- 沙箱（含 Codespaces / 一些 dev container）拉不到 AGP / wasmJs 编译器，
  本地永远跑不全；**写完 Web UI 必须 push 跑 CI 才能验**
- 拿到"build failure" comment 时，第一手要**只看 `e:` 开头的硬错误行**，
  忽略 `w:` 警告。本次错误被 ~50 行 unused-parameter 警告淹没了几秒
- 防御性编码 pattern：**可空 lambda → local val 固化 → 再用**；
  函数引用宁可写显式 lambda（`{ vm.foo() }` 而非 `vm::foo`）

### 9.4 Codex bot 与 Claude /review-pr 的互补（PR #53 双 P2 实战）

PR #53 push 完，Codex bot 30-90s 出 2 个 P2：
- P2-A：`processAITurn` 在 `delay()` 后没重检 `isAISubstitute`（race，详见
  regressions #11）
- P2-B：`btnAiTakeover` 在 SP 没接（**实际已在 Phase 3 d976e81 接好，Codex
  评论时间戳早于 push 时间戳**）

Claude /review-pr (Opus 4.7 subagent) 第一轮**没找到 P2-A**——视角偏向
"功能性是否完整"+"协议契约是否一致"，对"延迟期内状态过期"这种 race
condition 不敏感。Codex 视角偏"读完代码逐行问'这里的边界在哪？'"，反而抓住了。

**跨会话经验**：
- **Codex 与 Claude reviewer 角色不可互替代**：Codex 抓"语句级边界"，
  Claude 抓"功能性完整 / 跨文件契约"。两个都过才算稳
- 评论时间戳要看清楚：Codex 在 push 后~30s 出评论时，可能还在 review 旧
  commit（如本次 P2-B）。回复时**直接列 commit hash 证明已修**比辩论更快
- Codex 评论的格式（`P2 Badge` + 分析 + "Useful? React with 👍 / 👎"）让
  忽略 / 接纳门槛对称——即便误报也不浪费时间，给 👎 即可

### 9.5 push 后自动 review-check（hook 实战）

PR-H4 之后 `.claude/hooks/PostToolUse.sh` 在每次 `git push` 后注入"应主动
拉 review_comments + check_runs 修 P0/P1"提醒。本会话 8+ 次 push，每次
触发：`mcp__github__pull_request_read method=get_check_runs` 拉 build 状态、
`get_review_comments` 拉 Codex 评论。

**跨会话经验**：
- 不要 `sleep 60` 等 Codex——直接拉，拿到空就报"暂无评论"，下次 push 再拉
- CI 红时**第一手是去 `get_comments` 拉 PR comment 里 exfil 的 gradle 日志**
  （`docs/playbooks/ci-failure-triage.md` §5 模式），不去 `gh` / 不开浏览器
- 单 commit 推 push → 60s 内 build 通常还在 queued，**别在 push 之后立刻
  报"全绿"**；至少看到 `conclusion: success` 才算

### 9.6 PR 流转的"分支 vs PR" 错位（PR #52 → #53 实战）

PR #52 在 `claude/docs-architecture-refresh` 分支上合并后，**本会话又在
同一分支上 push 了 5 个 commit**（Phase 1+2+3+2 个修），但 PR #52 已 closed。
用户问"PR 怎么没看到"时才发现需要新开 PR #53。

**跨会话经验**：
- 一个分支 = 一个 PR。**PR merge 后，下一组改动开新分支**（不要再往老
  分支 push 期望"会自动出 PR"）
- 本会话的"修复"链：fix → fix → fix 全堆同分支，但**因为 PR #52 已合**，
  这些 fix 必须新分支 + 新 PR 才能进 main。早识别能省一轮 confusion
- AI 看不到 GitHub 的"分支与 PR 关联状态"——必须主动 `list_pull_requests
  state=all head=branch:name` 查清楚

### 9.7 文档单一真相 + 自动同步检测（PR #53 P1 #1 实战）

`pr-reviewer` 第一轮发现 `docs/game_rules.md §2.3` 误写"起手玩家随机
（`randomFirstPlayer`）"，但服务端 `ServerGameManager.startGame#L68-L71`
实际是 ♠3 先出。这是文档与代码漂移的经典案例：**新写的 game_rules.md
明确声明自己是"权威定义"，反而比旧文档更危险**——因为后续维护者会信。

**沉淀**：
- 新 doc 自称"权威"前，做一次"代码 grep 验证"：所有 anchor 函数名
  必须在 repo 中存在（`randomFirstPlayer` 不存在就是红旗）
- 用户面文档（HelpScreen / strings.xml `rules_content`）跟 game_rules.md
  必须**同 commit 改**——和 server 的 canBeat 约束类似，是同一类"两份
  必须一致"的隐性契约
- 后续可考虑：写一个 detekt 规则 / CI step grep `randomFirstPlayer` 类
  fabricated symbol，但 ROI 不高

### 9.8 AI 接管 / 速度档位的设计取舍（feature_spec G36-G38 实战）

最初讨论时考虑了"slider"（任意 50-2000ms 连续值），最终选 3 档预设。

**取舍**：
- slider：UI 复杂，玩家容易 fiddling，服务端要 clamp 任意值
- 3 档预设：UI 简单（radio button 即可），服务端 clamp 简单，玩家心理负担低
- "默认 400ms"：取自"看清 + 不无聊"经验值，比老 1000ms 缩短 60% 但仍可
  辨识 AI 决策过程
- 单机 50/400/1000；多人最低 100ms（不让网络抖动 + AI 雪崩压垮慢客户端）

**跨会话经验**：当一个特性"看起来 slider 更灵活"时，先问"用户真的需要
连续值么？"——多数情况下 3 档够用，且压缩了边界情况测试矩阵。


---
marp: true
theme: default
paginate: true
style: |
  section {
    font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
    font-size: 20px;
    padding: 40px 60px;
  }
  h1 { color: #1a73e8; font-size: 38px; }
  h2 { color: #1a73e8; font-size: 28px; border-bottom: 2px solid #1a73e8; padding-bottom: 6px; }
  h3 { color: #333; font-size: 22px; }
  table { font-size: 16px; width: 100%; }
  th { background: #1a73e8; color: white; padding: 6px 10px; }
  td { padding: 5px 10px; }
  tr:nth-child(even) { background: #f0f4ff; }
  code { background: #f4f4f4; padding: 2px 6px; border-radius: 3px; }
  pre { background: #f4f4f4; padding: 16px; border-radius: 6px; font-size: 14px; }
  .highlight { color: #e53935; font-weight: bold; }
  .green { color: #2e7d32; }
  .blue { color: #1565c0; }
  .tag-ai { background: #1a73e8; color: white; padding: 2px 8px; border-radius: 10px; font-size: 13px; }
  .tag-human { background: #e53935; color: white; padding: 2px 8px; border-radius: 10px; font-size: 13px; }
---

<!-- 封面 -->
# AI 辅助联网游戏开发
## 完整实践总结

<br>

**沟通牌 × Claude Code × 62 PR · 有效开发 19 天**

<br>

> 内容：技术架构 · 问题全景 · 人机协同模式 · 工程经验 · Admin 后台 · 质量量化

---

## 目录

1. **项目背景** — Kotlin Multiplatform + Vue 3 admin · 代码规模 · 三端
2. **开发全貌** — 13 阶段 · 62 PR · ~250 commit · 有效开发 19 天
3. **架构设计** — 多端共享 + 服务端权威 + Admin 模块 · 关键决策 · 演进路径
4. **问题全景** — 5 层审查发现 ~162 个问题，如何互补
5. **人机协同** — 4 个层次 · 分工矩阵 · 反模式 · 最佳实践
6. **关键技术修复** — 6 处典型（游戏逻辑 / 部署 / 平台陷阱）
7. **经验与行动** — 5 层审查 · plan-first · 1 commit = 1 ship-able
8. **harness 跨会话经验** — Phase 分段 · Codex 互补 · L0–L4 体系 · Admin 实战
9. **质量金字塔 & Token 实测** — PR #58-62 量化数据

---

## 一、项目背景

### 产品
**沟通牌**：4 副牌 216 张，6 人 3v3 卡牌游戏，**三种运行模式**：
- 单机模式（本机 5 AI 对手）
- 联网对抗（WebSocket 实时 6 人对战）
- **Web 浏览器版**（同一套游戏逻辑，Compose Multiplatform / Wasm-JS）

### 技术栈

| 层 | 技术 |
|----|------|
| **共享逻辑** | **Kotlin Multiplatform** (android + jvm + wasmJs) |
| Android 客户端 | Kotlin + Coroutines + Flow + OkHttp + XML 布局 |
| Web 客户端 | **Compose Multiplatform 1.6.10 / Wasm-JS** + 浏览器原生 WebSocket (`@JsFun`) |
| 服务端 | Ktor 2.3.6 + Netty + WebSockets + 依赖 `:shared` |
| **Admin 后台** | **Vue 3 + Element Plus + Pinia + Vite + ECharts**（apps/admin/）|
| **Admin 服务端** | **SQLite + jBCrypt + 手写 SSE + logstash JSON 日志** |
| 反代部署 | Caddy 80/443 + 自动 HTTPS → `127.0.0.1:8080` + `/admin/` 子路径 |
| 构建 | AGP 8.5 / KMP 1.9.24 / Compose MP 1.6.10 / Gradle 8.x + Node 20 |

### 代码规模（PR #62 后）：约 **23,360 行 / 98 文件**

| 模块 | 规模 |
|----|----|
| `:apps:android` | 20 文件 · ~6,440 行 |
| `:apps:web` (Compose MP) | 25 文件 · ~4,320 行 |
| `:shared` (KMP commonMain) | 9 文件 · ~2,670 行 |
| `:server`（含 admin 模块）| 25 文件 · ~4,840 行 |
| `apps/admin/`（Vue 3 SPA）| 19 文件 · ~1,470 行 |
| 测试 | **14 文件 · 186 用例 · ~3,620 行** |

---

## 二、开发全貌：13 阶段 · 62 PR · 有效开发 **19 天**

> **19 天分布**：2 月 8 天 + 3 月 1 天 + 4 月 2 天 + 5 月 8 天
> 月份间存在大量空档；密集开发集中在 5 月

```
2026-02-02 / 02-07~12 / 02-24
               单机游戏 (#1–14)         引擎/牌型/AI/结算 · 11 轮 UI 反馈
2026-04-30     联网首版 (#16)           服务端+网络层 · 一次性 6,529 行
2026-05-01~03  部署 (#17–31)            CI/Gradle/cleartext/503/Lobby UI
2026-05-07     深度修复 (#34)           AI 审查×4轮 / 8 commit / ~50 Bug
2026-05-08     KMP 重构 + Web (#35)     抽 :shared / Compose MP wasmJs
2026-05-08     Harness H1–H5 (#36–43)   hooks / TDD-gate / pr-reviewer
2026-05-08     部署自动化 (#41–44)      Caddy 反代 / systemd / GH Actions
2026-05-09     Web UI 4 阶段 (#47–50)   菜单/响应式/视觉/Android 同等
2026-05-09     Web CJK / Android URL    中文豆腐块修复 · 拓扑 URL 漂移
2026-05-10     AI 接管 (#52–53, #58)    G34-G38 · PROTOCOL_VERSION=3 · 约束 6/7/8
2026-05-10     WS 重连 (#59)            Web 指数退避 + dev_summary.html 渲染
2026-05-11     bermin.cn HTTPS (#60)    Caddy 自动 ACME · 保留 :80 IP fallback
2026-05-13     Admin MVP (#61)          PR 0-4：骨架/SQLite-bcrypt/监控/告警/Vue SPA
2026-05-13     Admin 优化 (#62)         PR 5a-d：SSE/趋势图/JSON 日志/事件回放
```

**总计：62 PR · ~250 commit · ~162 问题（5 层审查）· 186 测试用例**

---

## 三、架构：多端共享 + 服务端权威

```
┌──────────────────────┐  ┌──────────────────────┐
│ :apps:android (XML)  │  │ :apps:web (Wasm-JS)  │
│ Game/Online Activity │  │ Compose MP UI        │
│ MultiplayerGameEngine│  │ AppViewModel         │
└────────┬─────────────┘  └────────┬─────────────┘
         │ depends on              │
         ▼                         ▼
       ┌──────────────────────────────┐
       │ :shared (KMP commonMain)     │
       │ Card · Deck · Player         │
       │ CardRules · Settlement       │
       │ GameEngine · AIPlayer        │
       │ GameMessage (DTO)            │
       │ + commonTest (40+ 用例)      │
       └────────────┬─────────────────┘
                    │
                    ▼
       ┌──────────────────────────────┐
       │ :server (Ktor + Netty)       │
       │  每房间 Mutex · AI 三级回退  │
       │  30s 超时 · 兜底推进         │
       └────────────┬─────────────────┘
                    │ WebSocket /game
                    ▼
        Caddy 80/443 → 127.0.0.1:8080
```

**核心：KMP 共享逻辑（PR #35 + H3 编译期保证一致）+ 服务端权威 + 全量状态同步**

---

## 三、架构：关键决策 & 妥协

### 关键决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 状态同步 | 全量状态 + 单调 version | 比增量简单，天然支持重连 |
| 并发 | 每房间 Mutex | 串行化修改，广播在锁外 |
| 重连 | sessionToken = playerId | 30s 内无缝恢复游戏 |
| AI 替补 | isAISubstitute 标记 | 不删玩家槽，重连仍有效 |

### 架构妥协与遗憾

| 遗憾 | 影响 |
|------|------|
| ⚠ 未提取 KMP 共享规则模块 | `CardRules` 与服务端 `canBeat` 双份 → 后期出现 **3 处不一致** |
| ⚠ 无协议版本号 | 协议演进无强制兼容检查 |
| ⚠ 无事件溯源 | 历史行为无法追溯，调试困难 |

### 架构演进：从遗憾到修复

| 遗憾 | 状态 | 修复 |
|------|------|------|
| KMP 共享模块 | ✅ 已解决 | PR #35 + H3：`:shared` 编译期唯一份 |
| 协议版本号 | ✅ 已解决 | PR-H3：`PROTOCOL_VERSION` + 握手 |
| 事件溯源 | ⚪ 未规划 | 全量同步够用 |

---

## 四、问题全景：5 层审查 ~162 个问题（PR #1-#62 全程）

| 来源 | 数量 | 占比 | 特点 |
|------|------|------|------|
| 🔴 **人工测试 / 反馈 + 真机** | **~38** | 23% | UI 体验 · 部署环境 · UI race（如 Web 连点 3 次）|
| 🔵 **Claude Code 主会话** | **~65** | 40% | 全量扫描 · 跨文件链路 · CI 修复回路（4 次工具链 quirks）|
| 🟢 **Claude pr-reviewer** | **~22** | 14% | PR #61 一次审出 1 P0（AlertDto 未定义）+ 4 P1 |
| 🟡 **ChatGPT Codex Bot** | **25**（精确）| 15% | PR #29-#62 全量审计：0 误报 / P1×7 / P2×18 |
| 重叠联合 | ~12 | 7% | Codex 标记 → Claude 深挖根因 |

> ### 核心发现：5 种视角几乎不重叠
> **人工真机**：UI race（Compose 重组延迟期双击 - 无单测可写）
> **Claude 主会话**：跨文件链路（静态全量）+ CI 修复
> **pr-reviewer**：跨文件契约 / 类型未定义（独立 context；PR #61 救场）
> **Codex**：语句级边界 / 并发 race（PR #62 gameEventListener 锁外调）
> **CI**：工具链 quirks（嵌套块注释 / runTest 虚拟时钟）
>
> **撤掉任一层都有可观察的回退**——见 §九 9.18 质量金字塔量化数据

---

## 四、人工发现（1/3）— 单机阶段（11个）

| # | 反馈 | 修复 |
|---|------|------|
| 1 | 看不到每个玩家打出的牌 | 重新设计 UI，每玩家槽显示出牌 |
| 2 | 玩家 ID 映射错位，玩家2不显示 | 修正 ID 偏移（从1开始非从2）|
| 3 | 队伍积分只显示个人，非全队合计 | 改为 totalCollectedScore |
| 4 | 炸弹牌重叠比例不对，显示拥挤 | 调整为 20% 重叠 |
| 5 | AI 动不动打炸弹，太激进 | 炸弹作为最后手段策略 |
| 6 | 手牌顺序乱，炸弹应排在前面 | 按炸弹优先排序 |
| 7 | 更新 APK 提示签名不匹配 | 添加固定 debug keystore |
| 8 | 重构后卡片圆角消失 | 恢复 10dp 圆角 |
| 9 | 五子棋图标在旧 Android 崩溃 | 修复 API < 26 矢量图兼容性 |
| 10 | 字体大小不统一 | 统一为 14sp |
| 11 | 布局太拥挤，信息看不清 | 两行手牌 / 水平玩家排列 |

---

## 四、人工发现（2/3）— 部署阶段（9个）

| # | 反馈 | 修复 |
|---|------|------|
| 12 | CI 失败：服务端被 Android 构建拉入 | 从 settings.gradle 移除服务端 |
| 13 | 服务端无 Gradle Wrapper，无法启动 | 补充 gradlew + wrapper |
| 14 | 联网模块编译错误（3处 API 用法错误）| 修复 CardGroup/GameResult/ConnectionState |
| 15 | 服务端 JVM 工具链配置错误 | 修复 toolchain 配置 |
| 16 | ChatAdapter 引用不存在的 View ID | 修正为实际存在的 tvSender |
| 17 | 部署腾讯云后连接不上 | 更新服务器 URL |
| 18 | **Android 9+ cleartext 连接被拒绝** | 添加 network_security_config.xml |
| 19 | **连接返回 503，无法诊断** | 添加健康检查接口 + 详细日志 |
| 20 | "开始游戏"按钮让用户困惑 | 改为"单机游戏" |

---

## 四、人工发现（3/3）— 联网游戏阶段（12个）

| # | 反馈 | 修复 |
|---|------|------|
| 21 | loading 遮罩断线后永远不消失 | 断线后直接反馈错误 |
| 22 | 单个中文昵称被 2 字符限制拒绝 | 移除最低长度限制 |
| 23 | **进入大厅崩溃**（CardSuit 枚举不匹配）| 统一枚举值 |
| 24 | AI 玩家显示为离线状态 | AI 默认 isConnected=true |
| 25 | 房间内没有踢人按钮 | 添加房主踢人功能 |
| 26 | 看不到有哪些房间可以加入 | 添加房间列表功能 |
| **27** | **截图：等待电脑54出牌，长时间卡死** | canBeat 炸弹逻辑 + AI 回退链 |
| **28** | **截图：修复后依然卡死（反复3次）** | 并发 Mutex + 兜底推进 |
| **29** | **截图：已收分全是 0** | playerScores 追踪 + 结算公式 |
| 30 | 反复修复反复复现 → 触发全量自查 | 4 轮 AI 审查 |
| 31 | [Bot P1] 断线重连遮罩永久卡住 | 修复重连 UI 路径 |
| 32 | [Bot P2] UI提示按房间名加入但服务端不支持 | 待修复 |

---

## 四、AI 发现（~35个，4轮）

### 第1轮：综合审查（~20个）

| 类别 | 主要问题 |
|------|---------|
| 协议/序列化 (4) | sealed class 缺 classDiscriminator；枚举值不对齐 |
| 会话/重连 (3) | sessionToken 未设置；leaveRoom 未清空 token |
| 房间状态机 (4) | 未检查 WAITING 状态；退出不补 AI；重复加入 |
| UI/状态同步 (6) | seatIndex%2 误算队伍；初始化后未刷新按钮 |
| 其他 (3) | applyState 版本比较反向；senderId 不稳定 |

### 第2–3轮：深层审查（~8个）
赢家未设为下轮首家 · `ArrayList` 并发修改 · AI 回退链缺失 · `collectedScore` 硬编码 0

### 第4轮：根因专项审查（~7个）
WebSocket CONNECTING 时 send() 静默失败 · 多协程无锁并发写 · `playerScores` 整体缺失 · 结算公式漏算 · 两端逻辑不一致

---

## 五、人机协同：4个层次

```
Level 1  AI 执行人工指令          （传统：人主导）
         人工写完整指令 → AI 执行 → 等下一条
         缺点：人工成为瓶颈

Level 2  AI 提建议，人工决策      （审稿）
         AI 输出方案+备选 → 人工选择/驳回
         优点：人工不动手，但对质量负责

Level 3  人工反馈现象，AI 自主排查 ← 本项目大量使用
         人工："还卡住" / 截图
         AI：看代码 + 推理 + 多轮自查 + 修复

Level 4  AI 主动审查，人工验证    ← 本项目最高效
         人工："自查自纠所有问题"
         AI：全量扫描 + 清单 + 修复
         人工：真机验证，反馈漏网场景
```

**本项目 Level 3+4 占用了约 70% 的协同时间**

---

## 五、实际分工矩阵

| 任务类型 | 人工 | AI | 协同方式 |
|---------|------|----|---------|
| 需求定义 | 100% | — | 人工口述/截图 |
| 架构设计 | 70% | 30% | 人工拍板，AI 提方案对比 |
| 编码实现 | 5% | 95% | AI 主导，人工修正方向 |
| UI 调试 | 60% | 40% | 人工真机截图，AI 改代码 |
| 逻辑 Bug 排查 | 20% | 80% | 人工提症状，AI 挖根因 |
| 部署/网络问题 | 80% | 20% | 人工诊断环境，AI 改配置 |
| 文档编写 | 10% | 90% | AI 起草，人工指出遗漏 |
| 代码审查 | 30% | 70% | AI 全量扫描 + 人工 PR review |

---

## 五、AI 优势 vs 人工不可替代

### AI 显著优于人工

| ✅ 场景 | 说明 |
|--------|------|
| 全量代码审查 | 单次 35 个问题，覆盖 90 个文件，人工数天才能完成 |
| 跨文件链路追踪 | 消息从客户端到服务端的完整调用链 |
| 重复模式识别 | 5 处类似并发问题一次性全部找出 |
| 测试用例生成 | 15 个结算用例自动覆盖所有边界条件 |

### 人工不可替代

| ❌ 场景 | 原因 |
|--------|------|
| 真机环境验证 | Android cleartext 限制、503 错误 AI 看不见 |
| 时序竞争复现 | 网络抖动/并发，需真机才能稳定复现 |
| 用户体验判断 | "布局太挤"，AI 无视觉感知 |
| 部署 & 业务决策 | 服务器选择、密钥管理、业务规则拍板 |

---

## 五、协同反模式（要避免）

| 反模式 | 表现 | 后果 |
|--------|------|------|
| 🚨 过度信任 | AI 说"已修复"就直接合入 | 表层修复未触根因，反复复现 |
| 🚨 过度怀疑 | 每个修改都逐行 review | 失去 AI 提速核心价值 |
| 🚨 模糊指令 | "把这个 bug 修了" | 只修表象，根因仍在 |
| 🚨 一次到位幻想 | 期待一次审查解决所有问题 | 本项目经历 **4 轮**才彻底解决 |
| 🚨 跳过验证 | AI 修完直接发布 | 真机问题永远暴露不出来 |

---

## 五、最佳实践 & 效率数据

### 高效协同的 6 条实践

1. **症状描述要具体**："等待电脑54出牌不动了" 比 "卡了" 信息量大 10 倍
2. **截图优于文字**：UI/现象类问题，截图直接给 AI 上下文
3. **允许多轮迭代**：第一轮修表象，第二轮挖根因，第三轮加防护
4. **关键决策人工拍板**：架构、协议、依赖 AI 不应擅自决定
5. **人工把守发布闸门**：commit/push 前人工最终 review
6. **开放性指令激发全量审查**："全部自查自纠" > "修这个 bug"

### 效率数据

| 指标 | 数值 |
|------|------|
| 人工总投入 | ~30 小时（沟通 + 真机测试）|
| AI 等效工作量 | ~300 小时 |
| **提速比** | **约 10 倍** |
| 单次修复成功率 | ~12%（8次 commit 才彻底解决）|
| → 启示 | 提速代价是迭代次数增加，需要轻量 review 流程 |

---

## 六、关键技术修复（1/3）— 四层防卡死

**症状**：游戏卡在等待 AI 出牌，永不响应

```
[层1] canBeat 炸弹比较错误
      修复前：current.size != last.size → return false（大炸弹反而打不过）
      修复后：大张数直接胜，张数相同再比牌点

[层2] AI 失败无任何回退
      修复：首选动作 → 过牌 → 最小单张（三级回退）

[层3] 多协程无锁并发写 state.hands
      修复：每房间一把 Mutex
            修改 state 在锁内 · 广播在锁外（避免 I/O 阻塞锁）

[层4] 三级回退均失败时游戏仍卡
      修复：broadcastForceAdvance 强制推进，同步所有客户端
```

> **教训**：单机与联网验证逻辑必须保持一致，差异越早发现代价越低

---

## 六、关键技术修复（2/3）— 重连时序陷阱

**症状**：断线后重连，游戏状态无法恢复，永远显示"连接中"

```kotlin
// ❌ 修复前
ws = client.newWebSocket(request, listener)  // 异步，立即返回
// ws 仍在 CONNECTING 状态，send() 返回 false，消息静默丢弃 ↓
sessionToken?.let { send(Reconnect(it)) }    // BUG：永远失败

// ✅ 修复后：在 onOpen 回调内发送，确保 ws 已 OPEN
override fun onOpen(ws: WebSocket, response: Response) {
    scope.launch {
        _connectionState.value = Connected
        sessionToken?.let { send(Reconnect(it)) }  // OK
    }
}
```

> **教训**："创建连接" ≠ "连接已建立"；异步 API 必须在回调中操作

---

## 六、关键技术修复（3/3）— 两端结算不一致

**症状**：已收分全是 0；游戏结束时双方分数不符合预期

**根因**：
1. `getStateForPlayer` 中 `collectedScore` 硬编码为 **0**（`playerScores` 字段缺失）
2. 结算公式服务端遗漏"输方未走完玩家已收分"

```
统一公式：
  赢方得分 = 赢方所有已收
           + 输方未走完玩家（已收 + 手牌分）  ← 服务端原来漏了这项
  输方得分 = 输方已走完玩家的已收

新增字段：
  state.playerScores: MutableMap<Int, Int>
  在 handleRoundEnd 每次赢牌时同步累加 → collectedScore 实时正确
```

**15 个验证用例全部通过（含提前结算、速度流、极端场景）**

---

## 六、关键技术修复（4/6）— Web 中文豆腐块（CJK）

**症状**：Web 端打开后所有中文显示成 □□□

**根因**：浏览器 Wasm 环境无系统中文字体；Compose Multiplatform 默认字体不含 CJK

**方案**：
- 打包 ~3MB GB2312 子集中文字体（仅 game UI 用到的字符）
- 通过 `LocalCompositionLocal` 注入字体 family 到所有 Composable

```kotlin
@Composable
fun CommunicationCardTheme(cjkFamily: FontFamily, content: @Composable () -> Unit) {
    val typography = with(MaterialTheme.typography) {
        copy(bodyMedium = bodyMedium.copy(fontFamily = cjkFamily), ...)
    }
    MaterialTheme(typography = typography, content = content)
}
```

> **教训**：跨端测试要覆盖**真实终端环境**——浏览器与 Android 系统字体可用性差异巨大

---

## 六、关键技术修复（5/6）— 双层防火墙陷阱

**症状**：腾讯云部署后 Android 客户端 connection timeout；服务端日志显示连不进来

**根因**：
- ufw 已开 `8080/tcp`（**主机层** ✓）
- 但腾讯云**安全组**只开 22/80/443，**8080 被外网拦截**（**云控制台层**）
- 私有云常有"两层防火墙"：主机 ufw + 云厂商安全组，必须**都开**

**修复**：
- 短期：腾讯云控制台开 8080（不推荐——直暴 Ktor）
- 长期：Caddy 80 反代 → `127.0.0.1:8080`，**`:8080` 仅 loopback**（PR #41）

> **教训**：私有云部署完必须**双层验证**：本机 `curl localhost:8080` ✓ 后还要从外网测一次

---

## 六、关键技术修复（6/6）— Android URL 拓扑漂移

**症状**：PR #41 改 Caddy 反代后 Android 客户端连不上（Web 客户端正常）

**根因**：
- PR #41 把 Web 客户端改成同源 `/game`（走 80）✓
- 但 **Android 端 `LobbyActivity.kt` 和 `MainActivity.kt` 的 `SERVER_URL` 仍硬编码 `:8080`**
- `:8080` 不再外网可达 → Android 必然 timeout

**修复**：去掉 `:8080`，改走默认 80 经 Caddy 反代

> **教训**：拓扑变更必须 **grep 所有客户端 URL 硬编码**——Web、移动端、桌面端、未来 iOS 都要查
> **结构性根因**：URL 应该集中（资源文件 / BuildConfig / 远程配置），而非两处 `private const val`

---

## 七、工程经验总结（PR #58-62 新加 3 条）

### 1. "修了又坏"的根因：只修表层症状
```
canBeat → 还卡（无 AI 回退）→ 还卡（并发）→ 偶发（无兜底）→ 4 层防御彻底解决 ✓
```
**原则**：找到最小可复现场景，分层排除，验证到底

### 2. 跨端共享逻辑必须用 KMP 强制
**已实现**：PR #35 + H3 抽 `:shared` 模块，编译期保证一致

### 3. **五层**审查缺一不可（PR #62 新增"用户真机"为第 5 层）
- Claude 主会话 / pr-reviewer / Codex bot / CI / **用户真机**
- PR #62 Web 连点 3 次 bug：4 关 AI review 都没识别，由用户报——
  说明再多 AI 视角也替代不了真实使用反馈
- 详见 §九 9.18 质量金字塔（PR #58-62 量化数据）

### 4. 协议 / 环境 / 拓扑变更必须人工打通
- Android cleartext / 503 / Caddy 反代 / 双层防火墙：纯代码审查看不出来

### 5. **质量保障要 plan 先行而非事后补**（PR #61-62 admin 实战）
- admin 9 段 ~3,000 prod LOC：plan 文件预先 600+ 行写清楚 SQL schema / Vue
  文件树 / Caddy 路由 / CI Node 配置，再编码 → 实测**省 ~30% 返工**

### 6. **"1 commit = 1 个独立 ship-able 单元"**（admin 9 段 commit 拆分实战）
- 即便最终合到同一 PR，每个 commit 也要能独立通过 review
- commit 粒度 = review 粒度；不是"PR = review 单元"

### 7. delay() 之后必须重检所有依赖项（regressions #11）

---

## 七、交付成果 & 后续行动（PR #62）

### 全程交付成果

| 指标 | 数值 |
|------|------|
| 全程 PR / commit | **62 个 / ~250 次** |
| 发现问题总数 | **~162 个**（5 层审查；Codex 精确 25） |
| 开发量 | **有效开发 19 天** |
| 终端覆盖 | Android + Web + **Admin SPA (Vue 3 / Element Plus)** |
| 共享逻辑 | `:shared` KMP（编译期保证一致） |
| 服务端 | Ktor + 内置 Admin（SQLite + bcrypt + SSE + 告警 + 历史回放）|
| **自动化测试** | **186 个 @Test / 14 个 *Test.kt**（覆盖关键路径） |
| 部署 | Caddy 自动 HTTPS + `/admin/` 子路径 + GitHub Actions auto-deploy |

### 后续建议行动（PR #62 已落地的标 ✅）

| 状态 | 行动 |
|------|------|
| ✅ 已实现 | `:shared` KMP（PR #35）/ `PROTOCOL_VERSION` + 握手（PR-H3） |
| ✅ 已实现 | harness L0-L4（hooks / playbook / regressions）|
| ✅ 已实现 | tdd-gate CI 硬关（PR-H2）/ pr-reviewer subagent（PR-H5）|
| ✅ 已实现 | **监控告警**（PR #61/3：AlertEngine 3 内置规则 + PR #62/5a SSE 推送）|
| ✅ 已实现 | **历史游戏回放**（PR #62/5d：game_events 表 + 逐手出牌持久化）|
| ⚪ 待规划 | step-through 回放 UI / 玩家账号系统（模块 4）/ 弱网 e2e |

---

## 八、harness 跨会话经验（1/3）— Phase 分段 + wasmJs

### 大特性 Phase 分段提交（PR #53 实战）

| Phase | 内容 | 价值 |
|-------|------|------|
| **Phase 1** | 协议 + 服务端 + 单测 | 底层稳了再动客户端 |
| **Phase 2** | 主客户端 (Android) | 一份吃通验证 server |
| **Phase 3** | 次客户端 (Web) + 跨端测 | 最后补齐 |

适用：跨协议 + 服务端 + 双客户端 > 200 行 / > 5 文件。每 Phase 内还按编译单元切（Android / Web 拆 commit）。

### wasmJs 编译器隐藏陷阱（regressions #12）

```
Backend Internal error: Exception during psi2ir
Caused by: java.lang.NullPointerException
```

**根因**：可空 lambda + Compose smart-cast；`vm::method` KFunction → `(() -> Unit)?` 自动转换

**修法**：
- 可空 lambda → `local val` 固化非空
- 函数引用 → 显式 `{ vm.foo() }` lambda

> **教训**：jvmTest 过 ≠ wasmJs 过；写完 Web UI 必须 push CI 才算

---

## 八、harness 跨会话经验（2/3）— Codex 互补 + delay 重检

### Codex bot 与 Claude /review-pr 盲区互补（PR #53 实证）

| Reviewer | 找到 | 漏掉 |
|----------|------|------|
| **Claude /review-pr** | docs 误写 + 引用不存在函数（**跨文件契约**）| `delay()` 后 race |
| **Codex bot** | `delay()` 后没重检 `isAISubstitute` 的 race（**语句级边界**）| 文档与代码语义对齐 |

> **盲区不重叠** → 单独跑一个**至少漏一类**。两个都跑才算双重过关。

### `delay()` 后状态过期反请（regressions #11）

```kotlin
delay(effectiveAiDelayMs(...))     // 玩家此时取消了托管
mutexFor(room).withLock {
    if (state.currentPlayerIndex != playerIndex) return  // 只检查了这一项
    decideAIAction(...)             // isAISubstitute 已变 → 不该再代打
}
```

**审查清单**：醒来后必须重检"延迟前所有依赖项"——不只 `currentPlayerIndex`。
**修法**：抽 `internal fun shouldYieldToHumanPlayer(...)` 谓词 + 同 commit 加测。

---

## 八、harness 跨会话经验（3/3）— L0–L4 体系

### Harness 5 层防御（PR-H1..H5 + #35 web 重构 实战搭建）

| 层 | 工具 | 说明 |
|---|------|------|
| **L0** | Settings / hooks | PostToolUse 注入"关键路径 TDD 提醒"+ push 后自动 review-check |
| **L1** | Slash commands | `/test-fast` · `/pre-commit-scan` · `/ship-check` · `/review-pr` |
| **L2** | Subagents | pr-reviewer (Opus) · protocol-syncer · tdd-scaffolder |
| **L3** | CI gates | tdd-gate（关键路径必同改 *Test.kt）+ build + detekt |
| **L4** | Documentation | playbook + regressions DB（每条 8 字段：症状/根因/修复/教训/防回归测试）|

### 4 个跨会话原则

| 经验 | 说明 |
|------|------|
| **同 commit `*Test.kt` 配对** | tdd-gate 一次没误报、一次没漏；hook 弹"TDD 提醒"时不必另开 commit |
| **分支 vs PR 一一对应** | PR merge 后开新分支；老分支再 push 不会自动出 PR（PR #52→#53 实战）|
| **文档单一真相 grep 验证** | 新 doc 自称"权威"前必 grep 验证 anchor 函数名实际存在 |
| **速度档位用 3 档预设** | slider 看似灵活；实际多数场景 3 档够用 + 测试矩阵更小 |

> **核心**：harness 让教训跨 session 沉淀，新人 / 新 AI 不需要每次从头踩坑

---

## 九、Admin 后台实战：PR 0~N + 优化收尾分段（PR #61–62）

### 9 段 commit / 2 个 GitHub PR

| 段 | PR | 主题 | LOC |
|----|----|------|----|
| **PR 0** | #61 | 骨架：bind 127.0.0.1 + 提取 gameModule + install CN/StatusPages | ~150 prod / ~80 test |
| **PR 1** | #61 | SQLite + bcrypt + RBAC + /admin-auth/* | ~700 / ~470 |
| **PR 2** | #61 | 6 GET 监控 API + SnapshotBuilder + GameHistoryStore | ~700 / ~350 |
| **PR 3** | #61 | AlertEngine（3 内置规则）+ alerts 表 | ~400 / ~200 |
| **PR 4** | #61 | Vue 3 SPA + Caddy /admin/ + CI admin-build | ~1,800 LOC |
| **PR 5a-d** | #62 | SSE 告警 / Dashboard 趋势图 / JSON 日志 / game_events | ~800 / ~150 |

### 3 条提炼经验

- **1 commit = 1 ship-able 单元**：即便最终合到同一 PR，PR 0 review 焦点 ≠
  PR 1 review 焦点，互不混淆
- **plan 先行省 ~30% 返工**：4 轮 AskUserQuestion 收敛范围 → 编码不反复改向
- **CI 修复回路占非平凡时间**：PR #61-62 跑了 4 次 CI 修复（Kotlin 嵌套块注释 /
  arrayOf+= / runTest 虚拟时钟 / withCharset import），靠 `tee + Surface-on-failure`
  把日志 exfil 到 PR 评论才能在沙箱里读到错误

---

## 九、质量金字塔：PR #58-62 五层审查量化实证

### 五层各自唯一识别的问题

| 层 | 触发方式 | PR #58-62 唯一抓到的 |
|---|---------|----|
| L1 Claude 主会话 | 设计 / 重构时自查 | ~10 个潜在问题 + 4 轮 AskUserQuestion 收敛范围 |
| L2 CI 自动跑 | push 后 | **4 次工具链 quirks**（嵌套块注释 / arrayOf+= / withCharset / runTest 虚拟时钟）|
| L3 pr-reviewer subagent | `/review-pr` 手动 | PR #61：**1 P0** AlertDto 未定义 + 4 P1 |
| L4 Codex bot | PR 创建自动 | PR #62：1 P2 gameEventListener 锁外致并发 seq race |
| L5 用户真机 | 部署后实际用 | **1 P1** Web 连点 3 次（Compose 重组延迟期 UI race，无单测可写）|

### 撤掉某层的回退

> **撤 Claude 主会话** → 10 个设计期消灭的 bug 变成实现期 bug，迭代成本 ×10
> **撤 CI** → 4 次编译失败流到 main
> **撤 pr-reviewer** → AlertDto 未定义直接破 main（admin 模块全加载不出来）
> **撤 Codex** → admin 历史回放数据废（seq 错位）
> **撤用户真机** → 部署后用户实际进不了大厅

**质量基线**：本期 P0/P1 合并前 = 0 · Codex 误报 = 0 · 测试用例/LOC = 8 / 1k LOC

---

## 九、Token 用量实测（仅 Claude Code 接入后阶段）

> **数据缺口**：早期 PR #1-50（2 月单机 → 5 月 9 日 Web 功能）transcript 不在本机；
> 表只列已采集的 5/10-5/14 五天。

| 阶段 | 日期 | 轮次 | 输出 | 缓存读 | 计费等效* |
|------|------|----:|----:|------:|--------:|
| AI 托管 + UI 约束（#51-58）| 5/10 | 951 | 904K | **267.4M** | **41.1M** |
| bermin.cn HTTPS（#60）| 5/11 | 76 | 46K | 28.9M | 5.1M |
| WS 重连 + HTML docs（#59）| 5/12 | 232 | 243K | 8.6M | 4.2M |
| Admin MVP + PR 5（#61-62）| 5/13 | 897 | 1.44M | **293.8M** | **43.3M** |
| 今日 doc 刷新 | 5/14 | 54 | 70K | 37.1M | 7.6M |
| **5 天合计** | — | **2,210** | 2.7M | **635.7M** | **~101M** |

\*计费等效 = `pure_input + cache_creation + cache_read × 0.1 + output`（Anthropic cache 读价 1/10）

### 观察

- **cache_read 主导 94%**：每轮重读 CLAUDE.md + 代码上下文 ~300K token；Claude Code 的
  prompt cache 让"重复读已知内容"成本远低于"每轮从头输入"
- **两个尖峰对应大特性日**（5/10 AI 托管 / 5/13 Admin 后台）
- **3 天 ~$300-400 量级**（按 Opus 4.7 标准定价粗算 5 天高强度开发）

---
<!-- 结束页 -->

# 谢谢

<br>

> **核心结论**（PR #62 更新）：
> **5 层审查覆盖**（人工真机 / Claude 主会话 / pr-reviewer / Codex / CI）+
> **harness 跨会话沉淀**（L0–L4）= 教训不浪费、bug 不重复

<br>

> **可量化质量基线**：本期 P0/P1 合并前 = 0 · Codex 误报 = 0 · 业务级遗漏 = 0
> · 用户报 bug → regressions.md 延迟 < 2 小时

<br>

**问题 & 讨论**

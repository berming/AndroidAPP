# 沟通牌项目 — 历史 Bug 数据库（Regression Cold Storage）

> "修了又坏"的根因是经验不传承。本文件把每个**已修复**的关键 Bug
> 存档为可索引的条目，新会话进来即可读到全部教训，避免重复踩坑。
>
> **新增规则**：每修复一个 P0 / P1 Bug，都必须在此文件追加一条记录，
> 包含「症状 / 根因 / 修复 commit / 防回归测试」。`/ship-check` 在
> PR-H2 后会校验关键路径改动是否同步更新本文件。

每条记录的字段统一为：
- **症状**：用户可见的 1-2 句失败描述
- **根因**：技术性根本原因（不是表层修补）
- **修复**：commit SHA / PR #
- **教训**：可推广的设计原则
- **防回归测试**：测试文件 + 测试名（缺失则注明，列入 PR-H2 待补）

---

## #1  CardSuit 枚举不匹配 → 大厅崩溃

| 字段 | 内容 |
|------|------|
| 症状 | 点击"多人游戏"进入大厅立即崩溃，反序列化抛 `SerializationException` |
| 根因 | 服务端枚举值 `clubs/diamonds/hearts/spades`（小写），客户端 `CLUB/DIAMOND/HEART/SPADE`（大写）；kotlinx.serialization 用 classDiscriminator 反序列化时失配 |
| 修复 | PR #33 / commit `b038e73` |
| 教训 | **协议两端的枚举字符串值是契约**。CLAUDE.md 约束 4 直接源于此。约束靠人记 → 必有漂移；唯一可靠的根治是 PR-H3 把 server 并入 `:shared`，让编译器保证一致 |
| 防回归测试 | 缺失 → PR-H2 补 `GameMessageSerializationTest.kt`：每个 `@Serializable` 类做 round-trip 测试 |

---

## #2  游戏卡死（4 层防御）

| 字段 | 内容 |
|------|------|
| 症状 | 等待电脑出牌时游戏永不响应，必须主动退出重开 |
| 根因 | 同一现象**四层根因**：(L1) `canBeat` 比较不同张数炸弹时优先级颠倒，AI 选出的 5×3 被拒；(L2) AI 首选失败无回退链，直接返回过牌；(L3) `state.hands` 多协程无锁并发，触发 `ConcurrentModificationException`；(L4) 全部回退失败时无 `force-advance` 兜底，游戏永久卡死 |
| 修复 | 4 个连续 commit：`fb6cc7c`（canBeat） + `8969125`（Mutex + force-advance） + `8a56e14`（playerScores 一致性）|
| 教训 | dev_summary.md 第六章：「**修了又坏的根因是只修表层症状没往深挖根因**。卡死问题历经 4 层才彻底解决。原则：找到最小可复现场景，分层排除，验证到底。」一个 Bug 经常对应多个**结构性**根因 → 修复后必须由独立会话审查「这个根因还能从哪里发生」 |
| 防回归测试 | **完全缺失** → PR-H2 补 `ServerGameManagerTest.kt` 覆盖：(a) `canBeat(5×3, 4×10)` 返回 true；(b) AI 三级回退链各自的 fallback 行为；(c) Mutex 保护下并发 action 的手牌张数守恒；(d) 全员 pass 后 force-advance 是否推进 |

---

## #3  WebSocket CONNECTING 时 send() 静默丢弃 → 重连失效

| 字段 | 内容 |
|------|------|
| 症状 | 网络断开后自动重连建立了连接，但服务端没收到 `Reconnect` 消息，客户端被当成新用户，原房间映射丢失 |
| 根因 | `NetworkManager.connect()`：`ws = client.newWebSocket(...)` 返回时 socket 仍是 CONNECTING；立即 `send()` 返回 false 且**无异常**。OkHttp WebSocket 真正建立是在 `onOpen()` 回调中 |
| 修复 | commit `e8927e8`（多轮人工排查 + 静态审查后定位） |
| 教训 | **异步 API 中"创建对象 ≠ 已就绪"**。任何首次发送必须延后到 onOpen 内执行。CLAUDE.md 约束 3 即由此沉淀 |
| 防回归测试 | 缺失 → 难以单测（需 mock OkHttp WebSocket 状态机）；可在 PR-H4 用 MockServer 做 e2e：模拟 onOpen 之前 send() 立刻返回失败 |

---

## #4  已收分 / 队伍分硬编码为 0 → 结算错误

| 字段 | 内容 |
|------|------|
| 症状 | 游戏中每个玩家的"已收"分数始终显示 0；结算时赢方得分异常 |
| 根因 | `ServerGameState` 缺 `playerScores` 字段；`getStateForPlayer` 中 `collectedScore = 0` 硬编码；`handleRoundEnd` 只累加队伍总分，未同步个人分。结算公式两个信息源（个人 vs 队伍）不一致 |
| 修复 | commit `8a56e14` |
| 教训 | **硬编码占位符是隐藏 Bug 的最佳藏身处**。这种问题人工"看 UI"很难发现（数字看起来"在变"），但代码中清晰地写着 `0`。AI 静态扫描比人工更容易发现这类「藏在代码里的问题」 |
| 防回归测试 | `SettlementCalculatorTest.kt`（已有 15 用例）→ PR-H2 扩展：跑 server 单测验证 `getStateForPlayer.collectedScore` 与累计的 round 收分一致 |

---

## #5  UUID 截断到 8 字符 → 会话碰撞（Codex P1）

| 字段 | 内容 |
|------|------|
| 症状 | 多用户并发时，A 的重连可能被分配到 B 的房间；跨用户状态污染 |
| 根因 | `server/Application.kt`：`UUID.randomUUID().toString().take(8)` 把 36 字符 UUID 截到 8 字符；只剩 32 位熵；`sessions[id]` 与 `playerToRoom[id]` 在中等流量下产生碰撞 |
| 修复 | commit `06d445c`（**Codex Review Bot 在 PR #29 指出**，4 天后修复） |
| 教训 | **常用 ≈ 正确**这条假设是漏洞温床——`take(8)` 在客户端密钥中很常见，但分布式会话 ID 必须保留全部熵。CLAUDE.md 约束 5 即由此沉淀。这条 Bug **Claude 多轮审查没发现，Codex 一次发现**，是 dev_summary.md 8.3「跨 vendor 对抗审查」的最强证据 |
| 防回归测试 | 缺失 → PR-H2 补 `ServerRoomManagerTest`：注入 1k 个 UUID，验证 `playerToRoom` 唯一性；可加 `assert id.length == 36` |

---

## #6  ArrayList 并发修改异常

| 字段 | 内容 |
|------|------|
| 症状 | 服务端随机抛 `ConcurrentModificationException` 或游戏卡死 |
| 根因 | 多协程（玩家 action handler / 30s 超时计时器 / 断线处理器 / AI 任务）同时写 `state.hands: MutableList<Card>`；`handleAction` 与 `processAITurn` 没有同步原语 |
| 修复 | commit `8969125`（per-room `Mutex` + `state.players` 改 `CopyOnWriteArrayList`） |
| 教训 | **协程 + 共享可变状态**模式在低并发"看似没事"，中等并发立刻爆。CLAUDE.md 约束 2 沉淀此教训：任何修改服务端 game state 的代码都**必须**在 `mutexFor(room).withLock { ... }` 内。**广播必须在锁外**，否则慢客户端阻塞房间所有动作 |
| 防回归测试 | 缺失 → PR-H2 补：3+ 协程并发提交 action，断言手牌张数守恒（`Σ playerHands.size + Σ collectedCards.size == 216`） |

---

## #7  Loading 遮罩永久卡住（Codex P1 + 人工）

| 字段 | 内容 |
|------|------|
| 症状 | 创建/加入房间后网络断开，Loading 遮罩永不消失，UI 完全无法交互 |
| 根因 | `LobbyActivity.createRoom/joinRoom` 发请求后显示 Loading；`ConnectionState.Disconnected/Reconnecting` 的状态回调未触发隐藏逻辑；客户端等响应（永不到达），遮罩卡住 |
| 修复 | PR #33 — commits `9b302d6` + `b038e73`（分两轮才修干净）|
| 教训 | **UI 状态机的"非 happy path"分支最容易遗漏**。dev_summary.md 第五章原话：「人工可在真机上观察到这类问题，AI 看代码难以发现」。流程上对应：4 关 PR 流程的第 4 关「真机验证」是 AI 的盲区补丁 |
| 防回归测试 | 缺失 → 流程层面补：PR 模板第 4 关复选框「在真机/模拟器上断网测试 Lobby 流程」 |

---

## #8  结算公式漏算"输方未走完玩家已收分"

| 字段 | 内容 |
|------|------|
| 症状 | 全队走完触发结算时，赢方得分比预期低；输方部分未走完玩家时尤其明显 |
| 根因 | 结算公式 `赢方分 = 赢方已收 + 输方未走完玩家已收 + 输方未走完玩家手牌分`，旧版**漏了第二项**（未走完玩家已收分），只算了手牌分 |
| 修复 | commit `8a56e14`（与 #4 playerScores 同时修复，因为漏算的本质是 playerScores 字段缺失） |
| 教训 | **金钱相关逻辑必须 TDD**。单机版 `SettlementCalculator` 之所以 3 个月零回归，因为 15 个用例覆盖了所有触发条件；联网版没测试，反复出问题。这就是 PR-H2 必须补 `ServerGameManagerTest.computeAllFinishedScores` 的原因 |
| 防回归测试 | `SettlementCalculatorTest.kt`（15 用例已存在）；PR-H2 在 server 端跑同一组用例验证一致 |

---

## 教训综合（来自 dev_summary.md，与本表对应）

> "修了又坏"的根因：**只修表层症状，没往深挖根因**。卡死问题历经 4 层才彻底解决。
> 原则：找到最小可复现场景，分层排除，验证到底。

> AI 辅助的正确分工：AI 全量扫描 → 人工真机验证 → AI 生成修复。
> 最优工作流是**多 AI 串行 + 人工最后把关**。

> 多 vendor 交叉审查比单一审查更可靠。Claude（全局逻辑链路）+ Codex（细粒度风险点）几乎不重叠。
> 本项目 UUID 截断（#5）、Loading 卡死（#7）两个 P1 仅被 Codex 或人工发现。

---

## 防回归策略（PR-H2 起逐步落地）

| 类别 | 落地点 |
|------|--------|
| 关键路径 TDD | `CardRulesTest.kt`（PR-H2 新增）、`ServerGameManagerTest.kt`（PR-H2 新增）|
| 共享规则层 | `:shared` KMP 模块（已立项，PR-H3 把 server 也并入）|
| 协议版本号 | `protocolVersion` 字段（PR-H3）|
| 监控告警 | `force-advance` 计数指标 → 触发即告警（暂未规划，见 plan 第七章「暂不纳入」）|
| 弱网测试 | 集成限速 e2e（暂未规划）|
| 跨 vendor 审查 | 4 关 PR 流程 + Codex Bot（已就绪）；季度手动 Gemini / Cursor 审查（PR-H4 文档化）|

---

## 维护规约

新增条目时遵循同样的 8 字段表格 + 段落式教训。条目编号递增，永不
回收。即使后续把整段功能删除，记录也保留——历史不该被覆盖。

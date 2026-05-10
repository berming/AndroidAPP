# 沟通牌 · 游戏规则

> 本文档是**沟通牌玩法的权威定义**，同时面向：
> - **玩家**：第一次接触沟通牌的人，从 §1 看到 §4 即可学会怎么玩
> - **开发者**：实现新客户端、改 AI、调结算公式时的参照；§5–§7 包含具体函数 / 测试 / 不变量
>
> **真相在代码里**。本文与代码不一致时以代码为准 →
> [`shared/.../engine/CardRules.kt`](../shared/src/commonMain/kotlin/com/communicationcard/game/engine/CardRules.kt) ·
> [`shared/.../engine/SettlementCalculator.kt`](../shared/src/commonMain/kotlin/com/communicationcard/game/engine/SettlementCalculator.kt)
> 以及对应测试 [`CardRulesTest.kt`](../shared/src/commonTest/kotlin/com/communicationcard/game/engine/CardRulesTest.kt) ·
> [`SettlementCalculatorTest.kt`](../shared/src/commonTest/kotlin/com/communicationcard/game/engine/SettlementCalculatorTest.kt)。
> 改本文请同步代码 / 测试 / [PR-H2 tdd-gate](../.github/workflows/android-ci.yml) 三件套。

---

## 1. 一句话玩法

**沟通牌是 6 人 2 队的扑克对抗：每队 3 人坐成红蓝队相间，先把队伍累计已收分打到 ≥200 分获胜。**
出牌时只能"压上家"或者"过"；走完手牌的玩家先离场，最后剩牌的玩家手上的分给对方算。

> **注**：本作不支持顺子。要一次走多张只能凭炸弹（≥4 同点）。

---

## 2. 牌、座、队

### 2.1 用牌

- **4 副完整扑克 = 216 张**（4 × 54）：每副 13 种点数 × 4 花色 + 大小王各 1
- **大小顺序**（从大到小）：

  ```
  大王 > 小王 > 2 > A > K > Q > J > 10 > 9 > 8 > 7 > 6 > 5 > 4 > 3
  ```

- **分值牌**：

  | 点数 | 分值 | 副数 × 4 = 张数 | 总分 |
  |------|-----:|----------------:|-----:|
  | K | 10 | 16 | 160 |
  | 10 | 10 | 16 | 160 |
  | 5 | 5 | 16 | 80 |
  | 其他 | 0 | — | — |
  | **合计** | | | **400** |

  > 全场总分恒为 400 分，这是结算时验算"A 队 + B 队 == 400"的金标准（提前结算除外，见 §4.2）。

### 2.2 座位与队伍（默认 6 人配置）

```
        座位 0 (A 队)
   座位 5              座位 1
   (B 队)              (B 队)
   座位 4              座位 2
   (A 队)              (A 队)
        座位 3 (B 队)
```

- 6 个座位顺时针排序，**奇偶交替分队**：A 队 = {0, 2, 4}，B 队 = {1, 3, 5}
- 没满 6 人时服务端用 AI 自动补位，AI 也按座位号自动入队
- 引擎层支持 6 / 8 / 10 / 12 人（`Deck.deal()` 要求；见
  [`Deck.kt:55`](../shared/src/commonMain/kotlin/com/communicationcard/game/model/Deck.kt#L55)），
  但当前生产环境固定 6 人

### 2.3 出牌顺序

- 起手玩家随机（服务端 `randomFirstPlayer`）
- 之后**赢牌方**（最近一手没人压住的玩家）成为下一回合的首家
- 任意玩家走完手牌后立即离场；下一回合首家由最后一手赢牌方决定
- 单回合超时 **30 秒**，超时由服务端 AI 接管该手出牌（仅本手；下手玩家恢复手动）

---

## 3. 出牌

### 3.1 四种合法牌型

| 牌型 | 张数 | 约束 |
|------|------|------|
| **单张** | 1 | 任意 |
| **对子** | 2 | 同点数（花色不限，可跨副） |
| **三张** | 3 | 同点数 |
| **炸弹** | ≥ 4 | 同点数 |

**不支持顺子**——任何 5 张及以上的连续点数组合都不是合法牌型。
要一次走多张只能凭炸弹。设计动机：把"一次走多牌"的口子收紧到只有炸弹一条路径，
让"沟通"对节奏的影响更主要、避免顺子拆牌带来的复杂博弈。

> 历史：项目早期支持顺子（含 Q-K-A-2-3 循环），后续移除。详见
> commit 历史与 [`CardRulesTest.kt`](../shared/src/commonTest/kotlin/com/communicationcard/game/engine/CardRulesTest.kt)
> 的 `*_returnsNull_straightRemoved` 系列防回归测试。

### 3.2 跟牌：什么能压住什么

定义：**当前一手** vs **上家最后一手有效牌**（`canBeat`）。

| 上家 | 当前 | 是否能压 |
|------|------|----------|
| 无（自由出牌）| 任何合法牌型 | ✓ |
| 非炸弹 X | **同类型同张数**且点数更大的 X | ✓ |
| 非炸弹 X | 任意炸弹 | ✓ |
| 非炸弹 X | 类型/张数不同的非炸弹 | ✗ |
| 炸弹 | 非炸弹 | ✗（必须用炸弹压） |
| 炸弹（n 张）| 炸弹（m 张），m > n | ✓ |
| 炸弹（n 张）| 炸弹（n 张）点数更大 | ✓ |
| 炸弹（n 张）| 炸弹（n 张）点数 ≤ | ✗ |

**关键不变量**：炸弹间**先比张数，张数相同再比点数**。所以 **5×3** 能压 **4×10**——
不要凭"K 比 3 大"想当然。这是历史 Bug 第 2 条（[regressions.md](regressions.md)）。

### 3.3 过牌（Pass）

- 任何时候上家有牌时都可以过；**首家**（自由出牌时）不能过
- 过牌不影响"已收"
- 一圈所有人都过 → 上次出牌人赢得这一手，他赢得**这手出牌涉及的全部牌**（不只他自己的）

> 这是"已收"的来源：你赢了一手，全部 N 张牌都进你的"已收堆"，分值累计。

---

## 4. 计分与结算

### 4.1 实时计分

- **个人"已收"** = 你赢的所有手里所有牌的分值之和（K=10 / 10=10 / 5=5）
- **队伍累计分** = 本队所有玩家"已收"之和（实时显示在 UI 顶部）
- 客户端**只显示**服务端推送的 `playerScores` / 队伍分；不要本地推算

### 4.2 触发结算的两种方式

**触发 A：一队全部走完手牌**

```
赢方得分 = 赢方已走完玩家"已收"之和
        + 输方未走完玩家"已收"之和
        + 输方未走完玩家"手牌分"之和

输方得分 = 输方已走完玩家"已收"之和（无人走完则 0）
```

**触发 B：一队"已走完玩家的已收"≥ 200**

```
触发队得分 = 该队已走完玩家的"已收"
对方得分  = 对方已走完玩家的"已收"
（未走完玩家的累计分都不算）
```

> ⚠️ 触发 B 下，**未走完玩家的累计分作废**——这是设计选择（鼓励抢先走完），不是 Bug。
> 触发 A 下双方总分恒为 400；触发 B 下不一定（可能 < 400，参见 [`settlement_verification.md` 用例 9](settlement_verification.md#用例-9-提前结算)）。

### 4.3 胜负判定

```
A 分 ≥ 200 且 B 分 ≥ 200 → 比分高者胜（实际很难触发，因为先达 200 通常已结算）
A 分 ≥ 200            → A 胜
B 分 ≥ 200            → B 胜
都 < 200                → 比分高者胜（仅触发 A 全员走完场景）
分数相等                 → 平局（极罕见）
```

代码：[`SettlementCalculator.determineWinner`](../shared/src/commonMain/kotlin/com/communicationcard/game/engine/SettlementCalculator.kt#L109-L124)。

### 4.4 验证用例

15 个手算用例 + 单元测试覆盖结算公式 → 见 [`docs/settlement_verification.md`](settlement_verification.md)。
**任何修改 `SettlementCalculator` 的 PR 都必须保持这 15 个用例全过**——CI 的
`tdd-gate` 强制执行。

---

## 5. 通信（"沟通"的来源）

沟通牌的"沟通"指队友间**有限的合规信息交换**，弥补不能看队友手牌的信息差。

| 通信方式 | 信道 | 说明 |
|---------|------|------|
| **出牌本身** | 牌局 | 出 / 过 / 选择牌型，是默契建立的主信道 |
| **聊天文字** | 聊天面板 | 200 字内文字消息；可选"队内"或"全部"两个频道 |
| **快捷消息** | 聊天面板 | 4 个内置短句：`好牌！` / `要不起` / `队友上！` / `GG` |

### 公平性边界

- 聊天频道是平台级的，**不允许**通过外部信道（语音 / 截屏 / 其他 IM）泄露具体手牌
- 客户端绝不暴露其他玩家手牌内容；服务端 `getStateForPlayer` 把别人手牌字段清空再下发
- 任何"看穿对手手牌"的客户端实现 = 严重违规；详见 [客户端实现指南 §10](client_implementation_guide.md#10-不做的事明确拒绝)

---

## 6. 端到端流程（开发参考）

### 6.1 一局对战的状态机

```
WAITING (大厅 / 房间)
   ↓ 房主点开始 + 至少 1 真人就绪 + AI 自动补位到 6 人
IN_GAME
   ├─ 每回合：当前玩家 出牌 / 过牌 → 服务端校验 canBeat → 广播 GameEvent
   ├─ 走完玩家：标记 finished，下回合首家 = 当前回合赢家
   ├─ 检查结算条件（每回合结束 + 玩家走完时）
   │     │
   │     ├─ 一队全员走完 → SettlementResult (TEAM_ALL_FINISHED)
   │     └─ 一队 finishedScore ≥ 200 → SettlementResult (SCORE_REACHED_200)
   ↓
FINISHED
   ↓ 广播 GameEnd（含 SettlementResult）
   ↓ 所有真人离开 → 房间立即清理；否则保留供"再来一局"
```

### 6.2 关键代码位置

| 行为 | `:shared`（客户端 + 单机）| `:server`（多人模式权威）|
|------|-----------------------|---------------------|
| 牌型识别 | `CardRules.identifyCardGroup` | `ServerGameManager.identifyGroup` |
| 跟牌合法性 | `CardRules.canBeat` | `ServerGameManager.canBeat`（**必须等价**）|
| 自由出牌的所有合法组合 | `CardRules.findValidPlays` | — |
| 单局发牌 | `Deck.deal` | `ServerRoomManager.startGame` 调 `Deck` |
| 单机循环 | `GameEngine.playCards / pass` | `ServerGameManager.handlePlayCards / handlePass` |
| 结算公式 | `SettlementCalculator.calculate` | `ServerGameManager.computeAllFinishedScores`（**必须等价**）|
| AI 决策 | `AIPlayer.decide` | 调 `:shared/AIPlayer.decide`（已统一）|

> 客户端和服务端**两份 `canBeat`** 是历史包袱（PR-H3 后客户端侧已统一到 `:shared`，
> 但服务端仍持有自己的实现）。修改时必须**两边同步改 + 两边都加测试**。这是
> [CLAUDE.md §约束 1](../CLAUDE.md) 的核心警告。

### 6.3 不变量（实现新端 / 改服务端时不能破）

1. **总分恒等**：触发 A 结算时 `teamAScore + teamBScore == 400`
2. **回合归属**：`handleRoundEnd` 后 `currentPlayerIndex == winnerId`（一手赢家是下手首家）
3. **`canBeat` 单调**：若 `canBeat(X, Y) == true`，则 `canBeat(Y, X) == false`（炸弹 vs 同张数同点数除外，但同张数同点数不可能同时存在两份）
4. **服务端是唯一真相**：客户端 `applyState` 时若收到的版本号 < 本地版本号，**丢弃**（避免回滚）
5. **客户端不能伪造合法性**：服务端 `canBeat` 永远独立校验，客户端预校验只为减少无效请求

---

## 7. 修改规则的流程

新增 / 调整规则**永远从测试开始**（TDD 强制路径，[CLAUDE.md §三](../CLAUDE.md)）：

1. 在 `CardRulesTest.kt` 或 `SettlementCalculatorTest.kt` 加**失败用例**
2. 改 `:shared/CardRules.kt` / `:shared/SettlementCalculator.kt` 让测试过
3. 同步改 `:server/.../ServerGameManager.kt`（`canBeat` / `computeAllFinishedScores`）
   + 在 `ServerGameManagerTest.kt` 加对应用例
4. 更新本文档（§3 / §4）+ Android `strings.xml` 的 `rules_content` + Web 端规则面板
5. 跑 `./gradlew :shared:jvmTest :server:test detekt` 全绿
6. 走 4-gate PR：CI / Codex / Claude /review-pr / 真机；同时 `protocol-syncer` 会
   检查协议是否 affected——若是，**必须** bump `PROTOCOL_VERSION`
7. 在 [`docs/regressions.md`](regressions.md) 登记一行：症状 / 根因 / 测试 / 教训

---

## 8. 速查卡（贴墙版）

```
牌：4 副 × 54 = 216 张，总分 400（K=10×16, 10=10×16, 5=5×16）
人：6 人 2 队，A={0,2,4} B={1,3,5}
牌型：单 / 对 / 三 / 炸（≥4，不支持顺子）
压：同型同张比点；炸压非炸；炸 vs 炸先比张再比点
结算 A：一队全走完 → 赢=本队走完已收+对方未走完(已收+手牌)
结算 B：finishedScore≥200 → 双方各按"走完已收"计；未走完作废
胜：先达 200 / 比分高 / 平局
30s 超时 AI 接管；客户端绝不本地推算分数 / 合法性
```

---

## 9. 相关文档

- [架构总览](architecture.md) — 模块布局 / 跨模块依赖 / 部署链
- [跨端功能规格](feature_spec.md) — 客户端 MUST/SHOULD/MAY 矩阵
- [客户端实现指南](client_implementation_guide.md) — 实现新客户端的参考路径
- [多人游戏指南](multiplayer_guide.md) — 协议 / 部署 / 排错
- [结算验证](settlement_verification.md) — 15 个手算用例（数学规约）
- [历史 Bug 库](regressions.md) — 防回归测试登记

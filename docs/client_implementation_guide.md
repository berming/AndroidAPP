# 沟通牌 · 客户端实现指南

> **目标读者**：要在新平台（iOS / Desktop / CLI / 第三方语言）实现沟通牌
> 客户端的工程师。本文给你"现有 Android / Web 端是怎么做的 + 你应该怎么
> 复用 + 哪些是平台特定"的参考架构。
>
> 配套：[架构总览](architecture.md) · [跨端功能规格](feature_spec.md) ·
> [多人游戏指南](multiplayer_guide.md)

---

## 1. 决策树：新端用什么技术栈？

```
新客户端目标平台?
│
├── 是 Kotlin 友好（iOS / JVM Desktop / Native / Linux / Windows）
│      │
│      ├── 想最大复用 + 最小维护？
│      │      → KMP target + Compose Multiplatform UI（推荐路径，§3）
│      │
│      └── 想用平台原生 UI（SwiftUI / WinUI3）？
│             → KMP target，UI 层平台原生（§4）
│
└── 不是 Kotlin（Rust CLI / Go bot / 老 C++ 项目 ...）
       → 自行翻译 :shared/network/GameMessage.kt + 协议契约对齐（§5）
```

> 对维护成本而言：**KMP + 同语言 = 最低；KMP + 原生 UI = 中；非 Kotlin = 最高**。
> 维护成本 ≈ (字段数量 × 协议变更频率 × 端数) ÷ 共享比例。

---

## 2. 三档共享契约（**所有新端都要懂**）

来自 [架构总览 §11.1](architecture.md#111-三档共享)：

| 档位 | 必须共享 | 推荐共享 | 平台特定 |
|------|---------|----------|---------|
| **协议层** | `:shared/network/GameMessage.kt` 全部字段 + `PROTOCOL_VERSION` + `CardSuit` 枚举字符串 | — | — |
| **业务规则** | — | `:shared/engine/CardRules.kt`（牌型 / `canBeat`）<br>`:shared/engine/SettlementCalculator.kt`（结算）<br>`:shared/engine/GameEngine.kt`（单机驱动）<br>`:shared/ai/AIPlayer.kt`（AI 决策） | — |
| **平台层** | — | — | UI 渲染 / 网络栈 / 持久化 / 字体 / 输入 |

**两个底线**：

1. 协议层一字之差 = 服务端拒连。改动 `:shared/GameMessage.kt` 必须改
   `PROTOCOL_VERSION`（`protocol-syncer` 会卡）；新端 1.0 版固定到 main 当时的版本号。
2. 客户端**绝不本地推算分数 / 是否合法 / 谁的回合**。永远以服务端推送的
   `state` 为准。本地 `CardRules` 只用于"出牌前预校验"和"提示按钮算法"。

---

## 3. 推荐路径：KMP target + Compose Multiplatform

### 3.1 加 target

`shared/build.gradle.kts`：

```kotlin
kotlin {
    androidTarget()
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    // 新加的：
    iosArm64()
    iosX64()
    jvm("desktop")          // 区分于已有 jvm() (server uses)
    linuxX64()
    // ...
}
```

`commonMain` 的代码直接可用，不必改一行（这是 KMP 的卖点）。

### 3.2 加客户端模块

参考 `:apps:web` 的目录结构：

```
:apps:<platform>/                          (新模块，比如 :apps:ios / :apps:desktop)
├── build.gradle.kts
└── src/<platform>Main/kotlin/com/communicationcard/game/<platform>/
    ├── Main.kt                            入口（启动 Compose 或原生 UI）
    ├── ui/                                Home / Lobby / Room / Game / Settlement
    ├── viewmodel/                         AppViewModel + Screen sealed class
    ├── net/                               WebSocketTransport（platform-specific）
    ├── singleplayer/                      SinglePlayerEngine（包装 :shared GameEngine）
    └── storage/                           本地持久化抽象
```

90% 的 `viewmodel` 代码可以直接抄 `apps/web/.../viewmodel/AppViewModel.kt` ——
那部分逻辑**没有任何 wasmJs 依赖**，只用 kotlinx.coroutines + StateFlow。

### 3.3 平台特定的接口（必须重写）

| 接口 | 用途 | Web 端实现 | Android 端实现 |
|------|------|----------|---------------|
| `WebSocketTransport` | WS 连接 / 心跳 / 重连 | 浏览器 `WebSocket` via `@JsFun` | OkHttp `client.newWebSocket()` |
| `LocalStorage` (key-value) | 偏好 / sessionToken / Statistics | `localStorage.{getItem,setItem}` via `@JsFun` | SharedPreferences |
| `defaultServerUrl()` | 给 Lobby 一个合理默认值 | 同源（host=空回退 localhost）| `ws://10.0.2.2:8080/game` |
| 字体 / 渲染 | CJK 字符不丢字 | 打包 GB2312 子集字体 (~3 MB) | 系统字体直用 |

### 3.4 复用 `:shared` 的 5 个关键 API

```kotlin
// 1. 协议层
import com.communicationcard.game.network.GameMessage
import com.communicationcard.game.network.PROTOCOL_VERSION

// 2. 单机驱动
import com.communicationcard.game.engine.GameEngine
import com.communicationcard.game.engine.GameEvent      // CardsPlayed / TurnStarted / 等

// 3. 出牌合法性 + 提示
import com.communicationcard.game.engine.CardRules

// 4. AI（单机或 mock 多人时用）
import com.communicationcard.game.ai.AIPlayer
import com.communicationcard.game.ai.AIDifficulty

// 5. 模型
import com.communicationcard.game.model.Card
import com.communicationcard.game.model.Player
```

> 这 5 个 import 在 `commonMain` 里都存在，新端无需重新声明。

---

## 4. 路径 B：KMP + 平台原生 UI

适合"用户只用某平台 + 想要原生外观"的场景。

- **iOS / SwiftUI**：`commonMain` 编出 `iosArm64` framework，Swift 通过
  Kotlin/Native interop 调用 `CardRules.canBeat()` 等。UI 完全 SwiftUI 写。
- **Windows / WinUI 3**：`jvm("desktop")` target；UI 用 WinUI 3 / WPF；
  Kotlin 部分提供"业务核心 jar"。
- **macOS / AppKit**：同上，可走 `macosArm64` Native target。

注意：

- 协议 DTO 在 KMP 编译产物里就是普通 class——可以直接被 Swift/Java 端
  消费；**不要重新声明**
- `kotlinx.serialization` 在 Native target 上能跑，新端可以直接用同一份
  json string 编解码

---

## 5. 路径 C：非 Kotlin 客户端（Rust / Go / Swift 直接走 Foundation 等）

最高维护成本，但有合法理由：

- CLI 工具（Rust / Go）—— 想要 zero JVM dep + 单二进制
- 旧 C++ 项目 —— 已存在的引擎 / 团队全栈是 C++
- 第三方研究项目 —— 用 LLM 跑 AI bot 等

**协议契约必须人手对齐**：

1. 翻译 `:shared/.../network/GameMessage.kt` 到目标语言。**字段名严格 camelCase
   一致**（`PlayCards.cards` 不要写成 `cards_to_play`）
2. `CardSuit`、`CardRank` 等枚举的**字符串值**与 Kotlin `name` 一致（如 `CLUB`/`HEART`/`SPADE`/`DIAMOND`，**不要**用 `clubs`/`hearts`/...）
3. `PROTOCOL_VERSION` 锁到主分支当时的数字
4. 写一个"协议契约测试"：发 10 条主流消息（CreateRoom / JoinRoom / PlayCards / Pass / Heartbeat / Reconnect / ...）给主分支 server，断言 echo / 错误回包字段一致

**牌型 / 结算（如果端上要离线模式）**：

5. 翻译 `CardRules.canBeat` —— 单元测试覆盖**至少** PR-H2 引入的 ~30 用例
   （`shared/src/commonTest/.../CardRulesTest.kt`），逐条等价
6. 翻译 `SettlementCalculator` —— 覆盖那 15 个用例（`SettlementCalculatorTest.kt`）

> 不写这层测试 = 联网混战时新端会爆冷出"双方算的分不一样"的 bug，
> 历史教训见 [`docs/regressions.md`](regressions.md) 第 1–3 条。

---

## 6. 现有端的实现细节（参考实现）

### 6.1 Android 端（最早实现）

- **UI**：Activity + XML View（不是 Compose）；`MultiplayerGameEngine` 适配协议消息到本地 UI
- **网络**：OkHttp WebSocket
- **持久化**：SharedPreferences
- **状态机**：直接在 Activity 里持有，没有独立 ViewModel 层（历史包袱）

入口路径表见 [多人游戏指南 §关键文件索引](multiplayer_guide.md#关键文件索引)。

### 6.2 Web 端（后期实现，参考 KMP+CMP 路径）

- **UI**：Compose Multiplatform / Wasm-JS（Skia 渲染到 canvas）
- **网络**：浏览器原生 `WebSocket` via `@JsFun` interop（避开 `kotlinx-browser`，
  因为它要 Kotlin 2.0+，本仓库锁 1.9.24）
- **持久化**：`localStorage` via `@JsFun`，二进制经 base64
- **状态机**：`AppViewModel` (StateFlow<Screen>) — 这是新端最值得抄的部分
- **响应式布局**：`BoxWithConstraints` + `LocalLayoutMode` CompositionLocal
  分 Compact/Medium/Expanded 三档（< 600dp / < 1200dp / ≥ 1200dp）

入口：`apps/web/src/wasmJsMain/kotlin/com/communicationcard/game/web/Main.kt`。

> Web 端的 `viewmodel/AppViewModel.kt` 是**新端最优起点**——把 `wasmJs` 关键字
> 替换成你的 target（iOS / Desktop / 等），90% 直接编。

---

## 7. 测试 / 质量门槛

### 7.1 进 main 前必跑

- [ ] `./gradlew :shared:jvmTest` 全绿（验证你没意外破坏 commonMain 的合约）
- [ ] 与 `:server` 联调跑通 1 局完整对战（启 `:server:run` + 你的端 + 5 AI 填位）
- [ ] 与 Android / Web 混合 1 局（确认协议没漂移）

### 7.2 协议契约金标测试（强烈建议）

新端的 `<platform>Test` 模块里加：

```kotlin
@Test fun `protocol roundtrip - PlayCards`() {
    val msg = """
        {"type":"game.action","action":"play_cards","cards":[
          {"suit":"CLUB","rank":"FIVE"},{"suit":"HEART","rank":"FIVE"}
        ]}
    """.trimIndent()
    val decoded = json.decodeFromString<GameMessage>(msg)
    val reencoded = json.encodeToString(decoded)
    assertEquals(canonical(msg), canonical(reencoded))   // 字段顺序无关
}
```

至少覆盖 10 条主流消息。**这一条不过，端上线后必然出协议 bug**。

### 7.3 真机 / 真浏览器手测

- 单机模式跑完 1 局，结算明细对比 [settlement_verification.md](settlement_verification.md) 任 1 例
- 多人 happy path：建房 → 加 AI → 开局 → 出几手 → 离开 → 重连 → 继续
- 弱网：开 Chrome DevTools throttling slow-3G / 移动端切飞行模式 30s

---

## 8. 协议演进的礼仪

新端发布后，主项目仍会演进 `:shared/GameMessage.kt`。你要做的事：

1. **订阅** `protocol-syncer` 的输出（每个 PR 都跑）
2. `PROTOCOL_VERSION` 升时，新端要做主动决定：
   - 跟随升级（实现新字段，发布新版客户端）
   - 不跟随（旧版客户端在新版 server 上会被拒，需要在你这一侧给用户清晰提示）
3. **不要**自行扩展字段。`extra` / `metadata` 等 free-form 字段会被服务端
   严格序列化校验拒掉

---

## 9. 模板 PR 清单（新端从 0 到 1）

### Phase 1：协议层（1–2 天）
- [ ] 加 KMP target（或翻译 GameMessage 到目标语言）
- [ ] 实现 WS 连接 + 心跳 + 重连
- [ ] PROTOCOL_VERSION 握手通过
- [ ] 协议 roundtrip 金标测试通过

### Phase 2：单机模式（1–2 天）
- [ ] UI 框架最小化跑出 Home / Game 屏
- [ ] `:shared/GameEngine` + `:shared/AIPlayer` 接进来
- [ ] 单机能完整玩 1 局，结算与 `:shared/SettlementCalculator` 对账

### Phase 3：多人核心（2–3 天）
- [ ] Lobby（输服务端 URL）
- [ ] CreateRoom / JoinRoom / Ready / Start
- [ ] 游戏屏渲染服务端推的 state（不是本地推算）
- [ ] PlayCards / Pass + 服务端拒绝时的友好提示

### Phase 4：完成度（1–2 天）
- [ ] 断线重连
- [ ] 提示按钮 / 选中视觉反馈 / 倒计时
- [ ] 结算屏 + 再来一局
- [ ] 移植清单（[feature_spec.md §4](feature_spec.md#4-移植清单新客户端发布前自查)）跑完

### Phase 5：合并 (1 天)
- [ ] 与 Android / Web 混合 1 局通过
- [ ] PR review 4 关（CI 绿 + Codex + Claude /review-pr + 真机）
- [ ] 在 [feature_spec.md §5 现状对照表](feature_spec.md#5-现状对照表截至-2026-05) 加你这一列

---

## 10. 不做的事（明确拒绝）

- ❌ **客户端实现自己的牌型规则**——必须用 `:shared/CardRules`，否则历史 Bug 会重现
- ❌ **客户端持有完整的 6 玩家手牌**——`game.start` 推送时只有自己的手牌是真，其他都是占位 `Card.HIDDEN`
- ❌ **修改 sessionToken 含义**——服务端 `playerToRoom[token]` 是重连唯一锚点
- ❌ **绕过 `mutexFor(room)` 的并发约束**（仅 server 端关心，但客户端不要假设服务端能并发处理同房间动作）
- ❌ **跳过 4-gate PR 流程**——新端的协议影响面比单端大，**必须** Codex + Claude /review-pr + 真机三关都过

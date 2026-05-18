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
| 防回归测试 | 部分覆盖 → `ServerGameManagerTest.identifyCardGroup_acceptsUppercaseSuit_regressions1`（PR-H2 引入；保证服务端正常解析大写花色枚举）。完整 round-trip 序列化测试待 PR-H3 之后用 `:shared` 单元覆盖 |

---

## #2  游戏卡死（4 层防御）

| 字段 | 内容 |
|------|------|
| 症状 | 等待电脑出牌时游戏永不响应，必须主动退出重开 |
| 根因 | 同一现象**四层根因**：(L1) `canBeat` 比较不同张数炸弹时优先级颠倒，AI 选出的 5×3 被拒；(L2) AI 首选失败无回退链，直接返回过牌；(L3) `state.hands` 多协程无锁并发，触发 `ConcurrentModificationException`；(L4) 全部回退失败时无 `force-advance` 兜底，游戏永久卡死 |
| 修复 | 4 个连续 commit：`fb6cc7c`（canBeat） + `8969125`（Mutex + force-advance） + `8a56e14`（playerScores 一致性）|
| 教训 | dev_summary.md 第六章：「**修了又坏的根因是只修表层症状没往深挖根因**。卡死问题历经 4 层才彻底解决。原则：找到最小可复现场景，分层排除，验证到底。」一个 Bug 经常对应多个**结构性**根因 → 修复后必须由独立会话审查「这个根因还能从哪里发生」 |
| 防回归测试 | **L1 已覆盖**（PR-H2）：客户端 `CardRulesTest.canBeat_biggerBombSizeWinsOverRank_regressions2` + 服务端 `ServerGameManagerTest.canBeat_biggerBombSizeWinsOverRank_regressions2` 双端断言 5×3 压 4×10。**L2/L3/L4 仍待补**：AI 三级回退链 / Mutex 并发守恒 / force-advance 推进 → 需要更深的集成测试，列入 PR-H4 的 MockServer e2e 范围 |

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
| 防回归测试 | `SettlementCalculatorTest.kt`（15 用例 · 单机端） + `ServerGameManagerTest.computeAllFinishedScores_allCollectedZero_stillComputesHandScores`（PR-H2，服务端断言 playerScores=0 时仍正确计算手牌分） |

---

## #5  UUID 截断到 8 字符 → 会话碰撞（Codex P1）

| 字段 | 内容 |
|------|------|
| 症状 | 多用户并发时，A 的重连可能被分配到 B 的房间；跨用户状态污染 |
| 根因 | `server/Application.kt`：`UUID.randomUUID().toString().take(8)` 把 36 字符 UUID 截到 8 字符；只剩 32 位熵；`sessions[id]` 与 `playerToRoom[id]` 在中等流量下产生碰撞 |
| 修复 | commit `06d445c`（**Codex Review Bot 在 PR #29 指出**，4 天后修复） |
| 教训 | **常用 ≈ 正确**这条假设是漏洞温床——`take(8)` 在客户端密钥中很常见，但分布式会话 ID 必须保留全部熵。CLAUDE.md 约束 5 即由此沉淀。这条 Bug **Claude 多轮审查没发现，Codex 一次发现**，是 dev_summary.md 8.3「跨 vendor 对抗审查」的最强证据 |
| 防回归测试 | 缺失（不在 PR-H2 范围）→ 待补 `ServerRoomManagerTest`：注入 1k 个 UUID 验证唯一性 + `assert id.length == 36` |

---

## #6  ArrayList 并发修改异常

| 字段 | 内容 |
|------|------|
| 症状 | 服务端随机抛 `ConcurrentModificationException` 或游戏卡死 |
| 根因 | 多协程（玩家 action handler / 30s 超时计时器 / 断线处理器 / AI 任务）同时写 `state.hands: MutableList<Card>`；`handleAction` 与 `processAITurn` 没有同步原语 |
| 修复 | commit `8969125`（per-room `Mutex` + `state.players` 改 `CopyOnWriteArrayList`） |
| 教训 | **协程 + 共享可变状态**模式在低并发"看似没事"，中等并发立刻爆。CLAUDE.md 约束 2 沉淀此教训：任何修改服务端 game state 的代码都**必须**在 `mutexFor(room).withLock { ... }` 内。**广播必须在锁外**，否则慢客户端阻塞房间所有动作 |
| 防回归测试 | 缺失（不在 PR-H2 范围 —— 单元测试覆盖不到并发；需要 stress test）→ 待 PR-H4 用 MockServer 做 3+ 协程并发 action 的张数守恒断言 |

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
| 防回归测试 | `SettlementCalculatorTest.kt`（15 用例 · 客户端） + PR-H2 服务端 `ServerGameManagerTest.computeAllFinishedScores_includesLoserUnfinishedCollected_regressions8`（直接断言"输方未走完玩家已收分"必须计入赢方） |

---

## 教训综合（来自 dev_summary.md，与本表对应）

> "修了又坏"的根因：**只修表层症状，没往深挖根因**。卡死问题历经 4 层才彻底解决。
> 原则：找到最小可复现场景，分层排除，验证到底。

> AI 辅助的正确分工：AI 全量扫描 → 人工真机验证 → AI 生成修复。
> 最优工作流是**多 AI 串行 + 人工最后把关**。

> 多 vendor 交叉审查比单一审查更可靠。Claude（全局逻辑链路）+ Codex（细粒度风险点）几乎不重叠。
> 本项目 UUID 截断（#5）、Loading 卡死（#7）两个 P1 仅被 Codex 或人工发现。

---

## #10  Web 客户端中文豆腐块（被 PR #41 loader bug 掩盖）

| 字段 | 内容 |
|------|------|
| 症状 | 浏览器打开 Web 客户端，所有中文字符（"沟通牌"、"创建房间" 等）显示为白色矩形 □□□。tab 标题里的中文正常 —— 仅 Compose 渲染部分异常 |
| 根因 | CMP wasmJs 用 Skia 在 `<canvas>` 内画文本。Skia 在浏览器 wasm 沙箱里**拿不到 OS 字体**（沙箱安全），CMP 默认打包字体只覆盖 Latin → 所有中文 codepoint 找不到字形 → 画 .notdef glyph（豆腐块）。tab 标题正常是因为它走 HTML/DOM，由浏览器系统字体（macOS 苹方等）渲染，是另一条路径 |
| 修复 | PR #45（计划）—— Noto Sans CJK SC GB2312 子集（7540 字，~3 MB）打进 wasmJs 资源；`Fonts.kt` 用 `@JsFun` fetch + base64 + `androidx.compose.ui.text.platform.Font(identity, data)` 注册成 `FontFamily`；`App.kt` 顶层 `MaterialTheme(typography=…)` 把所有 Material3 textStyle 的 fontFamily 设为该 family。`apps/web/fonts/build-subset.sh` 提供 `gb2312` / `project` 两种 mode 的子集生成脚本 |
| 教训 | (1) **真机验证关 = 真的把 UI 跑给人看**：bug 在 PR #41 引入 Web 客户端就存在，但被 "loader 卡住" 这个上层 P0 完全掩盖；连续 3 个 PR（#41/#42/#44）的 4 关流程里"真机验证"都没真渲染过 Home/Lobby —— 直到部署完 + 字体 bug 暴露才发现。下次任何 UI PR 的真机关都必须**至少进 1 个非 Home 屏幕看实际渲染**，不只是确认页面打开。(2) **Skia in browser ≠ Skia native**：以为"Compose 跨平台"就以为字体在所有 target 都自动可用是错的；wasmJs target 必须显式打包字体。(3) **bug 数据库的"被掩盖" pattern**：上层 bug 修了之后要主动复查"这一层之下还有没有同时存在但被掩盖的 bug" |
| 防回归测试 | UI 文本渲染没有单元测试惯例；防回归靠 `apps/web/README.md` 的"中文字体"段 + `apps/web/fonts/build-subset.sh` 的可复现性。下次 build wasmJs 产物时只要 `resources/fonts/NotoSansSC-Subset.ttf` 在，渲染就正常；缺失 → `Fonts.kt` 静默 fallback（豆腐 + console.error），手动可观察到 |

---

## #9  公网部署 timeout（双层防火墙踩坑 ×2）

| 字段 | 内容 |
|------|------|
| 症状 | install.sh 跑完、systemd 服务起来、Caddy 在 80 上 listen，从公网 curl `http://<ip>/` 仍 connect timeout；浏览器一直转圈 |
| 根因 | 服务器外有**两层独立防火墙**：(L1) 云厂商安全组（腾讯云控制台层级、网络边界），(L2) 服务器内 ufw（host 层）。两层互不知道对方存在；任一层没放行 80/443 就 timeout，但表现完全一样。**第一次踩坑**漏配腾讯云安全组（install.sh 提示但易忽略）；**第二次踩坑**腾讯云安全组配了，但服务器 ufw 之前为开发自配过 `8080/tcp` 没动 80/443，install.sh 旧版没自动管 ufw |
| 修复 | PR #44（计划）—— install.sh 自动配 ufw（放 22/80/443，删 8080）；playbook §3 拆成 3a/3b/3c：ufw / 云安全组 / 自检 3 步定位决策树；install.sh 末尾输出把"配腾讯云安全组"提到第 1 步并标【必做】+ 给本机 curl 验证命令 |
| 教训 | **基础设施 bug 的复发模式**：用户跟着文档走，文档只覆盖一层，另一层就成了隐形踩坑点。**只配两层之一时表现 100% 相同**（公网 timeout），无法从症状区分 → 必须给"分层自检 3 步"才能让用户精确定位是哪一层。`install.sh` 的角色定位调整：能在 host 层自动化的（ufw）**自动配，不再依赖用户记**；不能自动化的（云安全组在控制台外）就**在脚本输出里【必做】粗体大字提醒** |
| 防回归测试 | 部署 / 防火墙脚本无单元测试惯例；防回归靠 playbook §3c 的"分层自检 3 步" + install.sh 末尾输出的决策树。下次遇到类似 timeout 必须**先按 §3c 自检**，0 歧义指出哪一层挂了，杜绝"重启服务器 / 重装 caddy / 疑神疑鬼" 的乱试 |

---

## #13  Android 客户端硬编码 :8080 → PR #41 拓扑改造后联网失败

| 字段 | 内容 |
|------|------|
| 症状 | 用户拉最新 main 后，Android 客户端连接服务器超时；Web 客户端正常。腾讯云服务器侧 systemd `:server` 服务跑着、Caddy 跑着、80 端口可达。 |
| 根因 | PR #41 把服务器拓扑改成「Caddy 80/443 反代 → 127.0.0.1:8080」，**8080 不再对外暴露**（Tencent 安全组只开 22/80/443）。同 PR 把 Web 客户端改成同源相对路径 `/game`（走 80），但 **Android 端 `MainActivity.kt:43` 和 `LobbyActivity.kt:34` 的 `SERVER_URL` 仍是 `ws://175.178.158.35:8080/game`**——硬编码端口绕开 Caddy，直接打外网不可达的 8080，必然 timeout。 |
| 修复 | 本 PR：把两处 `SERVER_URL` 从 `ws://175.178.158.35:8080/game` 改为 `ws://175.178.158.35/game`（去 :8080，走默认 80 经 Caddy 反代）。两个 `SERVER_URL` 上各自加注释指明 PR #41 拓扑契约。 |
| 教训 | **大改部署拓扑必须扫一遍所有客户端**——不光 Web；移动端、桌面端、未来的 iOS 都要查一遍 URL 硬编码。PR #41 的 PR 描述只提了 Web 客户端的 URL 变化，没提到 Android 同样需要改。**结构性根因**：服务器地址是配置项不是常量，不应该 `private const val` 硬编码两份；应该集中到一个地方（资源文件 / `BuildConfig` / 远程配置）。修这次的 bug 是治标，治本要后续 PR 把 Android 的 URL 配置抽出来。 |
| 防回归测试 | 缺失（URL 是字符串常量，不易写单元测试）。流程层面：(1) `docs/playbooks/feature-development.md` Loop A Step 2 加一条「改部署拓扑前先 grep 所有 client 的 URL 硬编码」；(2) PR 模板「服务端状态修改？」段落补一条「拓扑变更时 Android + Web + 任何未来端的 SERVER_URL 是否同步更新」复选框（PR-H6 文档 follow-up） |

---

## #11  AI 接管延迟期内的 substitute 状态过期（Codex P2）

| 字段 | 内容 |
|------|------|
| 症状 | 多人模式下玩家点"AI 接管"开启托管后，**在 effectiveAiDelayMs（最长 1000ms）期内点"我回来了"取消托管**，AI 仍然代打了一手，而后才让回控制权。玩家肉眼可见自己被偷了一手。 |
| 根因 | `ServerGameManager.processAITurn` 在 `delay(effectiveAiDelayMs(...))` 之后只在 `mutexFor(room).withLock {}` 内重检了 `currentPlayerIndex`，**没有重检 `isAISubstitute / isConnected`**。延迟期间玩家虽然把 isAISubstitute 翻成 false，回合也没推进，所以 AI 决策路径仍然完整执行。这是 feature_spec G34/G35 "即时收回控制权"语义的隐藏 race。 |
| 修复 | PR #53 commit `c9988fd`：抽取 `internal fun shouldYieldToHumanPlayer(player): Boolean` = `!isAI && !isAISubstitute && isConnected`；`processAITurn` 的 mutex 块内、`decideAIAction` 之前调用，true 则 `return@withLock` 不出牌。 |
| 教训 | **凡是涉及 `delay()` 的状态机，醒来后必须重检"延迟前所有依赖项"**，不只检查最显眼的那个。这条规律普适：网络重连 / WebSocket 心跳 / 任何"sleep then act"模式都该问一句"我醒来时这些前提还成立吗"。Phase 3 commit 时只重检 currentPlayerIndex 是凭直觉，没系统化清单——Codex 替补了清单审查这一关。 |
| 防回归测试 | `ServerGameManagerTest.kt`：4 个 `shouldYieldToHumanPlayer_*` 用例覆盖（纯 AI 不让位 / substitute 不让位 / disconnected 不让位 / 真人 resume 必让位）。同 commit 加测，tdd-gate 强制通过。 |

---

## #12  wasmJs psi2ir NPE：可空 lambda + Compose smart-cast 触发后端崩溃

| 字段 | 内容 |
|------|------|
| 症状 | 加 feature G34（AI 接管按钮）到 Web 端时，`./gradlew :apps:web:wasmJsBrowserDistribution` 在 CI 失败，`compileKotlinWasmJs` 报 `org.jetbrains.kotlin.backend.common.BackendException: Backend Internal error: Exception during psi2ir` + `Caused by: java.lang.NullPointerException`。本地 jvmTest 全过；纯 wasmJs 后端的内部错误。 |
| 根因 | 两处代码组合触发 wasmJs 编译器内部 bug：(1) GameScreen.kt 里 `if (imAiSubstitute != null && onToggleAITakeover != null) { OutlinedButton(onClick = onToggleAITakeover, ...) }`——把 smart-cast 后的可空 lambda 直接传给 `@Composable` 期待的 `() -> Unit`；(2) App.kt 里 `onToggleAITakeover = if (...) vm::toggleAITakeover else null`——`KFunction0<Unit>` 自动转 `(() -> Unit)?`。两个模式都是合法 Kotlin，jvmTarget 没事，但 wasmJs 后端 psi2ir 阶段对这两种"可空 + 函数引用"的组合存在已知 NPE。 |
| 修复 | PR #53 commit `8171283`：(1) GameScreen.kt 用 local `val` 固化非空再用，让 smart-cast 发生在 immutable 局部变量上（编译器最稳定的形式）；(2) App.kt 把 `vm::toggleAITakeover` 改成显式 `{ vm.toggleAITakeover() }` lambda。语义不变，纯编译期形态调整。 |
| 教训 | wasmJs target 还在快速演进，**不能假设"jvmTest 过 = wasmJs 过"**。任何涉及 Compose @Composable + 可空 lambda + smart-cast 组合的代码都要在 CI 上跑一次 `wasmJsBrowserDistribution` 才算数。沙箱里 AGP / wasmJs 编译器拉不到的环境下，**写完 Web UI 必须 push 跑 CI 才能收到反馈**——这是 Web 比 Android 更脆的一点。防御性编码 pattern：可空 lambda 用 local val，函数引用宁可写 lambda。 |
| 防回归测试 | 缺失（编译器 bug 不易写单测；只能等 Kotlin 升级修复或在 codestyle / detekt 加规则）。流程层面：(1) `docs/playbooks/ci-failure-triage.md` 已加 wasmJs psi2ir 排查项；(2) 写 Web UI 的代码评审 checklist 加一条"可空 lambda 是否用了 local val"。 |

---

## #14  Chrome 本地状态损坏 → 偶发 ERR_CONNECTION_REFUSED（非代码 bug，留档诊断流程）

| 字段 | 内容 |
|------|------|
| 症状 | 用户从手机 Chrome 访问 `http://<server-ip>/` 偶发 ERR_CONNECTION_REFUSED；同设备同 WiFi 下华为浏览器、iPhone Chrome、桌面 Chrome 全部正常；同一 Chrome 实例"傍晚 OK / 现在不行"，时间相关。`x-deny-reason: host_not_allowed` 在 sandbox 探测时是沙箱代理拦截，不是真实服务器；服务器 :80 / Caddy / Ktor / ufw / 安全组全部正常 |
| 根因 | **Chrome 本地缓存的网络栈状态损坏** —— 具体子项不能从外部唯一确定，候选：(1) HSTS 缓存把目标 IP 标成 HTTPS-only，HTTPS-First Mode 关掉也照样升级；(2) HTTP/3 alt-svc 缓存指向不存在的 QUIC endpoint，fallback 异常；(3) Chrome socket pool 拿到一个坏 entry；(4) Safe Browsing 黑名单推送误命中。这类故障的共同特征：**同一 Chrome 实例**+**特定时间**重现，**同设备其他浏览器**或**其他设备 Chrome**全部正常 |
| 修复 | 用户侧操作：(a) 验证假设——隐身模式访问，能上即确证是本地 state 损坏；(b) 一次性清理：`chrome://net-internals/#hsts` 删 IP 条目 + Flush socket pools + Chrome 设置清"缓存的图片和文件" + "Cookie 和其他站点数据" + 重启 Chrome。**服务端无需任何改动**。代码层面附带做了 PR #59：`apps/web/.../net/WebSocketTransport.kt` 加指数退避自动重连（防御 4G/WiFi 切换、移动 NAT 超时类的偶发 close，**不**对 Chrome 本地状态损坏起作用——每次 retry 同样 refused，只是 5 次后停下不耗电）|
| 教训 | (1) **症状先验证维度，再定根因**：用户首报"手机 Chrome 不行"时，第一反应跳到"运营商 80 端口劫持"假设——但只要让用户跑"同设备其他浏览器" + "桌面同时间访问" + "出问题 Chrome 隐身模式" 三个对照，就能在 5 分钟内排除"服务器 / 网络 / Chrome 通用 bug" 三类，剩下唯一可能就是"该 Chrome 实例本地状态"。**先收维度，再下结论**。(2) **"傍晚 OK / 现在不行"≠ 不可复现**：时间相关 + 缓存机制刚好能解释；不要因为"偶发"就认定查不到根因。(3) **未上 HTTPS 是隐性技术债**：纯 IP HTTP 是各种现代浏览器实验组的"反向白名单"——HTTPS-First Mode、HSTS、QUIC、Safe Browsing 全都对 HTTP-IP 站点有更严格 / 更不一致的行为。一旦后续频次升高，应当升级方案 B（域名 + Let's Encrypt）|
| 防回归测试 | 非代码 bug，无单测可写。流程层防御：(a) `WebSocketTransport.kt` 内置自动重连，把"偶发 refused / 中途断"的影响降低；(b) 本条目本身——下次类似报告（用户描述 ERR_CONNECTION_REFUSED + 同时间其他设备/浏览器正常 + 时间相关），按本条目的"症状-根因-修复"对照即可 5 分钟收尾，不再绕"服务器 → 网络 → Caddy → 备案"一圈 |

---

## #15  Web 连接服务器需"连点 3 次"才生效

| 字段 | 内容 |
|------|------|
| 症状 | Lobby 屏第一次点"连接服务器"后看似无反应，需要再点 2-3 次才看到 Connected 状态 |
| 根因 | `AppViewModel.connectServer()` 顶部无条件 `net?.close()` + `newSessionScope()`：用户在 UI 重组前（~16 ms 一帧）连点时，每次点击都把刚发起的 WS 撕掉新建，前面的连接尚未完成 onOpen 就被销毁，需要等用户停手后那次才生效 |
| 修复 | commit `9a10adc`（PR #62）— `connectServer()` 开头判断 `net?.connectionState?.value`：在 `Connecting` / `Connected` 直接 return，让按钮的 click 是幂等的 |
| 教训 | "破坏式重建"型 UI 入口必须自带幂等保护，仅靠按钮 visibility 控制不可靠——Compose 重组与 click event 不在同一帧。同类入口（goHome / startMultiplayer 等）也要审视 |
| 防回归测试 | 暂无 unit test（涉及 JS WebSocket + Compose 重组时序）；行为靠手动验证：开 Lobby 屏快速连点 5 次"连接服务器" → 仍只发起一条连接，最终 Connected |

---

## #16  Admin 登录 HTTP 500（Ktor cookie 编码 IAE）

| 字段 | 内容 |
|------|------|
| 症状 | Admin 控制台 `POST /admin-auth/login` 返回 HTTP 500；服务端日志打出 `[handleLogin] post-auth step threw java.lang.IllegalArgumentException`，堆栈指向 Ktor `ResponseCookies.kt:31` 的 `append` 方法 |
| 根因 | Ktor 2.3.6 的 `CookieEncoding.RAW` 模式对 cookie 值做严格字符集校验；base64url token 的 `-`（hyphen）/ `_`（underscore）在某些环境/版本下触发 IAE。切换到 `CookieEncoding.URI_ENCODING` 后单元测试通过（50 次 round-trip 无异常），但线上 `ResponseCookies.append` 仍抛 IAE——可能是 URI_ENCODING 在当前 runtime 版本也有字符白名单限制，或与 GZIP / content-type 协商有关，根因尚未完全确定 |
| 修复 | PRs #68 / #69 / #70；commits `b34808f`（bypass）、`c5ba575`（hybrid bypass + Ignore 测试）、`ec5ccff`（补 Ignore AdminApiRoutes no-cookie 401 test）。**重要：auth bypass 不能修复 `POST /admin-auth/login` 的 HTTP 500**——`handleLogin` 在调用 `call.response.cookies.append()` 时崩溃，该路由从不调用 `requireAdmin`，bypass 对 login 路由无效，login 仍返回 500。Bypass 的实际效果是：`/admin/api/*` / `/admin-auth/me` 等调用了 `requireAdmin` 的端点绕过 cookie 校验并直接获得 SUPER_ADMIN 身份，等同于对这些端点**暂时关闭鉴权**。**Bypass 为高风险临时措施——IAE 根因修复、login 端点稳定返回 200 后，必须删除 `AdminAuthPlugin.kt` 的 `TEMPORARY AUTH BYPASS` 块，并 re-enable 3 个 `@kotlin.test.Ignore` 测试** |
| 教训 | (1) Ktor cookie encoding 行为在小版本间可以破坏性变更；base64url token 虽"URL 安全"，仍需实测不同 `CookieEncoding` 模式的实际行为。(2) 线上 IAE 必须靠**完整生产堆栈**（哪一行、什么值触发）定位，不能只靠 unit test 推断。(3) Auth bypass 是极高风险的临时措施；加入之前**必须先记录恢复条件**（本条目即为记录），并在所有相关测试上加 `@kotlin.test.Ignore` 注释说明原因 |
| 防回归测试 | `AdminAuthRoutesTest.session cookie URI_ENCODING never throws for realistic base64url tokens`（50 次 round-trip）+ `AdminAuthRoutesTest.login Set-Cookie token is valid base64url and authenticates subsequent requests`（登录 200 + token 格式正确 + /me 可用）。暂时 `@Ignore` 的 3 个测试（待 bypass 移除后恢复）：`GET me without cookie returns 401` / `logout invalidates the cookie` / `GET overview without cookie returns 401` |

---

## #17  Admin DB SQLITE_READONLY（目录或文件权限递归失效）

| 字段 | 内容 |
|------|------|
| 症状 | 部署后 admin 登录失败或 admin API 写入报 HTTP 500；服务日志出现 `SQLITE_READONLY` 错误；重启服务无效，改服务配置无效 |
| 根因 | `/var/lib/communication-card/` 目录或其中的 `admin.db` 文件归属 `root`（或其他非 `cards` 用户），`cards` 系统用户无写权限。常见场景：(a) `install.sh` 首次由 root 运行，SQLite 自动创建的 `admin.db` 被 root 持有；(b) 手动备份/恢复后 `chown` 未带 `-R` 覆盖文件；(c) 目录本身可写但**文件**是 root 的——SQLite 写入需要**文件写权限**，目录可写仅能创建新文件，不能覆盖/修改 root 文件 |
| 修复 | PR #70 / commit `c5ba575`：`deploy.yml` 的 `Verify directories are writable on server` step 增加独立的 `admin.db` 文件可写检查（目录 + 文件两层，不可写则打印 `chown` 修复命令并 `exit 1` 阻断部署），在服务重启前发现而非运行时才暴露 |
| 教训 | (1) **目录可写 ≠ 文件可写**：SQLite 需要两层权限（目录：创建 WAL/SHM 临时文件；文件：写 DB 本体），必须独立检查。(2) **反复踩坑的问题必须移入自动化 pre-flight**，不能只写进运维文档——文档有但没人看，脚本不运行就阻断。(3) 人工 `chown -R` 一步解决，但往往需要"先知道是 permission 问题"；pre-flight 把这条错误路径的 MTTR（平均修复时间）从"翻日志 + 猜" 压缩到"看报错 + 一条命令" |
| 防回归测试 | `deploy.yml` `Verify directories are writable on server` step（两层检查：`$LIB_DIR` 目录可写 + `$LIB_DIR/admin.db` 文件可写或不存在）。无 unit test（shell 权限检查不适合单测）；防回归靠 deploy 流程必经此 step |

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

# Playbook · Loop B — Bug 修复（"修了又坏"防火墙）

> dev_summary.md 第六章「游戏卡死 4 层防御」是这个 playbook 的母本：
> 一次卡死被 4 个不同的根因同时支撑，前 3 次"修复"都只是表层。本流程
> 的目标是**结构性**地阻止这种重复。

参考：`docs/regressions.md` 全文 + CLAUDE.md 第三章「关键路径强制
TDD」 + dev_summary.md 8.2「迭代成本」。

---

## 触发条件

任意一条满足：

- 用户/真机看到错误 / 崩溃 / 卡死
- CI 失败但不是配置/工具链问题
- 日志中出现 `force-advance` / `ConcurrentModificationException` / 类似异常

如果是**同一现象的第二次出现**——立刻按本流程办，**不要**直接修。
重复 = 之前修了表层。

---

## 1. 把现象固化成失败测试（**最关键的一步**）

切到 Opus 4.7（**新会话**——避免上一会话的偏见）。

输入给 Opus 的素材：

- 用户描述（症状、复现步骤、截图）
- 相关日志（最关键 20-50 行）
- 同分类的历史 Bug 链接（`docs/regressions.md` 中的 # 编号）

要求 Opus 输出：

1. **最小可复现测试用例**（要 Kotlin 代码，不要伪代码）
2. 这个测试应该放在哪个 `*Test.kt`
3. 用例失败的预期错误信息

例：「用户说全队走完后赢方少 60 分，输方有人没走完」→ 输出
`SettlementCalculatorTest.testWinningTeamLosingPlayerCollectedScoreCounted`
含具体牌局 + 期望分数。

---

## 2. 提交红色测试 + Push

不要立刻调实现。先：

```bash
git checkout -b fix/<short-bug-description>
# 把测试粘贴进对应 *Test.kt
git add <test_file>
git commit -m "test(bug): red for <symptom>

复现 <用户报告 / 日志摘要>。下一 commit 修复。

Signed-off-by: ...
AI-Assisted-By: Claude Opus 4.7 (claude-opus-4-7)
"
git push -u origin fix/<short-bug-description>
```

**等 CI 红**——这是最重要的一次"红"，证明：

- 测试真的复现了 Bug（不是写错了断言）
- 跑测试的环境/工具链没问题
- 后续的"绿"才有意义

CI 没红 → 测试没复现到 Bug → 让 Opus 改测试再来。

---

## 3. 反推根因（依然 Opus 4.7）

把"红色测试 + 失败信息"喂给 Opus，要求它列**至少 2 层假设**：

- **症状层**：直接导致这条断言失败的最近代码
- **结构层**：这条代码的前置依赖 / 设计决策 / 共享状态

例（卡死 Bug）：

| 层 | 假设 |
|----|------|
| L1 | `canBeat(5×3, 4×10)` 返回 false |
| L2 | `getRankValue(BOMB)` 没有 size 维度 |
| L3 | AI 选 5×3 是因为它没有 fallback；选错也不知道选错 |
| L4 | 多协程同时改 `state.hands` 触发 CME |

只修 L1 就上线 = 修了又坏的开始。**至少修到 L2**，**最好同时审查 L3 / L4**。

### 常见反模式：`delay()` 后状态过期

任何"先 sleep / delay 再做事"的代码块都要审查"延迟期间状态变化的影响"。
最近一例（[`docs/regressions.md` #11](../regressions.md#11-ai-接管延迟期内的-substitute-状态过期codex-p2)）：

```kotlin
delay(effectiveAiDelayMs(...))     // 玩家在这一秒里把 isAISubstitute 翻 false 了
mutexFor(room).withLock {
    if (state.currentPlayerIndex != playerIndex) return  // 只重检了 currentPlayerIndex
    decideAIAction(...)             // 然而 isAISubstitute 已变 → AI 不该再代打
}
```

**审查清单**（任何 `delay()` 块醒来后必须重检的事）：
- 玩家 / session 状态（`isAISubstitute` / `isConnected` / `isAI`）
- 房间状态（`status != IN_GAME` 时早返回）
- 当前玩家索引（最显眼的，但**不止这一个**）
- 业务相关的"前提条件"——延迟前真，醒来后未必真

**修法**：抽取 `internal fun shouldYieldToHumanPlayer(...)` 之类的谓词，
把"延迟期可能变化的所有状态"打包检查；processAITurn 锁内调用；同 commit
加单测覆盖每一种状态过期路径。

---

## 4. 实施第一层修复（Sonnet 4.6）

切到 Sonnet。改最少代码让红色测试转绿。`/test-fast`。

**不要急着 commit + push**——还有第 5 步。

---

## 5. 同根因对抗审查（**容易跳过的关键步骤**）

**开新 Opus 会话**（不是切模型，是 `/clear` 或新窗口），把：

- 红色测试 + 修复后的 diff
- `docs/regressions.md` 的相关条目

喂给新 Opus 会话，问：

> "这个根因还能从哪个文件 / 哪个调用链发生？同一类问题在 server / web /
> Android 三端是否各有一处？修复是否漏了 server 端的对应逻辑？"

它会列 0-3 条额外排查点。逐一**确认或排除**。

dev_summary.md 8.2 数据：单次修复成功率约 12%，**4 关同时绿往往是
第 2-3 轮**才达到。本步骤的目的就是把第 2 轮的内容并入第 1 轮。

---

## 6. 写入 `docs/regressions.md`

新增条目，编号递增。8 字段表格 + 教训段落（看现有条目格式）。

如果是历史 Bug 的**重现**（说明上次修不彻底）——不要新建条目，
追加 `### 复发记录` 子段到原条目下，写：

- 复发时间
- 复发症状（与原症状相同？还是变体？）
- 这次的根因（与上次的差异）
- 这次的修复 commit

复发本身比"新 Bug"更重要，因为它说明上次的"教训"没起作用——把它
写大写明显。

---

## 7. Commit + Push 修复

```bash
git add -A
git commit -m "fix(<scope>): <bug summary>

修复 docs/regressions.md #<N>。

之前漏了 <结构层根因>；本次同时修了 <额外排查点>。
红色测试见上一 commit。

Signed-off-by: ...
AI-Assisted-By: Claude Opus 4.7 (claude-opus-4-7)
AI-Assisted-By: Claude Sonnet 4.6 (claude-sonnet-4-6)
"
```

`/ship-check` → push → 走 PR 4 关。

---

## 8. PR 描述模板（Bug 修复专用）

```markdown
## 症状
<复现步骤 + 截图/日志>

## 根因（多层）
- L1: <症状层>
- L2: <结构层>
- L3: <如有>

## 修复
- <文件>: <改了什么>

## 防回归测试
- <Test class>::<test name>（在 commit "test(bug): red for ..." 中先红，本 PR 转绿）

## 历史关联
- 类似 Bug：docs/regressions.md #<N>
- 是否复发：是 / 否
```

---

## 反模式（参考 dev_summary.md 8.2）

- **直接看代码改**（不写测试）：50% 概率"修了又坏"
- **修完不开新会话审查**：原会话的"心理偏差"会让你看不见同根因的旁路
- **只修客户端不看服务端**（或反之）：约束 1/4 的漂移源头
- **修了不写 `regressions.md`**：下次进来又踩，时间归零
- **CI 红就强 push**（`EMERGENCY_PUSH=1`）：除非生产事故，否则永远不要

---

## 何时退出本流程，回到 Loop A

修复 PR 合入后，如果**真的**没有遗留风险（确认了 L2/L3，写入 regressions），
回到 Loop A 继续原本的功能开发。如果心里有"这事好像没完"的感觉——
**不要回**，继续在 Loop B 多审查一轮。

# Playbook · 跨 vendor 对抗审查节奏

> dev_summary.md 第六章 + 8.3 的核心结论：**多 Claude 协同有结构性上限**。
> Claude 共享同一训练语料 / 同一目标 / 同一架构 → 相关性盲区会同时存在
> 于所有 Claude 模型。真正补盲区的是「换 vendor + 加静态工具 + 人工真机」。
>
> 本 playbook 把这个原则钉进流程，避免「全靠记忆 / 全靠自律」。

参考：CLAUDE.md 第一章模型选择 + 第五章 PR 4 关 + docs/regressions.md
（每条 Bug 标注「谁发现的」）。

---

## 0. 数据：本项目 Codex 的真实命中

`docs/regressions.md` 中标注「Codex 发现」的条目：

| Bug | 严重度 | Claude 多轮审查 | Codex 一次发现 |
|-----|--------|----------------|----------------|
| #5 UUID 截断到 8 字符（PR #29） | P1 | 漏 | ✓ |
| #7 Loading 遮罩永久卡住（PR #33） | P1 | 漏 | ✓ |
| PR #39 stage 3 AI 阈值偏移 | P2（行为退化） | 漏 | ✓ |

3/8 的关键 Bug 是 Claude 全员漏看、Codex 一次发现。如果不上 Codex，
这 3 条要么靠用户在真机踩到，要么永远不被发现。

**Codex 是必需的，不是 nice-to-have。**

---

## 1. 自动触发：每个 PR 都跑 Codex

`chatgpt-codex-connector[bot]` 已经接好 GitHub PR webhook：每次 PR
创建或新提交 push，Codex 自动跑一次 review。无需主动触发。

通过观察：
- `mcp__github__pull_request_read method=get_reviews` → 看到 Codex 的
  `state: COMMENTED` review 即代表跑过
- `method=get_review_comments` → 看到具体行级建议

**当 Codex 留 P0 / P1 评论时**：
- 读 → 确认是真问题（不是误报）→ 修 → push 修复 commit
- 流程见 docs/playbooks/bug-triage.md（Loop B）

**当 Codex 留 P2 评论时**：
- 读 → 评估是否影响行为
- 行为退化（如 PR #39 的 AI 阈值偏移）→ 当 P1 处理
- 仅风格 / 命名建议 → 可酌情忽略，但要在评论里 react 👍/👎 以训练

---

## 1.5 Claude 会话监听 PR 事件（remote execution 模式）

Claude Code 跑在云端（web / GitHub Action / 远程容器）时，session 无法
阻塞轮询；应当使用 `subscribe_pr_activity` 接收事件驱动通知。

### 触发时机

每次 `git push` 后，`PostToolUse.sh` hook 会注入提醒。主会话应当：

```
1. mcp__github__list_pull_requests        → 找到当前分支对应 PR number
2. mcp__github__subscribe_pr_activity
       owner=berming repo=AndroidAPP pullNumber=<PR#>
   （订阅后 harness 将 PR 事件以 <github-webhook-activity> tag 注入 session；
    不要 sleep / 轮询，等 tag 到达即可）
3. 事件处理完毕后 mcp__github__unsubscribe_pr_activity
```

> `mcp__github__subscribe_pr_activity` 是 Claude Code GitHub MCP server 的真实工具，
> 已在本仓库会话中验证可用（PR #65，2026-05）。

### 事件类型处理矩阵

结论分组（GitHub check_run conclusion 枚举）：
- **绿色**：`success` / `skipped` / `neutral`（路径过滤未触发的 job 记为绿色）
- **红色**：`failure` / `cancelled` / `timed_out` / `action_required`

| 事件类型 | 动作 |
|---------|------|
| `check_run` → `completed: *` | 调用 `get_check_runs` 查该 PR head SHA 的**所有** job；全部结论为绿色 → 汇报全绿 → unsubscribe；任一红色 → 拉 PR comment 中 exfil 的 gradle 日志 → 定位 → 修 → push；仍有 `in_progress` → 继续等待 |
| `pull_request_review` / `review_comment` | 分析严重度；P0/P1 → 修 + 用 `add_reply_to_pull_request_comment` 回复 thread；P2 → 评估后决定 |
| 其他（label / assign 等） | 忽略，继续等待 |

### 多 PR 并行

一次订阅对应一个 PR（`pullNumber` 参数）。同时跟踪多个 PR 时，对每个
PR 各调一次 `subscribe_pr_activity`；完成后各自 unsubscribe。

### GitHub 写操作预授权（settings.json 决策记录）

`.claude/settings.json` 的 `permissions.allow` 中已预授权以下三条工具，
主会话可**无需用户逐次确认**即调用：

| 工具 | 用途 |
|------|------|
| `mcp__github__add_issue_comment` | 发布 review 摘要评论 |
| `mcp__github__add_reply_to_pull_request_comment` | 回复 review thread |
| `mcp__github__resolve_review_thread` | 标记已修复的 thread |

**决策理由**（2026-05，PR #65）：push 后的自动修复 + 回复 thread 是
CLAUDE.md 第五章「常驻行为约定」的核心流程，每次写操作都需人工点击
会打断自动化节奏。预授权范围严格限定在上述三条；合并、关闭 PR、修改
PR 描述等破坏性写操作**未**预授权，仍需人工确认。

### 本地环境 fallback

本地 session 若不支持事件推送，退化为轮询：等 60 秒后用
`mcp__github__pull_request_read` 拉 `get_reviews` / `get_review_comments`
/ `get_check_runs`，逻辑与事件驱动模式相同。

> `mcp__github__subscribe_pr_activity` 及其他 GitHub MCP 工具已在
> `.claude/settings.json` 的 `permissions.allow` 中预授权，调用时不需要
> 用户手动审批。

---

## 2. 半自动触发：关键路径必走 `/review-pr <PR#>`

CLAUDE.md 第五章 PR 4 关的第 3 关：「Claude PR review（确认无 P0/P1）」。

### 为什么需要 context isolation

提交 PR 的会话已经形成「我的方案没问题」的心理偏差。要 Claude 公正
审查，审查者必须看不到作者的辩护、看不到作者的叙述、只看 PR 的原始
diff。

### 实现：`/review-pr` 调 pr-reviewer subagent（PR-H5 引入，2026-05）

> 历史上这一关要求"开新会话跑 `/review`"。问题：会话切换成本高、
> 上下文 / 滚动记录丢失、审查报告分散在多个会话里。
>
> 替换方案：`/review-pr <PR#>` slash command 调 `pr-reviewer` subagent
> （`.claude/agents/pr-reviewer.md`）。subagent 满足"context
> isolation"的核心要素：
>
> - 独立 context window，看不到主会话历史
> - **system prompt 锁在 agent 文件里**，主会话不能临时改写
> - 强制从 `mcp__github__pull_request_read` 拉 diff，
>   不接受主会话叙述
> - 模型默认 Opus 4.7（与作者同等容量，最大化抓 P0/P1 概率）
>
> 这就把"开新会话"的真正价值（独立上下文）保留下来，又免了开会话
> 的体力开销。**Codex（异 vendor）和真机仍然必走** —— 见 §1、§4。

### 何时强制走

**关键路径**改动时。判定标准（与 CI tdd-gate 一致）：

- `shared/.../engine/CardRules.kt`
- `shared/.../engine/SettlementCalculator.kt`
- `shared/.../network/GameMessage.kt`（含 PROTOCOL_VERSION 升降）
- `server/.../ServerGameManager.kt`
- `server/.../Application.kt`（handleReconnect / handleAction 等流量入口）

PR 模板里要有「关键路径改动 → 已跑 `/review-pr`」复选框。没勾 →
reviewer 拒绝合入。

### 操作

```
/review-pr <PR#>
```

主会话把这一行交给 pr-reviewer subagent；subagent 跑完返回结构化的
P0/P1/P2 列表。主会话**只展示报告**，不替你拍板修不修。

输出处理：
- P0 / P1 → 修；走 `docs/playbooks/bug-triage.md` Loop B
- P2 → 自行判断；不修也要在 PR 评论里留一句理由
- nit → 顺手修或忽略

### 已知限制

`/review-pr` 不能完全替代「开新会话 + 真机验证」的所有价值：

| 替代了 | 没替代 |
|---|---|
| 独立 context（看不到作者叙述） | 跨 vendor 能力（仍同是 Claude 家族） |
| 锁定的 reviewer rubric（不可被作者诱导） | 真机/真服务端验证（subagent 跑不了 wasmJs / 服务器 curl） |
| 每 PR 5 分钟内完成 | 「直觉式」整体感（subagent 走 rubric，可能漏 rubric 之外的问题） |

所以：
- **Codex bot 评论必读**（§1）—— 异 vendor 才能补 Claude 家族盲区
- **真机/真服务器验证必跑**（§4）—— rubric 之外的运行时问题
- 季度第二 vendor 深审（§3）—— 仍按季度走

### Codex 与 /review-pr 的实际盲区互补（PR #53 实证）

PR #53 引入 `feature_spec G34-G38`（5 特性，~700 行）。两个 reviewer 都
跑了，结果：

| Reviewer | 找到的问题 | 漏掉的问题 |
|---|---|---|
| **Claude `/review-pr`**（Opus 4.7 subagent） | P1 #1 docs/game_rules.md 误写"随机起手"、引用了不存在的 `randomFirstPlayer` 函数（**功能性 / 跨文件契约**） | P2 `processAITurn` 在 `delay()` 后没重检 `isAISubstitute` 的 race（语句级边界） |
| **Codex bot** | P2-A 上面那条 race；P2-B `btnAiTakeover` 单机未接（已修，时间戳错位） | P1 #1（功能性 / 文档与代码的语义对齐）—— Codex 偏向句法 / 边界，对"文档自称权威 vs 实际实现"的语义不一致不敏感 |

**核心洞察**：两个 reviewer 的盲区**不重叠**——这是用 Codex + Claude
互补的核心价值。如果只跑一个，**至少漏一类问题**。

实战 checklist：
- Codex P2 出现"在 X 之后没重检 Y"类评论时不要先反驳——race condition
  类报告极少误报，先看代码再回应
- Claude `/review-pr` 的 P1 通常是"跨文件 / 跨服务的契约不一致"——
  尤其是涉及"权威定义文档 vs 实际代码"的漂移
- 两个都跑完都没 P0/P1 才算"双重过关"

### Fallback：何时还是要开新会话

- subagent 报告自相矛盾或明显跑偏 → 开新会话用 `/review` 复核
- 涉及多 PR 关联（如本 PR 修了 PR-X 引入的 bug，要回看 PR-X 上下文）
  —— 主会话上下文太大，subagent 也会被截断 → 开新会话整体梳理

---

## 3. 季度手动：第二 vendor 深审

每季度一次（或重要 release 前），跑一次**非 OpenAI 系**的 LLM 审查。
候选：
- Gemini 2.x（Google）
- Cursor Composer 的 Background Agent
- Cody / Augment Code

为什么季度而不是每 PR：第二 vendor 通常没有 GitHub PR webhook 集成，
需要手动喂 diff，开销大。但每季度一次能把「Claude + Codex 都漏的
模式」捞出来一批。

操作模板（人工执行，无自动化）：

1. 选最近一个高风险 PR 或一段时间的 critical-path commits
2. `git log --oneline origin/main~50..origin/main -- shared/ server/`
3. 把改动整理成 markdown diff（限制在 ~500 行内，太长第二 vendor 会
   失焦）
4. 喂给选定 vendor，问：「找出可能的 P0/P1 风险，特别是并发安全 /
   协议契约 / 数值边界」
5. 收集发现 → 比对 docs/regressions.md → 真新发现的 → 走 Loop B

---

## 4. 真机验证（4 关的最后一关）

CLAUDE.md 第五章第 4 关：真机 happy path + 1 个边界场景。

为什么这一关无法被 AI 替代：
- UI 状态机的「非 happy path 分支」（regressions.md #7 Loading 卡住）
  Claude 看代码看不出来，要在断网真机上才显形
- 网络重连时序 / 弱网行为 / 设备特定权限弹窗
- 手感（动画卡顿、字体溢出、按钮可点击区域）

操作：
1. 安装 PR 分支的 APK 到真机
2. 跑一遍正常游戏流程
3. 至少试一种边界（断网、关后台、低电量、慢网）
4. PR 模板里勾「真机验证」复选框

---

## 5. 为什么不全自动化

历史上有过「让 PR-H4 给每个 PR 自动跑 Gemini」的设想，没做的原因：
- 第二 vendor 的 GitHub App 多数收费 / 配额受限
- 自动化反而稀释了人工注意力（噪音多 → 跳过率高）
- Codex 已经在 95% 场景里够用了

季度手动的频率换成 2 周一次也行，但每 PR 不必。

---

## 6. 反模式

- **Claude 多轮自审作为对抗审查替代品**：相关性盲区不会因为多跑几轮
  而消失。dev_summary.md 8.3 的核心警告。
- **修复 Codex 评论时不开新会话**：原会话仍有「我没问题」的偏差。
- **跳过真机这一关**：「CI 绿 + 3 个 LLM 都说没问题」≠ 用户用得起来。
- **评论里直接关掉 Codex P2 不解释**：未来追溯不到为什么忽略；至少
  在 PR 评论里写一句「不影响行为，仅风格」。

---

## 维护

每发现一条「某 vendor 救了我们一命」的 bug，就在第 0 节的表里加一行。
表越长 → 越多人愿意花时间走对抗审查这一关。

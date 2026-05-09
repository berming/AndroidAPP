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

## 2. 半自动触发：关键路径必走 Claude `/review` 新会话

CLAUDE.md 第五章 PR 4 关的第 3 关：「Claude PR review（**新会话**
`/review`，确认无 P0/P1）」。

### 为什么必须**新会话**

提交 PR 的会话已经形成「我的方案没问题」的心理偏差。要 Claude 公正
审查，必须从空白上下文开始。

### 何时强制走

**关键路径**改动时。判定标准（与 CI tdd-gate 一致）：
- `shared/.../engine/CardRules.kt`
- `shared/.../engine/SettlementCalculator.kt`
- `shared/.../network/GameMessage.kt`（含 PROTOCOL_VERSION 升降）
- `server/.../ServerGameManager.kt`
- `server/.../Application.kt`（handleReconnect / handleAction 等流量入口）

PR 模板里要有「关键路径改动 → 已开 Opus 4.7 新会话 `/review`」复选框。
没勾 → reviewer 拒绝合入。

### 操作

1. 在 Claude Code 里 `/clear`（清空当前会话）
2. 切到 Opus 4.7：`/model claude-opus-4-7`
3. 跑：`/review`（如果有该 slash command）或手动喂 PR diff
4. 审查输出 → P0/P1 → 走 Loop B；P2 → 自行判断

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

# Playbook · Loop A — 新功能开发（happy path）

> 从"想做一个新功能"到"PR 合入 main"的标准流程。每步都明确**该用哪个
> 模型 / 哪个工具 / 何时跑测试**。偏离流程不一定错，但偏离前先想清楚
> 在补哪条防线。

参考：CLAUDE.md 第一章「模型选择策略」 + 第三章「关键路径 TDD」 +
第五章「PR 4 关」 + `docs/regressions.md`（避免重复踩历史坑）。

---

## 0. 开始之前

- [ ] 拉取最新 main：`git fetch origin && git checkout main && git pull`
- [ ] 跑 `/test-fast` 确认 main 是绿的（不绿不要在上面叠改动）
- [ ] 想清楚这个功能**会不会**触碰 critical path（`docs/regressions.md`
      列出的几个文件）。如果会，**先看 Loop C**（playbook
      暂未写完整，但流程在 plan 第三章）

---

## 1. 起新会话 + 切支线分支

```bash
git checkout -b feat/<short-name>
```

`SessionStart` hook 自动打印当前分支 / 最近 5 commit / dirty count。
如果 dirty 数不为 0 说明上一会话遗留改动 —— 先处理（`git stash`
或归档到正确分支）再起新功能。

---

## 2. 设计阶段（Opus 4.7）

切到 Opus：`/model claude-opus-4-7`

把以下信息扔给 Opus：

- **功能描述**：1-2 段说清需求
- **影响范围猜测**：哪些目录 / 文件大概率改动
- **是否触碰协议**：要不要加新的 GameMessage 子类、要不要升 `protocolVersion`
- **测试设想**：哪些用例覆盖正常 + 边界 + 失败

要求 Opus 输出：

1. 文件清单 + 每个文件改什么（**包括对应的测试文件**）
2. 关键路径标记（如果有，必须先 TDD —— 跳到 Loop C 流程）
3. 多平台对齐检查：Android / Web / Server 三端是否同步
4. 风险点（参考 `docs/regressions.md`，避免重复踩坑）

输出**不要直接采纳**——读一遍，挑战每条「这个真有必要吗 / 我有没有
更简单的做法」。让 Opus 改一版，**收敛后**再进入实现阶段。

---

## 3. 实现阶段（Sonnet 4.6）

切到 Sonnet：`/model claude-sonnet-4-6`

把 Opus 的文件清单交给 Sonnet，按文件顺序实现。

> **大特性请用 Phase 分段提交**（`docs/dev_summary.md §9.1`）：
> 跨协议层 + 服务端 + 客户端 + 测试的特性（>200 行 / >5 文件）应拆成
> 2-3 个 Phase 各自 commit：
> - Phase 1：协议层 + 服务端 + 单测（**底层稳了再动客户端**）
> - Phase 2：主客户端（Android）
> - Phase 3：次客户端（Web）+ 跨端协议 roundtrip 测
>
> 每个 Phase 独立 commit + 独立 review 周期，CI 红 / Codex 评论范围更小。
> 协议先行的副作用：Phase 2/3 即便 UI 没写完，server 已经能跑（新客户端
> 连老服务端用默认值兼容）。
>
> 每个 Phase 内部还要按"编译单元"切——Android 与 Web 拆 commit，能更早
> 抓到平台特定编译错误（参考 PR #53 wasmJs psi2ir 教训）。

**每改一组**（≤3 个相关文件）跑一次 `/test-fast`：

```
/test-fast
```

通过再继续。失败立刻进入 Loop B。

如果 PostToolUse hook 在某次 Edit 后弹出"⚠️ 关键路径 TDD 提醒"——
立刻**回退**这次实现，先去对应 `*Test.kt` 写失败测试 commit，再回来
做实现（Loop C 的核心）。

---

## 4. 静态扫描（Haiku 4.5）

切到 Haiku：`/model claude-haiku-4-5` —— 跑 `/pre-commit-scan`

它会按 5 大约束 + null safety + 异常路径批量扫一遍。P0 / P1 必须修；
P2 看情况。

---

## 5. 本地 4 关校验

```
/ship-check
```

四关全绿才能 push。任一关红就修对应内容；最常见的是 Gate 3（关键路径
没改对应测试 → 回到第 3 步补 TDD）。

---

## 6. Commit + Push

```bash
git add -A
git commit -m "<conventional commit subject>

详细描述...

Signed-off-by: <name> <email>
AI-Assisted-By: Claude Opus 4.7 (claude-opus-4-7)
AI-Assisted-By: Claude Sonnet 4.6 (claude-sonnet-4-6)
"
git push -u origin feat/<short-name>
```

`commit-msg` 钩子会校验署名两行齐全；`pre-push` 钩子会再跑一次
`:shared:jvmTest` 兜底（`/ship-check` 已经跑过，但分支 push 是最后
机会）。

---

## 7. 创建 PR

通过 MCP github 工具或 `gh pr create`（沙箱环境用 MCP），按
`.github/pull_request_template.md` 填 4 关 checklist。Body 中提及：

- 这个功能解决的问题
- 设计取舍
- 改动是否触碰 critical path（PR-H2 后 CI 会自动 gate）
- **链接到 `docs/regressions.md` 的相关条目**（避免重复历史 Bug）

---

## 8. 等齐 4 关绿灯

| 关 | 来源 | 期望 |
|----|------|------|
| 1 | GitHub Actions（CI） | tests + detekt 全绿 |
| 2 | chatgpt-codex-connector[bot] | 评论无 P1 |
| 3 | Claude `/review`（**必须新会话**，Opus 4.7） | 无 P0/P1 |
| 4 | 真机验证 | happy path + ≥1 边界 |

任一关红：

- CI 红 → Loop D（`docs/playbooks/ci-failure-triage.md`）
- Codex P1 → 同新 Sonnet 会话修复（不要在原会话修——它的"心理偏差"已固化）
- Claude review P0/P1 → 同上
- 真机异常 → Loop B（`docs/playbooks/bug-triage.md`，把现象转测试再修）

---

## 9. 合入 + 清理

PR 合并后：

```bash
git checkout main && git pull
git branch -d feat/<short-name>
```

如果功能引入了 **新的失败模式**（比如发现一类 Bug），在 `docs/regressions.md`
追加条目——下一次会话进来就能读到，避免重复踩坑。

---

## 反模式（不要做）

- **跳过 Opus 设计阶段直接让 Sonnet 写**：80% 的"修了又坏"始于此
- **Edit 完直接 commit 不跑 `/test-fast`**：本地 30 秒能发现的问题，
  让 CI 4 分钟才发现是浪费
- **PR 4 关一次过**就觉得稳：dev_summary.md 反复强调单次修复成功率
  ~12%，4 关同时绿往往是在第 2-3 轮才达到的
- **关键路径改动跳过 TDD**：CLAUDE.md 第三章 + PR-H2 之后的 CI tdd-gate
  会立刻挡下来；与其被挡不如先写测试

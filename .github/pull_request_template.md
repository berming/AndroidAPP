## 改动说明

<!-- 一两句话说明此 PR 解决什么问题 / 实现什么功能 -->

## 影响面分析

每条勾上即承诺该方向已确认（未改动 OR 已按要求做完）。

- [ ] **关键路径**（`CardRules` / `SettlementCalculator` / `ServerGameManager`）：未改动 **或** 同 PR 内已改对应 `*Test.kt`（CI tdd-gate 强制）+ 已跑 `/review-pr <PR#>`
- [ ] **协议 DTO**（`shared/.../network/GameMessage.kt`）：未改动 **或** `protocol-syncer` 已 OK（non-breaking / 已升 `PROTOCOL_VERSION` + `Application.handleReconnect` 仍能拒老客户端）
- [ ] **服务端状态**（`ServerGameState.hands` / `playerScores` / `currentPlayerIndex`）：未改动 **或** 改动全在 `mutexFor(room).withLock {}` 内 + 广播在锁外 + `room.players` 仍是 `CopyOnWriteArrayList`
- [ ] **测试**：现有测试已覆盖 **或** 已新增单测（关键路径必须）**或** 不需要（纯文档 / 资源 / UI 视觉）

## 验证清单（4 关，缺一不可）

- [ ] **CI 绿**：GitHub Actions 全部通过（build + tests + detekt + tdd-gate）—— `update-ci-checkbox` workflow 会自动勾这一格
- [ ] **Codex Bot review**：已等到 `chatgpt-codex-connector` 评论，无新增 P1 问题
- [ ] **Claude PR review**：跑 `/review-pr <PR#>`（PR-H5 引入 pr-reviewer subagent；fallback：开新会话 `/review`）—— 输出无 P0/P1
- [ ] **真机验证**：覆盖 happy path + 至少 1 个边界 / 异常场景

## 截图 / 日志（如适用）

<!-- 真机截图、关键日志片段 -->

---

> ℹ️ PR-H3 起 server 直接依赖 `:shared` 共享 GameMessage / CardRules /
> SettlementCalculator。**约束 1/4 编译期已消除**——不再需要"客户端
> ↔ 服务端各自一份"的双向同步复选框。如果改 GameMessage，参考
> `docs/playbooks/adversarial-review.md` 第 1 节的 protocol-syncer 流程。

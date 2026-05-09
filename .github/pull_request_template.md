## 改动说明

<!-- 一两句话说明此 PR 解决什么问题 / 实现什么功能 -->

## 验证清单（4 关，缺一不可）

- [ ] **CI 绿**：GitHub Actions 全部通过（build + tests + detekt + tdd-gate）
- [ ] **Codex Bot review**：已等到 `chatgpt-codex-connector` 评论，无新增 P1 问题
- [ ] **Claude PR review**：开新会话用 Opus 4.7 跑 `/review`，输出无 P0/P1
- [ ] **真机验证**：覆盖 happy path + 至少 1 个边界 / 异常场景

## 关键路径改动？（CardRules / SettlementCalculator / ServerGameManager）

CI 的 `tdd-gate` job 会强制校验：以下三个文件被改但对应 `*Test.kt` 没动 → CI 红。

- [ ] **否**
- [ ] **是** —— 必须满足全部：
  - [ ] 同 PR 内修改对应 `*Test.kt`（`shared/src/commonTest/.../engine/CardRulesTest.kt` /
        `SettlementCalculatorTest.kt` / `server/src/test/.../ServerGameManagerTest.kt`）
  - [ ] **已开新会话用 Opus 4.7 `/review`**（`docs/playbooks/adversarial-review.md` 第 2 节）

## 协议 DTO 改动？（`shared/.../network/GameMessage.kt`）

GameMessage.kt 不在 tdd-gate 范围内（没有专门的 GameMessageTest），改动改走
`protocol-syncer` subagent 检查 PROTOCOL_VERSION 升降。

- [ ] **否**
- [ ] **是** —— 必须满足：
  - [ ] `protocol-syncer` agent 已运行，分类为 non-breaking **或** 已升 PROTOCOL_VERSION
  - [ ] 升版本则 `server/.../Application.kt::handleReconnect` 的版本握手仍正确拒绝旧客户端
  - [ ] 关键路径改动 → 已开新会话用 Opus 4.7 `/review`

## 服务端状态修改？

- [ ] **否**
- [ ] **是** —— 已确认：
  - [ ] 修改在 `mutexFor(room).withLock { ... }` 内
  - [ ] 广播在锁外
  - [ ] `room.players` 仍是 `CopyOnWriteArrayList`

## 测试

- [ ] 改动代码已被现有测试覆盖
- [ ] 已新增单元测试（关键路径必须）
- [ ] 不需要测试（如纯文档 / 资源文件）

## 截图 / 日志（如适用）

<!-- 真机截图、关键日志片段 -->

---

> ℹ️ PR-H3 起 server 直接依赖 `:shared` 共享 GameMessage / CardRules /
> SettlementCalculator。**约束 1/4 编译期已消除**——不再需要"客户端
> ↔ 服务端各自一份"的双向同步复选框。如果改 GameMessage，参考
> `docs/playbooks/adversarial-review.md` 第 1 节的 protocol-syncer 流程。

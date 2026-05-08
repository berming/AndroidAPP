## 改动说明

<!-- 一两句话说明此 PR 解决什么问题 / 实现什么功能 -->

## 验证清单（4 关，缺一不可）

- [ ] **CI 绿**：GitHub Actions 全部通过（build + tests + detekt）
- [ ] **Codex Bot review**：已等到 `chatgpt-codex-connector` 评论，无新增 P1 问题
- [ ] **Claude PR review**：开新会话用 Opus 4.7 跑 `/review`，输出无 P0/P1
- [ ] **真机验证**：覆盖 happy path + 至少 1 个边界 / 异常场景

## 涉及共享逻辑？（单机 / 联网双份代码）

- [ ] **否**
- [ ] **是** —— 已同步以下两侧：
  - [ ] `engine/CardRules.kt` ↔ `server/.../ServerGameManager.kt::canBeat`
  - [ ] `engine/SettlementCalculator.kt` ↔ `ServerGameManager::computeAllFinishedScores`
  - [ ] 其他共享：__________________

## 涉及协议消息？（WebSocket）

- [ ] **否**
- [ ] **是** —— 客户端 `network/GameMessage.kt` 与服务端 `Messages.kt` 已对齐：
  - [ ] 字段增删一致
  - [ ] 枚举字符串值一致
  - [ ] 默认值一致

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

---
description: Push 前 4 关本地校验（detekt + 测试 + 签名 + 关键路径同改）
allowed-tools: Bash(./gradlew detekt), Bash(./gradlew :shared:jvmTest), Bash(./gradlew :apps:communication-card:test), Bash(git diff*), Bash(git log*), Bash(git status*), Read, Bash(grep*)
---

# /ship-check

Push 前的 **本地** 4 关校验。把人工 PR checklist（CLAUDE.md 第五章）
机械化，避免漏项。

CI 仍是最终权威——本命令只是把"反馈周期从 4 分钟缩到 30 秒"。

## 执行步骤（顺序，前一关失败立刻报，不继续）

### Gate 1: detekt + 测试

1. `./gradlew detekt --console=plain` —— 必须 exit 0
2. `./gradlew :shared:jvmTest --console=plain`（或降级 `:apps:communication-card:test`）
   —— 必须全绿

任一失败 → 报失败位置，**不继续**。

### Gate 2: commit message 署名

`git log -1 --pretty=%B` 取最近 commit message，校验：

- 含 `^Signed-off-by: ` 行
- 含 `^AI-Assisted-By: ` 行（CLAUDE.md 第八章）

缺任一 → 报「最近 commit 缺署名」+ 提示用 `git commit --amend`。

### Gate 3: 关键路径与对应测试同改（PR-H2 之后启用）

`git diff --name-only origin/main...HEAD` 取本分支改动文件，按规则校验：

| 改动文件 | 必须同时改动 |
|----------|-------------|
| `shared/.../engine/CardRules.kt` | `shared/.../engine/CardRulesTest.kt` |
| `shared/.../engine/SettlementCalculator.kt` | `shared/.../engine/SettlementCalculatorTest.kt` |
| `server/.../ServerGameManager.kt` | `server/.../ServerGameManagerTest.kt` |

（PR-H2 之前 CardRulesTest/ServerGameManagerTest 不存在，跳过对应规则。）

不满足 → 报具体哪个文件缺测试，附 docs/playbooks/bug-triage.md 链接。

### Gate 4: 协议双端对齐（PR-H3 之后退役）

如果改动了 `shared/src/commonMain/kotlin/.../network/GameMessage.kt`：

- 必须同时改动 `server/src/main/kotlin/.../Messages.kt`
- 或 PR 描述中标注「服务端不需要变更（仅客户端 UI 字段）」

不满足 → 报「协议未双端对齐」+ 提示运行 `/align-server-shared`。

PR-H3 合并后 server 直接依赖 :shared，本关删除。

## 输出格式

```
🚦 ship-check
  Gate 1 detekt + jvmTest    ✅
  Gate 2 commit signing      ✅
  Gate 3 critical-path TDD   ⚠️  CardRules.kt 改动但 CardRulesTest.kt 未改
  Gate 4 server protocol     ✅

→ 修复 Gate 3 后再 push（Loop B：先在 CardRulesTest 写失败测试）。
```

```
🚦 ship-check
  Gate 1 ✅  Gate 2 ✅  Gate 3 ✅  Gate 4 ✅
  → 可以 push。
```

## 不做的事

- 不**自动** push（用户手动 `git push` 触发 pre-push hook 二次校验）
- 不调 `gh pr create`（那是另一步）
- 不修代码（失败时只报告）

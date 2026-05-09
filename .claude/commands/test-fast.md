---
description: 跑 :shared:jvmTest（≤30s），失败摘要直出
allowed-tools: Bash(./gradlew :shared:jvmTest), Bash(./gradlew :apps:communication-card:test), Read, Bash(grep*), Bash(cat *), Bash(head *), Bash(tail *)
---

# /test-fast

本地极速反馈循环。优先跑 `:shared:jvmTest`（含 15 个 SettlementCalculatorTest
用例 + PR-H2 之后的 CardRulesTest）。`:shared` 模块尚未存在时降级到
`:apps:communication-card:test`。

## 执行步骤

1. 检查 `settings.gradle.kts` 是否 `include(":shared")`：
   - 是 → 跑 `./gradlew :shared:jvmTest --console=plain`
   - 否 → 跑 `./gradlew :apps:communication-card:test --console=plain`
2. 若失败：解析输出，把 **第一组失败的测试名 + 关键 stack 行**摘出来给我看。
3. 若通过：一句话报「✅ N 个测试全绿，耗时 Xs」。
4. **不要**自动尝试修复。失败时只报告，让用户决定下一步（通常是进入
   `docs/playbooks/bug-triage.md` 的 Loop B）。

## 输出格式

成功：
```
✅ test-fast: 15 个测试全绿（22.4s）
```

失败：
```
❌ test-fast: 1 失败
  · com.communicationcard.game.engine.SettlementCalculatorTest.test05PartiallyFinished
    expected: 240 but was: 220
    at SettlementCalculator.kt:142

下一步：进 Loop B（docs/playbooks/bug-triage.md）
```

## 不做的事

- 不修代码（让 Loop B 走流程）
- 不跑 detekt（那是 `/pre-commit-scan` 的职责）
- 不跑 `:apps:communication-card:assembleDebug`（慢，留给 CI）

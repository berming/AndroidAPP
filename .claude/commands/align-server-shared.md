---
description: 检查客户端 :shared 与服务端的 canBeat / Messages 是否仍然等价（PR-H3 后退役）
allowed-tools: Read, Bash(grep*), Bash(diff*), Bash(find *)
---

# /align-server-shared

CLAUDE.md 约束 1 / 4 要求：

- `shared/.../engine/CardRules.kt::canBeat` ↔ `server/.../ServerGameManager.kt::canBeat`
- `shared/.../network/GameMessage.kt` ↔ `server/src/main/kotlin/.../Messages.kt`

本命令机械化对比这两组，发现漂移立刻报告。**PR-H3 合并 server 进
:shared 之后，本命令退役。**

## 执行步骤

### 1. canBeat 函数体对比

1. 从 `shared/src/commonMain/kotlin/com/communicationcard/game/engine/CardRules.kt`
   抽取 `fun canBeat(...)` 完整方法体（含递归内部调用如 `getRankValue`）。
2. 从 `server/src/main/kotlin/com/communicationcard/server/ServerGameManager.kt`
   抽取同名方法。
3. 规范化（去空白 + 注释）后语义对比。
4. 报：相同 / 公式不同 / 仅细节差异（命名）。

### 2. Messages DTO 对比

1. 列出 `:shared` 的 GameMessage.kt 中所有 `@Serializable` 顶层类（含
   sealed class 的子类）和它们的字段集合。
2. 列出 `server/.../Messages.kt` 同集合。
3. 报：缺失的类 / 多余的类 / 字段不一致的类。

### 3. 枚举值字符串对比

抽取两边所有 `enum class` 的所有值，对比字符串（`CLUB` vs `CLUBS` 这
种回归曾真实出现过 —— 见 `docs/regressions.md` "CardSuit 不匹配"）。

## 输出格式

全对齐：
```
✅ align-server-shared
  · canBeat: 等价
  · Messages: 23 个顶层类，字段全部对齐
  · 枚举: CardSuit / CardRank / GamePhase / RoomStatus 全部对齐
```

发现漂移：
```
⚠️ align-server-shared
  · canBeat: 不等价
      shared 第 88 行: bombSize > 4 时优先级覆盖普通牌
      server 第 162 行: 缺这条分支（参考 regressions.md "炸弹张数比较"）
  · Messages: GameAction 在 :shared 多了字段 timestamp
  · 枚举: 全对齐

→ 同步建议：
  1. 编辑 server/.../ServerGameManager.kt 第 162 行加入 bombSize 分支
  2. 在 server/.../Messages.kt::GameAction 加 timestamp 字段
  3. 跑 /test-fast 验证（CardRulesTest 应通过 PR-H2 之后的对应用例）
```

## PR-H3 后

本命令直接 echo「✅ server 已依赖 :shared，无需对齐检查 —— 本命令已退役」
然后 exit 0。或者把命令文件直接删掉。

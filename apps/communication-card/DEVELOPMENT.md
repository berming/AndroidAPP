# 沟通牌 (Communication Card) App 开发总结

## 项目概述

沟通牌是一款基于传统扑克规则的团队对战卡牌游戏 Android 应用。玩家分为红蓝两队，目标是通过出牌获得 200 分以上来赢得比赛。

## 技术栈

- **语言**: Kotlin
- **平台**: Android (minSdk 24, targetSdk 34)
- **构建工具**: Gradle 8.14.3 + AGP 8.5.0
- **架构**: 单 Activity + 多 View 模式
- **CI/CD**: GitHub Actions 自动构建

## 项目结构

```
apps/communication-card/
├── src/main/java/com/communicationcard/game/
│   ├── model/          # 数据模型
│   │   ├── Card.kt         # 卡牌定义（花色、点数、分值）
│   │   ├── Deck.kt         # 牌组管理
│   │   └── Player.kt       # 玩家状态
│   ├── engine/         # 游戏引擎
│   │   ├── CardRules.kt           # 牌型规则（单张、对子、三张、顺子、炸弹）
│   │   ├── GameEngine.kt          # 游戏流程控制
│   │   ├── SettlementCalculator.kt # 结算逻辑
│   │   └── SettlementVerification.kt
│   ├── ai/             # AI 玩家
│   │   └── AIPlayer.kt     # 三档难度 AI 策略
│   └── ui/             # 用户界面
│       ├── MainActivity.kt  # 主菜单
│       └── GameActivity.kt  # 游戏界面
├── src/main/res/
│   ├── layout/         # 布局文件
│   ├── drawable/       # 图形资源
│   ├── values/         # 颜色、字符串、主题
│   └── mipmap-*/       # 应用图标
├── keystore/           # 签名密钥
└── build.gradle.kts    # 构建配置
```

## 游戏规则

### 牌型
| 牌型 | 说明 |
|------|------|
| 单张 | 任意一张牌 |
| 对子 | 两张相同点数 |
| 三张 | 三张相同点数 |
| 顺子 | 5张及以上连续点数（不含王） |
| 炸弹 | 4张及以上相同点数 |

### 计分规则
- K = 10分, 10 = 10分, 5 = 5分
- 全副牌共 400 分
- 达到 200 分即可获胜

### 结算条件
1. **全队走完**: 先走完的队伍获得己方已收分 + 对方未完成玩家的手牌分和已收分
2. **提前结算**: 任一队伍已收分达到 200 分时立即结算

## 开发历程

### 第一阶段：项目初始化
1. 创建基础项目结构和游戏模型
2. 实现牌型识别和比较逻辑
3. 搭建 AI 玩家系统（三档难度）
4. 完成基础 UI 界面

### 第二阶段：项目重构
1. 重构项目支持多 App（沟通牌 + 五子棋）
2. 升级 Gradle 和 AGP 版本
3. 添加结算逻辑单元测试（15个用例全覆盖）

### 第三阶段：UI 优化
1. 重新设计游戏界面，显示每位玩家的出牌
2. 手牌双行布局，动态重叠
3. 玩家信息单行显示
4. 添加游戏记录功能

### 第四阶段：交互优化
1. 炸弹牌型紧凑布局（30%重叠）
2. 智能出牌提示策略
3. 点击空白处取消选牌
4. 当前领先玩家按队伍颜色显示

### 第五阶段：细节打磨
1. 手牌显示顺序优化（炸弹优先、按点数排序）
2. 按钮文字完整显示
3. 卡牌圆角优化（手牌10dp、已出牌6dp）
4. 固定 APK 签名配置

---

## 修订清单

| 版本 | 提交 | 类型 | 描述 |
|------|------|------|------|
| v1.0 | 4f1d7ad | feat | 初始版本：完整游戏规则、AI对手、Android UI |
| v1.1 | c931140 | chore | 升级 Gradle wrapper 和 AGP 版本 |
| v1.2 | 0e3eea1 | refactor | 重构项目结构支持多 App |
| v1.3 | 1e37408 | test | 添加结算逻辑测试（15个用例） |
| v1.4 | bece62b | fix | 重新设计 UI，显示每位玩家出牌 |
| v1.5 | e99f7bf | fix | 改进 UI 布局，手牌动态重叠 |
| v1.6 | 222a12b | fix | 手牌双行显示，玩家横向排列 |
| v1.7 | 56f3299 | fix | 统一字体大小，修复玩家 ID 映射 |
| v1.8 | e6e9892 | feat | 添加游戏记录，修复队伍分数，改进布局 |
| v1.9 | b03c954 | feat | 炸弹紧凑布局，智能提示，点击取消选牌 |
| v1.10 | 6c8a5bb | fix | 修正炸弹 20% 重叠显示 |
| v1.11 | b938ad9 | feat | 优化出牌提示策略 |
| v1.12 | 09de5ac | feat | 当前领先按队伍颜色显示，手牌炸弹重叠 |
| v1.13 | 510eaa4 | feat | 改进手牌显示，CI 只构建 communication-card |
| v1.14 | 9272ee3 | feat | 手牌排序优化，提示按钮切换 |
| v1.15 | 0fe964d | fix | 修复手牌顺序，添加固定签名配置 |
| v1.16 | ccab914 | fix | 修复签名配置 getByName |
| v1.17 | 0c87be2 | feat | 多项 UI 修复：手牌顺序、按钮文字、圆角、顶部空间 |
| v1.18 | 2fe704e | fix | 恢复卡牌圆角 6dp，按钮文字完整显示 |
| v1.19 | 9bfe427 | feat | 手牌使用更大圆角（10dp） |

---

## 关键问题与解决方案

### 1. 签名配置冲突
**问题**: `Cannot add a SigningConfig with name 'debug' as a SigningConfig with that name already exists`

**解决**: 使用 `getByName("debug")` 替代 `create("debug")`

```kotlin
signingConfigs {
    getByName("debug") {
        storeFile = file("keystore/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
}
```

### 2. 卡牌重叠显示
**问题**: 炸弹牌型需要紧凑显示但保持可读性

**解决**: 使用负边距 + 禁用裁剪
```xml
android:clipChildren="false"
android:clipToPadding="false"
```

```kotlin
// 30% 重叠
val overlapMargin = -(cardWidth * 0.30).toInt()
```

### 3. 手牌排序逻辑
**问题**: 手牌需要按特定规则显示（炸弹优先、按大小排序）

**解决**:
```kotlin
// 炸弹：按张数降序，再按点数降序
val bombGroups = cardsByRank.filter { it.value.size >= 4 }
    .sortedWith(compareByDescending<...> { it.second.size }
        .thenByDescending { it.first.value })

// 非炸弹：按点数降序
val nonBombGroups = cardsByRank.filter { it.value.size < 4 }
    .sortedByDescending { it.first.value }
```

### 4. 手牌圆角不明显
**问题**: 6dp 圆角在较大的手牌上不够明显

**解决**: 为手牌创建独立的 10dp 圆角样式
- `card_background_large.xml` (10dp)
- `card_selected_large.xml` (10dp)

---

## 构建说明

```bash
# 构建 Debug APK
./gradlew :apps:communication-card:assembleDebug

# 运行测试
./gradlew :apps:communication-card:test

# APK 输出路径
apps/communication-card/build/outputs/apk/debug/communication-card-debug.apk
```

---

## 后续优化方向

1. 添加在线多人对战功能
2. 优化 AI 策略（机器学习）
3. 添加游戏回放功能
4. 支持自定义规则变体
5. 添加音效和动画

---

*文档更新日期: 2026-02-11*

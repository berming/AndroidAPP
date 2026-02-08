# 多平台游戏项目架构

## 目录结构

```
GameHub/
├── build.gradle.kts                 # 根项目配置
├── settings.gradle.kts              # 多模块配置
├── gradle.properties                # Gradle属性
│
├── shared/                          # 共享核心库
│   └── core/                        # 通用工具类
│       ├── build.gradle.kts
│       └── src/
│           ├── commonMain/          # 通用代码
│           ├── androidMain/         # Android特定
│           └── iosMain/             # iOS特定
│
├── games/                           # 游戏模块
│   ├── communication-card/          # 沟通牌
│   │   ├── shared/                  # 共享游戏逻辑 (KMP)
│   │   │   ├── build.gradle.kts
│   │   │   └── src/
│   │   │       ├── commonMain/      # 游戏核心逻辑
│   │   │       ├── androidMain/
│   │   │       └── iosMain/
│   │   ├── composeApp/              # Compose Multiplatform (Android + iOS)
│   │   │   ├── build.gradle.kts
│   │   │   └── src/
│   │   │       ├── commonMain/      # 共享UI
│   │   │       ├── androidMain/     # Android入口
│   │   │       └── iosMain/         # iOS入口
│   │   └── harmonyApp/              # HarmonyOS应用 (ArkTS)
│   │       ├── build-profile.json5
│   │       ├── hvigorfile.ts
│   │       └── entry/
│   │           └── src/main/
│   │               ├── ets/         # ArkTS代码
│   │               └── resources/
│   │
│   └── gomoku/                      # 五子棋
│       ├── shared/
│       ├── composeApp/
│       └── harmonyApp/
│
├── iosApp/                          # iOS项目入口
│   └── iosApp.xcodeproj
│
└── docs/
    └── architecture.md
```

## 技术栈

| 平台 | UI框架 | 语言 | 共享逻辑 |
|------|--------|------|----------|
| Android | Compose Multiplatform | Kotlin | KMP共享模块 |
| iOS | Compose Multiplatform | Kotlin/Swift | KMP共享模块 |
| HarmonyOS | ArkUI | ArkTS | 独立实现(可复用逻辑) |

## 模块依赖关系

```
┌─────────────────────────────────────────────────────────────┐
│                        应用层 (Apps)                         │
├───────────────────┬───────────────────┬─────────────────────┤
│   Android App     │     iOS App       │   HarmonyOS App     │
│ (Compose/Kotlin)  │ (Compose/Swift)   │     (ArkUI/ArkTS)   │
├───────────────────┴───────────────────┴─────────────────────┤
│                     UI层 (composeApp)                        │
│              Compose Multiplatform 共享UI                    │
├─────────────────────────────────────────────────────────────┤
│                   游戏逻辑层 (shared)                         │
│              Kotlin Multiplatform 共享逻辑                   │
├─────────────────────────────────────────────────────────────┤
│                    核心库 (shared/core)                      │
│                    通用工具和基础设施                         │
└─────────────────────────────────────────────────────────────┘
```

## 构建命令

```bash
# Android
./gradlew :games:communication-card:composeApp:assembleDebug
./gradlew :games:gomoku:composeApp:assembleDebug

# iOS (需要macOS)
./gradlew :games:communication-card:composeApp:iosDeployIPhone14Debug
./gradlew :games:gomoku:composeApp:iosDeployIPhone14Debug

# HarmonyOS (使用DevEco Studio)
cd games/communication-card/harmonyApp && hvigorw assembleHap
cd games/gomoku/harmonyApp && hvigorw assembleHap
```

## 代码共享策略

### 1. 游戏核心逻辑 (100%共享)
- 游戏规则引擎
- AI算法
- 数据模型
- 状态管理

### 2. UI层 (Android/iOS共享, HarmonyOS独立)
- Compose Multiplatform: Android + iOS
- ArkUI: HarmonyOS

### 3. 平台特定代码
- 文件存储
- 网络请求
- 系统API调用

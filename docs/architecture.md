# 项目架构

## 顶层结构

```
AndroidAPP/
├── build.gradle.kts                    # 根项目配置
├── settings.gradle.kts                 # 多模块配置
├── gradle.properties
├── gradlew
│
├── apps/                               # 客户端应用
│   ├── communication-card/             # 沟通牌（主项目，含多人联网）
│   │   ├── build.gradle.kts
│   │   ├── DEVELOPMENT.md
│   │   ├── keystore/                   # 签名密钥
│   │   └── src/
│   │       ├── main/java/com/communicationcard/game/
│   │       │   ├── CommunicationCardApp.kt
│   │       │   ├── ai/                 # 单机版 AI
│   │       │   ├── engine/             # 游戏引擎
│   │       │   ├── model/              # 数据模型
│   │       │   ├── network/            # 网络层（多人联网）
│   │       │   ├── ui/                 # 单机版 UI
│   │       │   ├── ui/multiplayer/     # 多人联网 UI
│   │       │   └── util/               # 工具类
│   │       └── test/                   # JVM 单元测试
│   │
│   └── gomoku/                         # 五子棋（独立 APP）
│       └── src/main/...
│
├── server/                             # 多人联网服务端（Ktor）
│   ├── build.gradle.kts
│   ├── gradlew
│   └── src/main/kotlin/com/communicationcard/server/
│       ├── Application.kt              # 主入口、WebSocket 路由、消息分发
│       ├── Messages.kt                 # 消息协议（与客户端 GameMessage.kt 对齐）
│       ├── GameSession.kt              # WebSocket 会话封装
│       ├── ServerRoomManager.kt        # 房间生命周期与玩家管理
│       └── ServerGameManager.kt        # 游戏逻辑、AI 决策、回合计时
│
└── docs/
    ├── architecture.md                 # 本文档
    ├── multiplayer_guide.md            # 多人联网部署、协议、调试
    └── settlement_verification.md      # 结算逻辑验证用例
```

## 技术栈

| 组件 | 技术 |
|------|------|
| 客户端 UI | Android XML 布局（不使用 Compose） |
| 客户端语言 | Kotlin + Coroutines + Flow |
| 客户端构建 | AGP 8.5 + Gradle 8.14 |
| 服务端语言 | Kotlin + Coroutines |
| 服务端框架 | Ktor + Netty + WebSockets |
| 服务端构建 | Gradle 8.4 |
| 序列化 | kotlinx.serialization (JSON) |
| 客户端 WebSocket | OkHttp |

## 关键模块依赖

```
┌─────────────────────────────────────────────────────────────┐
│ 客户端 Android (apps/communication-card)                    │
│                                                             │
│  ui/ (Activity 与适配器)                                    │
│    ├─ MainActivity / GameActivity (单机)                    │
│    └─ ui/multiplayer/                                       │
│        LobbyActivity → RoomActivity → OnlineGameActivity    │
│                                                             │
│  engine/                                                    │
│    ├─ GameEngine (单机引擎)                                 │
│    ├─ MultiplayerGameEngine (网络引擎适配器)                │
│    │     ↓ 通过 GameSyncManager 与服务器交互                │
│    ├─ CardRules (牌型识别 / 比较 / 合法性)                  │
│    └─ SettlementCalculator (结算公式)                       │
│                                                             │
│  network/                                                   │
│    ├─ NetworkManager     (WebSocket 连接 / 心跳 / 重连)     │
│    ├─ RoomManager        (房间状态 + RoomEvents 流)         │
│    ├─ GameSyncManager    (游戏状态同步 + 回合计时)          │
│    ├─ TextChatManager    (聊天 / 快捷消息)                  │
│    └─ GameMessage.kt     (消息协议、与服务端 1:1 对齐)      │
│                                                             │
│  model/ (Card / Deck / Player / Team)                       │
└─────────────────────────────────────────────────────────────┘
                          ▲
                          │ WebSocket  /game
                          ▼
┌─────────────────────────────────────────────────────────────┐
│ 服务端 (server/)                                            │
│                                                             │
│  Application.kt (路由 + handle*)                            │
│       ├─ ServerRoomManager  (room 创建/加入/AI 填充/结算)   │
│       └─ ServerGameManager  (游戏状态/AI 决策/回合计时)     │
│             • 每房间一个 Mutex 串行化所有状态修改           │
│             • 每房间一个 turnTimer (30s 超时由 AI 接管)     │
│             • 三级 AI 回退：首选 → 过牌 → 最小单张          │
│                            → 强制推进（兜底）               │
└─────────────────────────────────────────────────────────────┘
```

## 客户端 → 服务端消息流

1. `LobbyActivity.onCreate` → 创建 `NetworkManager` 与 `RoomManager`，调用 `connect(SERVER_URL)`
2. 用户点击创建/加入房间 → `RoomManager.createRoom/joinRoom` 通过 `NetworkManager.send` 发送 `CreateRoom` / `JoinRoom`
3. 服务端 `handleCreateRoom` / `handleJoinRoom` 处理 → 回 `RoomCreated` / `RoomJoined`
4. `RoomManager` 收到后保存 `_localPlayerId` 并把 `playerId` 设为 `NetworkManager.sessionToken`（用于断线重连）
5. `RoomManager` 触发 `RoomEvent.RoomCreated` / `JoinedRoom` → `LobbyActivity.navigateToRoom` → 启动 `RoomActivity`
6. `RoomActivity` 监听 `RoomManager.currentRoom` 与 `roomEvents`，处理准备 / 添加 AI / 开始游戏
7. 房主点击开始 → `StartGameRequest` → 服务端 `fillWithAI(6)` → 广播 `RoomUpdate(IN_GAME)` 与每玩家的 `GameStart(state)`
8. `RoomActivity` 收到 `GameStart` → 启动 `OnlineGameActivity`，把初始状态 / 本地座位号通过 Intent 传过去
9. `OnlineGameActivity` 创建 `GameSyncManager` 与 `MultiplayerGameEngine`；后续的所有 `game.*` 消息由 `GameSyncManager` 收集并暴露成 Flow

## 关键不变量

- `player.id`（房间内稳定）= 创建/加入时的 `session.id`，**断线重连不变**
- `SerializedPlayer.id`（游戏状态中）= `player.seatIndex`（0..N-1）
- `SerializedPlayer.remoteId` = `player.id`（与 `_localPlayerId` 一致，用于在房间列表中识别本机）
- 服务端权威：客户端的 `humanPlay` / `humanPass` 是**乐观响应**，最终以服务端广播的 `GameActionResult.state` 为准
- `state.version` 单调递增；客户端 `GameSyncManager.applyState` 会丢弃 `version` 倒退的状态

## 构建命令

```bash
# Android 客户端
./gradlew :apps:communication-card:assembleDebug
./gradlew :apps:communication-card:test          # JVM 单元测试

# 五子棋
./gradlew :apps:gomoku:assembleDebug

# 服务端（在 server/ 目录下，独立的 Gradle 工程）
cd server && ./gradlew run        # 开发运行
cd server && ./gradlew build      # 打包
```

## 单机 vs 联网游戏

单机模式与联网模式**复用同一份 UI 布局** (`activity_game.xml`)，区别仅在引擎与状态来源：

| 维度 | 单机 (`GameActivity`) | 联网 (`OnlineGameActivity`) |
|------|----------------------|----------------------------|
| 引擎 | `GameEngine` | `MultiplayerGameEngine` |
| 玩家动作 | 直接调用引擎方法 | 发送 WebSocket 消息，等服务端广播 |
| AI 决策 | 客户端 `AIPlayer` | 服务端 `ServerGameManager.decideAIAction` |
| 状态 | 客户端持有 | 服务端权威，客户端通过 `GameSyncManager` 同步 |
| 回合计时 | 不限时 | 每回合 30s（服务端兜底） |
| 出牌验证 | 客户端 `CardRules` | 服务端 `ServerGameManager.canBeat` + `identifyCardGroup` |
| 结算 | `SettlementCalculator` | 服务端 `checkGameEnd`（公式相同） |

> 为保证两端结算一致，服务端 `checkGameEnd` 使用与 `SettlementCalculator` 完全相同的公式：
> 全队走完时赢方得分 = 赢方已收 + 输方未走完玩家(已收 + 手牌分)；输方得分 = 输方已走完玩家已收。

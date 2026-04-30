# 沟通牌 - 多人游戏模式

## 功能概述

多人游戏模式支持多名玩家通过网络在线对战，不足6人时自动补充AI玩家。

### 主要功能
- **在线对战**: 2-6名真人玩家通过WebSocket连接
- **AI补位**: 不足6人时自动填充AI玩家
- **文字聊天**: 支持自由输入和快捷消息
- **断线重连**: 断开连接后自动尝试重连，AI暂时接管
- **回合计时**: 30秒出牌时间限制，超时自动过牌

## 架构说明

### 服务端 (server/)

```
server/
├── build.gradle.kts              # Ktor依赖配置
└── src/main/kotlin/com/communicationcard/server/
    ├── Application.kt            # 主服务器，WebSocket路由
    ├── Messages.kt               # 消息协议（与客户端共享）
    ├── GameSession.kt            # WebSocket会话封装
    ├── ServerRoomManager.kt      # 房间管理（创建/加入/离开）
    └── ServerGameManager.kt      # 游戏逻辑（出牌/AI/计时）
```

**技术栈**:
- Ktor Server (Netty引擎)
- WebSocket通信
- kotlinx-serialization JSON序列化
- Kotlin协程

### 客户端 (apps/communication-card/)

```
网络层 (network/):
├── NetworkManager.kt        # WebSocket连接管理，心跳，重连
├── RoomManager.kt           # 房间状态管理
├── GameSyncManager.kt       # 游戏状态同步
├── TextChatManager.kt       # 聊天消息管理
└── GameMessage.kt           # 消息协议定义

游戏引擎 (engine/):
└── MultiplayerGameEngine.kt # 多人游戏引擎适配器

界面 (ui/multiplayer/):
├── LobbyActivity.kt         # 大厅（创建/加入房间）
├── RoomActivity.kt          # 房间等待界面
├── OnlineGameActivity.kt    # 多人游戏界面
└── ChatAdapter.kt           # 聊天消息适配器
```

## 消息协议

### 房间消息
| 类型 | 方向 | 说明 |
|------|------|------|
| `room.create` | C→S | 创建房间 |
| `room.created` | S→C | 房间创建成功 |
| `room.join` | C→S | 加入房间 |
| `room.joined` | S→C | 加入成功 |
| `room.leave` | C→S | 离开房间 |
| `room.update` | S→C | 房间状态更新 |
| `room.ready` | C→S | 准备/取消准备 |
| `room.start` | C→S | 开始游戏（仅房主） |
| `room.kick` | C→S | 踢出玩家（仅房主） |
| `room.add_ai` | C→S | 添加AI玩家（仅房主） |

### 游戏消息
| 类型 | 方向 | 说明 |
|------|------|------|
| `game.start` | S→C | 游戏开始，发送初始状态 |
| `game.action` | C→S | 玩家动作（出牌/过牌） |
| `game.action_result` | S→C | 动作结果 |
| `game.event` | S→C | 游戏事件广播 |
| `game.sync` | S→C | 状态同步 |
| `game.turn_timeout` | S→C | 回合超时 |
| `game.end` | S→C | 游戏结束 |

### 聊天消息
| 类型 | 方向 | 说明 |
|------|------|------|
| `chat.text` | 双向 | 文字消息 |
| `chat.quick` | 双向 | 快捷消息 |

### 系统消息
| 类型 | 方向 | 说明 |
|------|------|------|
| `sys.heartbeat` | 双向 | 心跳保活 |
| `sys.error` | S→C | 错误消息 |
| `sys.player_disconnected` | S→C | 玩家断开连接 |
| `sys.player_reconnected` | S→C | 玩家重新连接 |
| `sys.reconnect` | C→S | 请求重连 |
| `sys.reconnect_success` | S→C | 重连成功 |

## 运行说明

### 启动服务器

```bash
cd server
./gradlew run
```

服务器默认监听 `0.0.0.0:8080`，WebSocket端点为 `/game`

### 客户端连接

客户端默认连接地址（在LobbyActivity.kt中配置）:
- Android模拟器: `ws://10.0.2.2:8080/game`
- 真机测试: 需修改为服务器实际IP

### 游戏流程

1. **创建/加入房间**
   - 玩家在大厅输入昵称
   - 创建新房间或输入房间码加入

2. **等待准备**
   - 房间内玩家点击"准备"
   - 房主可添加AI玩家
   - 所有玩家准备后，房主可开始游戏

3. **游戏进行**
   - 自动填充AI至6人
   - 黑桃3的玩家先出
   - 每回合30秒时间限制
   - 支持聊天和快捷消息

4. **游戏结束**
   - 一队全部出完或达到200分
   - 显示最终比分

## 快捷消息

| 类型 | 文本 |
|------|------|
| NICE_PLAY | 好牌！ |
| HELP_TEAMMATE | 队友上！ |
| PASS | 要不起 |
| BOMB_WARNING | 小心炸弹 |
| GOOD_GAME | GG |
| HURRY_UP | 快点啊 |
| SORRY | 抱歉 |
| THANKS | 谢谢 |

## 配置说明

### 服务器配置

在 `Application.kt` 中可修改:
- 端口号: 默认 8080
- WebSocket超时: 默认 60秒
- 心跳间隔: 默认 15秒

### 游戏配置

在 `ServerGameManager.kt` 中可修改:
- 回合超时: `TURN_TIMEOUT_MS = 30000` (30秒)
- AI出牌延迟: `AI_DELAY_MS = 1000` (1秒)

## 注意事项

1. **网络要求**: 客户端和服务器需在同一网络或服务器有公网IP
2. **最低人数**: 需要至少2名真人玩家才能开始
3. **最大人数**: 支持最多6名玩家（真人+AI）
4. **断线处理**: 断线玩家由AI接管，重连后恢复控制

# 沟通牌 · 多人游戏完整指南

> 本文覆盖三件事：本地起服务、客户端怎么连、生产部署。
> 协议 / AI / 重连 / FAQ 等"运行时知识"在后半部分。
>
> 部署到自有服务器走 [`docs/playbooks/web-deploy.md`](playbooks/web-deploy.md)
> （已脚本化：`deploy/install.sh` + `Caddyfile` + systemd unit + GitHub Actions
> 自动 push 部署）；本节只说本地开发。

## 快速开始

### 1. 本地启动服务端

```bash
./gradlew :server:run         # 监听 0.0.0.0:8080，提供 /game WebSocket
```

成功启动会输出：
```
=== Starting Server ===
=== WebSockets installed ===
=== Routing configured ===
=== Server ready on port 8080 ===
```

> `:server` 是 root build 的子项目（PR-H3 后并入），无需 `cd server`。

### 2. 客户端怎么连服务端

| 客户端 | 默认连法 | 自定义 |
|--------|---------|--------|
| **Android (`:apps:android`)** | `ws://10.0.2.2:8080/game`（模拟器）<br>`ws://<电脑局域网IP>:8080/game`（真机） | 主菜单 → 多人游戏 → Lobby 顶栏输入框；连接前可改 |
| **Web (`:apps:web`)** | 同源：`ws://<host>/game` 或 `wss://<host>/game`（host=空 时回退 `ws://localhost:8080/game`） | Lobby 顶栏输入框；连接前可改 |
| **未来客户端** | 由各端的 `defaultServerUrl()` 决定，应遵循 [客户端实现指南](client_implementation_guide.md) §3 | 同上，端内提供"自定义 URL"入口 |

> Web 端在浏览器打开后默认走"和当前页同 host"——这意味着部署时 Caddy
> 反代 `/game` 到 `127.0.0.1:8080` 后客户端就能直接连，不需要改源码。
> 详见 [`apps/web/.../viewmodel/AppViewModel.kt`](../apps/web/src/wasmJsMain/kotlin/com/communicationcard/game/web/viewmodel/AppViewModel.kt)
> 的 `defaultServerUrl()`。

### 3. 进入游戏

1. 客户端：输入昵称（首次会提示）
2. 创建房间 → 把房间码（4 位）发给朋友
3. 朋友输入房间码 → 加入 → 点准备
4. 房主点开始；服务端自动用 AI 填到 6 人

---

## 部署到生产服务器

> **不要再手抄旧版"复制 systemd unit + 跑 ufw"流程**——已脚手架化。

完整流程：[`docs/playbooks/web-deploy.md`](playbooks/web-deploy.md)。

要点速览：

- `deploy/install.sh`：Ubuntu 22.04 一次性 bootstrap（apt 装 caddy/JDK17/rsync、
  建 cards 用户 / 各种目录、生成 ed25519 SSH key、装 sudoers 白名单）
- `deploy/Caddyfile`：Caddy 反代 `/game` 到本机 8080；静态文件托管 web 产物；
  含 IP 直连（A 方案）+ HTTPS 域名（B 方案）模板
- `deploy/communication-card-server.service`：systemd unit（cards 用户 / JVM
  调优 / 日志路径 / `ProtectSystem=strict`）
- `.github/workflows/deploy.yml`：push to main 自动 SSH rsync + 重启服务（**opt-in**：
  设 `vars.DEPLOY_ENABLED=true` 才跑）

> **协议层兼容**：升级 server 会断开所有进行中房间。建议挑非黄金时段或在
> playbook 提示玩家退到大厅。`PROTOCOL_VERSION` bump 时老客户端会被服务端
> 拒绝（详见 [架构总览](architecture.md) §六）。

---

## 网络配置

### URL 格式

```
ws://<IP或域名>:<端口>/game        # 明文
wss://<IP或域名>:<端口>/game       # TLS（需 Nginx 反代）
```

### Nginx WSS 反代

```nginx
server {
    listen 443 ssl;
    server_name game.example.com;
    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;

    location /game {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400;
    }
}
```

客户端：`wss://game.example.com/game`。

---

## 游戏流程

### 房间生命周期

```
WAITING ──(房主点开始 + 至少1名真人玩家已准备)──▶ IN_GAME ──(一队全部走完 / 已收 ≥200)──▶ FINISHED
   │                                                 │
   └─ 玩家断线/离开 ─▶ 移除                          └─ 玩家断线 ─▶ 标记 isAISubstitute=true，AI 接管
```

- **WAITING**：可以加入、踢人、添加 AI、改准备状态
- **IN_GAME**：禁止加入/踢人/改准备；玩家显式离开 = AI 接管（不真正删除座位）；断线 = AI 接管
- **FINISHED**：广播 `GameEnd` 后，若所有真人都已离线，房间立即清理

### 开始游戏的最低条件

服务端只要求：**至少 1 名真人 + 所有真人都已准备**（房主自动算准备）。
按钮按下后服务端自动用 AI 填到 6 人，再分牌。

### 房间内的功能

- **准备 / 取消准备**（非房主）—— 状态由服务端广播的 `RoomUpdate` 同步，按钮文本基于服务端真实状态翻转
- **添加 AI**（房主，仅 WAITING）—— 服务端拒绝在 IN_GAME 状态加 AI
- **踢出玩家**（房主，仅 WAITING）—— 同上
- **开始游戏**（房主）—— 状态非 WAITING 时服务端拒绝
- **聊天**（任何阶段）—— 队内/全部两种模式；聊天 `senderId` 使用稳定的 `player.id`，重连后仍能识别本人

### 游戏内界面元素

- 中央"当前出牌"区域：显示最新一手有效牌，字号较大
- 5 个对手插槽（顶部）：显示 `电脑X 张数 已收:N分` + 最近出牌或 PASS
- 底部本机手牌：48×68dp，按炸弹优先 + 点数降序排列；点击选中（向上抬起）
- 状态栏：`轮到你出牌 (Ns)` / `等待 电脑X 出牌`
- 底部按钮：记录 / 离开 / 提示 / 过牌 / 出牌
- 聊天悬浮按钮：右下角，可拖动

---

## 游戏规则要点（联网与单机一致）

### 牌型
- 单张 / 对子 / 三张 / 顺子（5+ 连续无 2/王）/ 炸弹（4+ 同点）

### 比较规则（`canBeat`）
- 同类型同张数：比点数（高者胜）
- 炸弹 vs 非炸弹：炸弹必胜
- 炸弹 vs 炸弹：**先比张数，张数相同再比点数**（5×3 能压 4×10）
- 上家是炸弹时，必须用炸弹才能压

### 计分
- 5 → 5 分；10 → 10 分；K → 10 分；其他牌 → 0 分
- 4 副牌共 216 张牌，总分 400 分
- "已收"：玩家赢得该轮所获得的所有牌的分值之和
- 队伍累计分（实时显示）：本队所有玩家"已收"之和
- 每回合 30 秒；超时由服务端 AI 自动接管出牌

### 结算
（与单机 `SettlementCalculator` 完全一致——见 `settlement_verification.md`）

**全队走完触发**：
- 赢方 = 赢方已收 + 输方未走完玩家(已收 + 手牌分)
- 输方 = 输方已走完玩家已收

**已收 ≥ 200 触发**：
- 双方得分 = 各自已走完玩家已收（未走完玩家的累计分**不算**）

---

## 服务端配置参数

`server/src/main/kotlin/.../ServerGameManager.kt`：

```kotlin
companion object {
    private const val TURN_TIMEOUT_MS = 30_000L   // 回合超时
    private const val AI_DELAY_MS = 1_000L        // AI 出牌前的"思考"延迟
}
```

`server/src/main/kotlin/.../Application.kt`：

```kotlin
embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)   // 心跳
        timeout = Duration.ofSeconds(60)
        maxFrameSize = Long.MAX_VALUE
    }
    ...
}
```

---

## 消息协议

### 房间消息

| 类型 | 方向 | 触发条件 / 备注 |
|------|------|-----------------|
| `room.create` | C→S | session 不能已在房间中 |
| `room.created` | S→C | 创建成功，含 `roomCode`；客户端把 `hostId` 存为 `sessionToken` |
| `room.join` | C→S | session 不能已在房间中 |
| `room.joined` | S→C | 加入成功，含 `playerId`；客户端把 `playerId` 存为 `sessionToken` |
| `room.leave` | C→S | IN_GAME 中离开 = 服务端标 AI 接管，不真正删除；同时清除 `playerToRoom` 映射阻止重连回此房间 |
| `room.update` | S→C | 房间状态变化，每玩家 ready 状态改变也广播 |
| `room.ready` | C→S | 仅 WAITING 接受 |
| `room.start` | C→S | 仅房主、仅 WAITING |
| `room.kick` | C→S | 仅房主、仅 WAITING |
| `room.add_ai` | C→S | 仅房主、仅 WAITING |
| `room.list` | C→S | 列出 WAITING 状态房间 |
| `room.list_result` | S→C | 房间列表 |

### 游戏消息

| 类型 | 方向 | 备注 |
|------|------|------|
| `game.start` | S→C | 含完整初始状态；其他玩家的 `hand` 字段为空 |
| `game.action` | C→S | 出牌或过牌；服务端校验 `playerId == session.seatIndex` |
| `game.action_result` | S→C | 含动作后的最新 `state`；客户端按 `version` 丢弃过期更新 |
| `game.event` | S→C | `cards_played` / `player_passed` / `round_won` / `player_finished` / `turn_start` |
| `game.sync` | S→C | 极端兜底情况（AI 完全无法行动）下推送的状态强同步 |
| `game.turn_timeout` | S→C | 超时通知（伴随 AI 自动接管） |
| `game.end` | S→C | 含最终结算结果 |

### 系统消息

| 类型 | 方向 | 备注 |
|------|------|------|
| `sys.heartbeat` | 双向 | 客户端每 15 秒发一次 |
| `sys.reconnect` | C→S | 携带 `sessionToken`；服务端按 `playerToRoom[token]` 查找原房间 |
| `sys.reconnect_success` | S→C | 重连成功；包含游戏状态（若 IN_GAME），客户端 `GameSyncManager` 据此恢复 |
| `sys.error` | S→C | 错误码 + 文案 |
| `sys.player_disconnected` | S→C | 玩家断线广播给其他人 |
| `sys.player_reconnected` | S→C | 玩家重连广播；`excludeId = player.id` 不发给本人 |

---

## 断线重连

### 触发与流程

1. WebSocket 异常关闭（非 code=1000）
2. `NetworkManager.handleDisconnection` 启动 `attemptReconnect`，指数退避 2s/4s/8s/16s/32s（最多 5 次）
3. 每次重连建立新 WebSocket → `onOpen` 中**自动发送** `Reconnect(sessionToken)`
4. 服务端 `handleReconnect`：
   - 查找 `playerToRoom[token]` → 找不到则回 `sys.error 404`
   - 找到则更新 `player.session` 为新连接，`player.isAISubstitute = false`
   - 回 `ReconnectSuccess(state)` + `RoomUpdate(roomInfo)`，并广播 `PlayerReconnected`
5. 客户端 `GameSyncManager` 收到 `ReconnectSuccess` 后用 `applyState` 恢复游戏状态
6. 若手牌内容有变（AI 在断线期间替你出过牌），`StateRefresh` 触发 `updatePlayerHand` 重建底部手牌

### 主动离开 vs 网络断开

- **主动离开**（点击离开按钮）：客户端发 `LeaveRoom` 后立即清空 `sessionToken`；服务端清掉 `playerToRoom` 映射 → 之后即便连接断了也无法用旧 token 重连回此房间
- **网络断开**（连接异常）：`sessionToken` 保留 → 自动重连

---

## AI 行为说明

### 自由出牌（无上家）
- 优先小对子 → 小三张 → 最小单张
- 出单张时尽量避开炸弹（4+ 同点的牌组）

### 压牌
- 同类型优先：用 `compareBy(size, rank)` 选最便宜的（同张数下选最小够压的点数；不同张数选张数小的）
- 上家是炸弹时：自动选最便宜的更大炸弹（先比张数，再比点数）
- 上家不是炸弹时，是否拆炸弹用以下策略：
  - 手牌 ≤ 10 张：**主动拆**（拼速度）
  - 上家点数 ≥ TEN：**用炸弹**（压大牌划算）
  - 上家本身是炸弹：**必须用炸弹**
  - 自己的最小炸弹是 4×3/4×4/4×5（小炸弹）：**用掉**
  - 否则：过牌保留

### 容错回退（处理边界情况）

服务端 `processAITurn` 设三级回退，**保证游戏永不卡死**：

1. AI 决策的首选动作 → `handlePlayCards/Pass`
2. 若失败：尝试过牌（仅当上家有牌）
3. 若仍失败：尝试出最小单张
4. 若**全部失败**：`broadcastForceAdvance` 强制推进回合并广播 `GameSync` + `TurnStart`

---

## UI 功能说明

### 游戏记录
- 点击"记录"按钮查看历史，每页两栏共 30 行，可上一页/下一页

### 牌面尺寸

| 位置 | 尺寸 | 说明 |
|------|------|------|
| 中央当前出牌 | 38×54dp | 大尺寸，醒目 |
| 对手出牌区 | 32×46dp | 中等 |
| 玩家手牌 | 48×68dp | 可点击，选中后向上抬起 16dp |

### 回合倒计时
- 每回合 30 秒
- 超时由 AI 接管出牌（仅本回合，下回合恢复）
- 状态栏：`轮到你出牌 (25s)`

### 提示按钮
- 计算所有合法出牌，选最小一组（按张数、点数）
- 提示生成基于客户端 `CardRules`，与服务端 `canBeat` 一致

### 聊天
- 文字消息（200 字）
- 4 个快捷消息按钮：好牌！/ 要不起 / 队友上！/ GG
- 队内消息只发给同队队友
- 未读数显示在右下角红点上

---

## 故障排查

### 连接失败

```bash
# 1. 服务起着吗？（生产服务器）
systemctl status communication-card-server

# 2. 端口监听了吗？
ss -tlnp | grep 8080

# 3. 防火墙开了吗？
ufw status                              # 主机层
# 云控制台还要确认安全组（参考 docs/playbooks/web-deploy.md 第二层防火墙）

# 4. 客户端能连吗？
curl -I http://<服务器IP>/                  # Caddy 应返 200
curl -I http://<服务器IP>/game              # WS 上线前 Caddy 通常返 426/400 也算正常
```

更详尽的连不上排错路径见 [`docs/playbooks/web-deploy.md`](playbooks/web-deploy.md) §排错。

### 游戏看似卡住（服务端 AI 不出牌）

**最常见原因：客户端连了一台旧版服务**。先确认服务端是最新代码：

```bash
ssh cards@<host> 'sudo systemctl status communication-card-server'
ssh cards@<host> 'sudo journalctl -u communication-card-server --since "5 min ago" | grep -i AI'
```

最新版本的服务端在每次 AI 失败时都会输出日志：
- `AI action failed for seat X: ...` —— 第 1 步首选失败
- `AI fallback also failed for seat X: ...` —— 三级回退也失败（极罕见）
- `force-advance` 之后的 GameSync 应该立刻把游戏推进到下一回合

### 频繁断线

- 检查客户端 `NetworkManager` 的 ping 间隔（30s）与服务端 `pingPeriod`（15s）匹配
- 服务器侧的 Nginx/反代 `proxy_read_timeout` 至少设到 86400
- 真机弱网情况下，客户端会自动重连（最多 5 次，指数退避）

### 已收/分数显示异常

如果"已收"全是 0 但队伍分有变化 → 服务端是旧版（`8a56e14` 之前），需要部署新代码。新版服务端：
- `getStateForPlayer` 返回 `state.playerScores[seat]`
- `handleRoundEnd` 把回合得分同时累加到 `state.playerScores[winner]` 与队伍总分

### 客户端看到的状态错乱

打开调试日志：
```kotlin
// DebugLogManager 默认开启，日志写到 /data/data/.../files/debug_logs.txt
adb shell run-as com.communicationcard.game cat files/debug_logs.txt
# 或在 APP 内"主菜单 → 设置 → 调试日志"查看
```

关键日志：
- `applyState`：状态应用是否被版本号校验拦截
- `isMyTurn / GameActionResult`：本地座位号与服务端 currentPlayerIndex 是否一致

---

## 常见问题（FAQ）

**Q: 我离开了一局游戏，重连后被拽回去了？**
A: 升级到含 `e8927e8` 的版本。客户端 `RoomManager.leaveRoom` 现在会清空 `sessionToken`，服务端 `handleLeaveRoom` 会清除 `playerToRoom` 映射，之后无法用旧 token 重连回原房间。

**Q: 4×10 的炸弹被电脑拒绝了什么的？**
A: 升级到含 `fb6cc7c` 的版本。修复了 `canBeat` 在炸弹张数不同时的错误判断（5×3 现在能压 4×10）。

**Q: 房间里只有 1 个人能开始游戏吗？**
A: 可以，服务端会自动用 AI 填到 6 人。

**Q: 玩家断线后他的牌怎么办？**
A: 服务端把他标为 `isAISubstitute`，AI 用他的手牌继续打；他若重连过来，立即恢复控制（`isAISubstitute = false`）。

**Q: 重连成功但状态还是旧的？**
A: 升级到含 `8a56e14` 的版本。`NetworkManager.handleMessage` 现在会把 `ReconnectSuccess` 转发给 `GameSyncManager`，`GameSyncManager` 会 `applyState` 恢复完整游戏状态。

---

## 关键文件索引

### 跨平台共享层（`:shared`）—— 所有客户端共用

| 路径 | 角色 |
|------|------|
| `shared/src/commonMain/.../network/GameMessage.kt` | 协议 DTO + `PROTOCOL_VERSION`，**所有客户端依赖** |
| `shared/src/commonMain/.../engine/CardRules.kt` | 牌型识别 + `canBeat`（与服务端 `canBeat` 必须等价） |
| `shared/src/commonMain/.../engine/SettlementCalculator.kt` | 结算公式（与服务端 `computeAllFinishedScores` 等价） |
| `shared/src/commonMain/.../ai/AIPlayer.kt` | AI 决策；服务端、Web 单机模式都用同一份 |

### 服务端（`:server`）

| 路径 | 角色 |
|------|------|
| `server/src/main/kotlin/.../Application.kt` | WebSocket 路由、消息分发、`handleReconnect`/版本握手 |
| `server/src/main/kotlin/.../ServerRoomManager.kt` | 房间生命周期 |
| `server/src/main/kotlin/.../ServerGameManager.kt` | 游戏逻辑 / AI / 计时器 / `mutexFor(room)` |
| `server/src/main/kotlin/.../Messages.kt` | 服务端独有的握手 / 错误消息（业务消息已用 `:shared` 的 `GameMessage`） |

### Android 客户端（`:apps:android`）

| 路径 | 角色 |
|------|------|
| `apps/android/.../network/NetworkManager.kt` | WebSocket 连接 / 心跳 / 重连 |
| `apps/android/.../network/RoomManager.kt` | 房间状态流 |
| `apps/android/.../network/GameSyncManager.kt` | 游戏状态流 + 回合计时 |
| `apps/android/.../engine/MultiplayerGameEngine.kt` | 网络版引擎适配器 |
| `apps/android/.../ui/multiplayer/LobbyActivity.kt` | 大厅 |
| `apps/android/.../ui/multiplayer/RoomActivity.kt` | 房间 |
| `apps/android/.../ui/multiplayer/OnlineGameActivity.kt` | 联网游戏界面 |

### Web 客户端（`:apps:web`）

| 路径 | 角色 |
|------|------|
| `apps/web/.../web/Main.kt` | `ComposeViewport(document.body)` 入口 + #loader 移除 |
| `apps/web/.../web/viewmodel/AppViewModel.kt` | 状态机 + `defaultServerUrl()` + ws 连接 |
| `apps/web/.../web/net/WebSocketTransport.kt` | 浏览器 WS 包装（`@JsFun` interop） |
| `apps/web/.../web/singleplayer/SinglePlayerEngine.kt` | 单机模式（包装 `:shared` `GameEngine`） |
| `apps/web/.../web/ui/{Home,Lobby,Room,Game,Settlement}Screen.kt` | 5 屏 Compose UI |

### 新客户端（iOS / Desktop / CLI / 其他）

参考 [`docs/client_implementation_guide.md`](client_implementation_guide.md) +
[`docs/feature_spec.md`](feature_spec.md)。所有新端必须复用 `:shared` 的协议
DTO 与 `PROTOCOL_VERSION`，否则握手失败。

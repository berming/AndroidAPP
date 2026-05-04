# 沟通牌 - 多人游戏完整指南

## 快速开始

### 1. 启动服务器

```bash
# 进入服务器目录
cd server

# 启动服务器 (首次运行会下载依赖)
./gradlew run
```

成功启动后显示:
```
Communication Card Server started on port 8080
Application started in X.XXX seconds.
Responding at http://0.0.0.0:8080
```

### 2. 配置客户端连接地址

修改 `LobbyActivity.kt` 中的服务器地址:

```kotlin
// 文件: apps/communication-card/src/main/java/com/communicationcard/game/ui/multiplayer/LobbyActivity.kt

companion object {
    // 根据实际情况修改:
    
    // 本地开发 - Android模拟器连接本机
    private const val SERVER_URL = "ws://10.0.2.2:8080/game"
    
    // 本地开发 - 真机通过WiFi连接 (替换为电脑IP)
    // private const val SERVER_URL = "ws://192.168.1.100:8080/game"
    
    // 生产环境 - 云服务器
    // private const val SERVER_URL = "ws://your-server.com:8080/game"
}
```

### 3. 运行游戏

1. 安装APK到手机/模拟器
2. 点击"多人游戏"
3. 输入昵称
4. 创建房间或输入房间码加入

---

## 服务器部署

### 方式一: 本地运行 (开发测试)

**环境要求:**
- JDK 17 或更高版本
- Gradle 8.x (项目自带wrapper)

```bash
cd server
./gradlew run
```

**Android模拟器连接:** `ws://10.0.2.2:8080/game`  
**真机连接:** `ws://<电脑局域网IP>:8080/game`

查看电脑IP:
- Windows: `ipconfig`
- Mac/Linux: `ifconfig` 或 `ip addr`

### 方式二: 云服务器部署 (生产环境)

#### 步骤1: 准备服务器

推荐配置:
- 系统: Ubuntu 20.04+
- 内存: 1GB+
- CPU: 1核+
- 开放端口: 8080

```bash
# 安装JDK
sudo apt update
sudo apt install openjdk-17-jdk -y

# 验证安装
java -version
```

#### 步骤2: 上传服务器代码

```bash
# 在本地打包
cd server
./gradlew build

# 上传到服务器 (使用scp或其他方式)
scp -r server/ user@your-server:/home/user/
```

#### 步骤3: 启动服务

```bash
# SSH到服务器
ssh user@your-server

# 进入目录并启动
cd server
./gradlew run
```

#### 步骤4: 后台运行 (可选)

使用 `screen` 或 `systemd` 保持服务运行:

```bash
# 使用screen
screen -S card-server
./gradlew run
# 按 Ctrl+A, D 分离

# 重新连接
screen -r card-server
```

或创建systemd服务:

```bash
sudo nano /etc/systemd/system/card-server.service
```

```ini
[Unit]
Description=Communication Card Game Server
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu/server
ExecStart=/home/ubuntu/server/gradlew run
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable card-server
sudo systemctl start card-server
sudo systemctl status card-server
```

#### 步骤5: 防火墙配置

```bash
# Ubuntu UFW
sudo ufw allow 8080/tcp

# 或者 iptables
sudo iptables -A INPUT -p tcp --dport 8080 -j ACCEPT
```

云服务器还需在控制台安全组中开放8080端口。

### 腾讯云 Ubuntu 22.04 LTS 快速部署

完整的一键部署命令（SSH登录后按顺序执行）：

```bash
# 1. 系统更新和 JDK 安装
apt update && apt upgrade -y
apt install -y openjdk-17-jdk git

# 2. 克隆代码
cd /opt
git clone https://github.com/你的用户名/AndroidAPP.git
cd AndroidAPP/server
chmod +x gradlew

# 3. 构建测试
./gradlew build

# 4. 配置防火墙
ufw allow 8080/tcp
ufw allow 22/tcp
ufw --force enable

# 5. 创建服务
cat > /etc/systemd/system/communication-card.service << 'EOF'
[Unit]
Description=Communication Card Game Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/AndroidAPP/server
ExecStart=/opt/AndroidAPP/server/gradlew run --no-daemon
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# 6. 启动服务
systemctl daemon-reload
systemctl enable communication-card
systemctl start communication-card

# 7. 验证
systemctl status communication-card
curl http://localhost:8080/
```

**别忘了在腾讯云控制台安全组中开放 8080 端口！**

服务管理命令：
```bash
systemctl start communication-card    # 启动
systemctl stop communication-card     # 停止
systemctl restart communication-card  # 重启
journalctl -u communication-card -f   # 查看日志
```

---

## 网络配置

### 连接地址格式

```
ws://<IP或域名>:<端口>/game
```

### 不同场景的配置

| 场景 | 服务器位置 | 客户端地址 |
|------|-----------|-----------|
| 模拟器测试 | 本机 | `ws://10.0.2.2:8080/game` |
| 真机局域网 | 本机 | `ws://192.168.x.x:8080/game` |
| 真机公网 | 云服务器 | `ws://公网IP:8080/game` |
| 正式环境 | 云服务器+域名 | `ws://game.example.com:8080/game` |

### WSS (安全连接) 配置

生产环境建议使用HTTPS/WSS。可通过Nginx反向代理:

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

客户端连接: `wss://game.example.com/game`

---

## 游戏流程详解

### 创建房间

1. 进入"多人游戏"
2. 输入昵称 (最多12字符)
3. 点击"创建房间"
4. 获得6位房间码 (如: `ABC123`)
5. 分享房间码给朋友

### 加入房间

1. 进入"多人游戏"  
2. 输入昵称
3. 输入房间码
4. 点击"加入"

### 房间等待

- 玩家自动分配到红队/蓝队
- 点击"准备"表示就绪
- 房主可以:
  - 点击"添加AI"填充空位
  - 踢出玩家
  - 所有人准备后点击"开始游戏"

### 游戏中

- 界面与单机模式相同
- 增加:
  - 回合倒计时 (30秒)
  - 当前玩家高亮
  - 聊天按钮 (右下角)
  - 断线重连提示

### 聊天功能

点击聊天按钮打开面板:
- 输入文字发送
- 快捷消息一键发送
- 队内消息仅队友可见

---

## 服务器配置参数

### 端口修改

`server/src/main/kotlin/.../Application.kt`:

```kotlin
embeddedServer(Netty, port = 8080) {  // 修改端口
    ...
}
```

### 游戏参数

`server/src/main/kotlin/.../ServerGameManager.kt`:

```kotlin
companion object {
    const val TURN_TIMEOUT_MS = 30_000L  // 回合超时时间
    const val AI_DELAY_MS = 1_000L       // AI出牌延迟
}
```

### 心跳配置

`server/src/main/kotlin/.../Application.kt`:

```kotlin
install(WebSockets) {
    pingPeriod = Duration.ofSeconds(15)   // 心跳间隔
    timeout = Duration.ofSeconds(60)       // 超时时间
}
```

---

## 故障排查

### 连接失败

1. **检查服务器是否启动**
   ```bash
   curl http://server-ip:8080
   # 应返回 "Communication Card Server"
   ```

2. **检查端口是否开放**
   ```bash
   telnet server-ip 8080
   ```

3. **检查防火墙**
   - 本机防火墙
   - 云服务器安全组

4. **检查客户端地址配置**
   - 确认IP正确
   - 确认协议是 `ws://` 不是 `http://`

### 频繁断线

1. 检查网络稳定性
2. 检查心跳配置
3. 查看服务器日志

### 游戏不同步

1. 检查服务器日志中的错误
2. 尝试重新进入房间
3. 确保客户端版本一致

---

## 消息协议参考

### 房间消息

| 类型 | 方向 | 说明 |
|------|------|------|
| `room.create` | C→S | 创建房间 |
| `room.created` | S→C | 房间创建成功，返回房间码 |
| `room.join` | C→S | 加入房间 |
| `room.joined` | S→C | 加入成功 |
| `room.leave` | C→S | 离开房间 |
| `room.update` | S→C | 房间状态更新 |
| `room.ready` | C→S | 准备/取消准备 |
| `room.start` | C→S | 开始游戏（仅房主） |
| `room.add_ai` | C→S | 添加AI玩家（仅房主） |

### 游戏消息

| 类型 | 方向 | 说明 |
|------|------|------|
| `game.start` | S→C | 游戏开始，包含初始手牌 |
| `game.action` | C→S | 玩家动作（出牌/过牌） |
| `game.event` | S→C | 游戏事件广播 |
| `game.sync` | S→C | 完整状态同步 |
| `game.end` | S→C | 游戏结束 |

### 系统消息

| 类型 | 方向 | 说明 |
|------|------|------|
| `sys.heartbeat` | 双向 | 心跳保活 |
| `sys.error` | S→C | 错误消息 |
| `sys.reconnect` | C→S | 请求重连 |

---

## 文件结构

```
server/                              # 服务器
├── build.gradle.kts                 # 依赖配置
├── gradlew                          # Gradle Wrapper
└── src/main/kotlin/.../
    ├── Application.kt               # 主入口，WebSocket路由
    ├── Messages.kt                  # 消息协议定义
    ├── GameSession.kt               # 会话封装
    ├── ServerRoomManager.kt         # 房间管理
    └── ServerGameManager.kt         # 游戏逻辑

apps/communication-card/             # Android客户端
└── src/main/java/.../
    ├── network/
    │   ├── NetworkManager.kt        # WebSocket连接
    │   ├── RoomManager.kt           # 房间状态
    │   ├── GameSyncManager.kt       # 游戏同步
    │   ├── TextChatManager.kt       # 聊天
    │   └── GameMessage.kt           # 消息协议
    ├── engine/
    │   └── MultiplayerGameEngine.kt # 多人引擎
    └── ui/multiplayer/
        ├── LobbyActivity.kt         # 大厅
        ├── RoomActivity.kt          # 房间
        ├── OnlineGameActivity.kt    # 游戏
        └── ChatAdapter.kt           # 聊天适配器
```

---

## 常见问题排查

### 连接失败

1. **检查服务器是否运行**
   ```bash
   systemctl status communication-card
   # 或
   ps aux | grep java
   ```

2. **检查端口是否监听**
   ```bash
   netstat -tlnp | grep 8080
   # 或
   ss -tlnp | grep 8080
   ```

3. **检查防火墙**
   ```bash
   # Ubuntu 防火墙
   ufw status
   
   # 腾讯云安全组
   # 登录控制台 → 云服务器 → 安全组 → 检查入站规则
   ```

4. **测试网络连通性**
   ```bash
   # 在手机或电脑上
   telnet 你的服务器IP 8080
   ```

### 服务启动失败

1. **查看错误日志**
   ```bash
   journalctl -u communication-card -n 50
   ```

2. **手动运行查看报错**
   ```bash
   cd /opt/AndroidAPP/server
   ./gradlew run
   ```

3. **检查 Java 版本**
   ```bash
   java -version
   # 需要 Java 17 或更高
   ```

4. **检查端口占用**
   ```bash
   lsof -i :8080
   # 如有其他进程占用，先停止或改用其他端口
   ```

### 修改服务器端口

如需更改端口（如改为 8888），编辑 `server/src/main/kotlin/.../Application.kt`：

```kotlin
embeddedServer(Netty, port = 8888, host = "0.0.0.0") {
    // ...
}
```

同时更新客户端 `LobbyActivity.kt` 中的端口号。

### 性能优化（可选）

```bash
# 增加文件描述符限制
echo '* soft nofile 65535' >> /etc/security/limits.conf
echo '* hard nofile 65535' >> /etc/security/limits.conf

# 优化 JVM 内存（在服务文件中添加）
# ExecStart=... -Xms256m -Xmx512m
```

## 注意事项

1. **网络要求**: 客户端和服务器需在同一网络或服务器有公网IP
2. **最低人数**: 支持1名真人玩家 + AI 即可开始
3. **最大人数**: 支持最多6名玩家（真人+AI）
4. **断线处理**: 断线玩家由AI接管，重连后恢复控制
5. **版本兼容**: 确保所有客户端版本一致
6. **安全建议**: 生产环境建议配置 HTTPS/WSS 和域名

---

## AI 行为说明

### 出牌策略

**自由出牌时（没有上家）：**
- 优先出对子、三张（消耗手牌更快）
- 出单张时避免拆炸弹

**压牌时：**
- 优先用同类型牌压（单张压单张，对子压对子）
- 只在以下情况使用炸弹：
  - 手牌少于 10 张（需要抢先出完）
  - 上家牌大（10 及以上的牌）
  - 上家是炸弹（必须用炸弹压）
  - 炸弹很小（4 张 3/4/5，价值不高）
- 否则选择过牌，保留炸弹

---

## UI 功能说明

### 游戏记录

- 点击"记录"按钮查看游戏历史
- 两栏分页显示，每页 30 行
- 使用"上一页"/"下一页"翻页

### 牌面显示

| 位置 | 尺寸 | 说明 |
|------|------|------|
| 中央当前出牌 | 38×54dp | 大尺寸，清晰显示 |
| 对手出牌区 | 32×46dp | 中等尺寸 |
| 玩家手牌 | 48×68dp | 可点击选择 |

### 回合倒计时

- 每回合 30 秒
- 超时自动由 AI 接管出牌
- 显示在状态栏：`轮到你出牌 (25s)`

---

## 已知问题与调试

### 状态同步问题

如果遇到按钮无法点击或状态异常：

1. **查看 logcat 日志**
   ```bash
   adb logcat | grep "DEBUG"
   ```

2. **关键日志**
   - `DEBUG isMyTurn`: 显示当前玩家索引和本地座位号
   - `DEBUG GameActionResult`: 显示状态更新时的回合信息

3. **排查步骤**
   - 确认 `currentPlayerIndex` 和 `localSeatIndex` 是否匹配
   - 检查网络连接是否稳定
   - 尝试重新进入房间

### 网络断线

- 断线后会显示"重连中..."
- 自动尝试重连
- 重连成功后恢复游戏状态

---

## 更新日志

### 2024-05 版本

**新功能：**
- 支持 1 人 + 5 AI 开始游戏
- 游戏记录两栏分页显示
- 台面牌面尺寸增大

**AI 优化：**
- 不再用大炸弹压小牌
- 优先出对子/三张清手牌
- 保留炸弹应对关键时刻

**Bug 修复：**
- 修复出牌后手牌不更新的问题
- 修复回合指示器不刷新的问题
- 修复选牌后按钮禁用的问题
- 添加 TurnStart 事件广播

**架构改进：**
- 移除状态缓存，直接读取最新状态
- 添加 StateRefresh 事件触发 UI 更新
- 优化消息处理顺序

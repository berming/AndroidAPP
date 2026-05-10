# Playbook · Web + Server 部署到自有服务器

> 适用：腾讯云 / 阿里云 / 任何 Ubuntu 22.04 LTS。
> 拓扑：Caddy 在 80/443 反代 → 本机 :server (Ktor) 8080。
> 自动化：push 到 `main` → GitHub Actions 自动 SSH rsync + 重启。

---

## 一次性：服务器初始化

### 1. 在服务器上跑 install.sh

```bash
ssh ubuntu@<your-server-ip>     # 或者你的常用账号
sudo apt update && sudo apt install -y wget
wget https://raw.githubusercontent.com/berming/AndroidAPP/main/deploy/install.sh
sudo bash install.sh
```

脚本会：
1. 装 Caddy + JDK 17 + rsync
2. 创建 `cards` 系统用户、目录布局
3. 部署 `Caddyfile` + `communication-card-server.service`
4. 给 cards 用户加白名单 sudo（仅 systemctl restart / reload）
5. 生成专用 SSH ed25519 key 并自动加入 `~cards/.ssh/authorized_keys`
6. 在终端打印**私钥 + 服务器 IP** 让你下一步填进 GitHub Secrets

### 2. 编辑 Caddyfile（选 A 或 B）

```bash
sudo vim /etc/caddy/Caddyfile
```

- **方案 A（纯 IP，HTTP）**：保留 `:80 { ... }` 段，删掉下面注释起来的域名段
- **方案 B（域名 + HTTPS）**：把 `cards.example.com` 改成你的域名，把 `:80` 段删掉

```bash
sudo systemctl reload caddy
sudo systemctl status caddy --no-pager
```

### 3. 腾讯云安全组

进腾讯云控制台 → 云服务器 → 安全组：
- 放行 **22**（SSH）/ **80**（HTTP）/ **443**（HTTPS）
- **关闭 8080**（只在本机 127.0.0.1 用，不能对外）

### 4. 在 GitHub repo 添加 3 个 Secret + 1 个 Variable

`Settings → Secrets and variables → Actions`

**Secrets**（New repository secret）：

| Name | Value |
|---|---|
| `DEPLOY_SSH_HOST` | 服务器公网 IP 或域名 |
| `DEPLOY_SSH_USER` | `cards` |
| `DEPLOY_SSH_KEY` | `install.sh` 末尾打印的私钥（含 `-----BEGIN/END-----` 两行） |

**Variables**（同一页 → Variables tab → New repository variable）：

| Name | Value |
|---|---|
| `DEPLOY_ENABLED` | `true` |

> ⚠️ `DEPLOY_ENABLED=true` 是 **opt-in 开关**：没设 / 不为 `true` 时 deploy workflow 直接 skip。
> 这样保证 Secrets 配齐前 main 上的 push 不会触发失败的部署。

---

## 触发首次部署

打开 GitHub Actions 页面 → 选 `Deploy to server` workflow → 点 **Run workflow** 按钮（branch: main）。

> 注：**不要**用 `git commit --allow-empty` —— `deploy.yml` 的 `paths:` 过滤器只在
> `apps/web/**` / `shared/**` / `server/**` / `deploy/**` 等路径有改动时才触发 push 事件，
> 空 commit 不命中任何路径，workflow 不会被触发。`workflow_dispatch` 是绕过 paths 的唯一方式。

---

## 验证

| 检查 | 命令 / 操作 | 预期 |
|---|---|---|
| Server 进程在跑 | `ssh cards@<host> 'systemctl status communication-card-server'` | active (running) |
| Server 日志 | `ssh cards@<host> 'journalctl -u communication-card-server -n 50'` | `Server ready on port 8080` |
| 静态首页 | `curl -I http://<host>/` | 200 |
| wasm MIME | `curl -I http://<host>/communicationCardWeb.wasm` | `Content-Type: application/wasm` |
| 浏览器打开 | `http://<host>/` | loader 淡出 → Home 页 |
| 联网模式连得上 | Lobby → 输入框默认 `ws://<host>/game`（无 :8080）→ 点连接 | 圆点变绿 |

---

## 后续日常

每次推 main：
- `apps/web/**` / `shared/**` / `server/**` / `deploy/**` 改动 → Actions 自动跑 `Deploy to server`
- ~5 分钟内浏览器刷新可见

如果只在 PR 分支改动，**不会**触发部署 —— 部署只跟 `main` 走。

---

## 排错

### 浏览器一直转圈

```bash
# 1. 静态文件是否服务到位
curl -I http://<host>/communicationCardWeb.wasm
# 应 200 + application/wasm
```

```bash
# 2. Caddy 日志
sudo journalctl -u caddy -n 50
sudo tail -f /var/log/caddy/communication-card.log
```

### 联网模式连不上

```bash
# 1. server 进程
sudo systemctl status communication-card-server

# 2. /game 反代是否通
curl -i -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Key: x" -H "Sec-WebSocket-Version: 13" \
     http://<host>/game
# 应该返回 101 Switching Protocols（不是 4xx）
```

```bash
# 3. server 日志
sudo journalctl -u communication-card-server -f
# 在浏览器点连接，应看到 "WebSocket /game connected: <sessionId>"
```

### GitHub Actions 部署失败

- **Permission denied (publickey)**：`DEPLOY_SSH_KEY` 复制不全，确保含开头 `-----BEGIN OPENSSH PRIVATE KEY-----` 和结尾 `-----END...-----`
- **rsync: connection unexpectedly closed**：`DEPLOY_SSH_USER` 应是 `cards`（不是 `root` / `ubuntu`）
- **sudo: a password is required**：install.sh 第 5 步的 sudoers 条目没生效，重跑：
  ```bash
  sudo cat /etc/sudoers.d/communication-card-deploy
  # 应包含 cards ALL=(root) NOPASSWD: ...
  ```

### CI 日志看不到详细错？

在 PR 评论里看 `:apps:web:wasmJsBrowserDistribution` 的 log（CI 自动 exfil 模式，
详见 `docs/playbooks/ci-failure-triage.md`）。

---

## 升级路径

| 想做 | 怎么做 |
|---|---|
| 加 HTTPS | DNS 解析到服务器 → 编辑 Caddyfile 切方案 B → reload caddy（Caddy 自动申请 LE 证书） |
| 多副本 | 不在本 playbook 范围；个人项目过度设计 |
| 对外开 8080 直连（弃用反代）| 编辑 `apps/web/.../AppViewModel.kt::defaultServerUrl`，恢复 `:8080`；Caddyfile 删掉 `/game` 反代段；安全组开 8080 |
| 回滚 | `ssh cards@host 'cd /opt/... && git ...'` 不适用（没 git）；用 GitHub Actions Re-run 上一个绿的 deploy |

---

## 已经跑过旧版 install.sh（PR #41 合并版本）的迁移

如果你已经跑过 commit `699d94b` 那版的 `install.sh`，需要在服务器上手动 fix
3 处问题（PR #41 review 发现的 P0/P1）：

```bash
ssh ubuntu@<host>          # 你的常用账号（不是 cards）

# ① 拉新版 Caddyfile（修了 try_files 破坏 ws 反代的 P0）
sudo curl -fsSL https://raw.githubusercontent.com/berming/AndroidAPP/main/deploy/Caddyfile \
    -o /etc/caddy/Caddyfile
sudo vim /etc/caddy/Caddyfile     # 重新选 A/B 方案
sudo systemctl reload caddy

# ② 重写 sudoers（修了 /bin/systemctl 路径不匹配的 P1）
SYSCTL=$(command -v systemctl)
sudo tee /etc/sudoers.d/communication-card-deploy >/dev/null <<EOF
cards ALL=(root) NOPASSWD: $SYSCTL restart communication-card-server, $SYSCTL reload caddy, $SYSCTL status communication-card-server
EOF
sudo chmod 440 /etc/sudoers.d/communication-card-deploy
sudo visudo -cf /etc/sudoers.d/communication-card-deploy

# ③ 拉新版 systemd unit（JAVA_OPTS → SERVER_OPTS + network-online）
sudo curl -fsSL https://raw.githubusercontent.com/berming/AndroidAPP/main/deploy/communication-card-server.service \
    -o /etc/systemd/system/communication-card-server.service
sudo systemctl daemon-reload
sudo systemctl restart communication-card-server   # 已部署过 server 的话；首次部署可跳过

# ④ GitHub repo 加 Variable：DEPLOY_ENABLED=true（新版 deploy.yml 改成 opt-in 了）
#    Settings → Secrets and variables → Actions → Variables → New
```

验证 ws 反代修好了：
```bash
curl -i -H "Connection: Upgrade" -H "Upgrade: websocket" \
     -H "Sec-WebSocket-Key: x" -H "Sec-WebSocket-Version: 13" \
     http://<host>/game
# 应返回 101 Switching Protocols（旧版会返回 200 + index.html）
```

---

## 安全注记

- `cards` 用户 sudo 仅限 `systemctl restart communication-card-server` / `reload caddy` / `status` 三条 —— 即使私钥泄漏也无法 root
- SSH key 是部署专用 ed25519，跟你日常的 SSH key 物理隔离
- `:server` 监听 127.0.0.1 之外的 0.0.0.0:8080 —— 配合腾讯云安全组关闭 8080，外网不可达
- Caddy 自动 HTTPS 走 ACME http-01，需要 80 端口可达

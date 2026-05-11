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

- **方案 A（纯 IP，HTTP）**：删掉默认的 `bermin.cn` 段，把 `:80 {}` 内的 `redir https://bermin.cn{uri}` 改回 `file_server`（参考 git 历史 `60ca617` 之前的版本）
- **方案 B（域名 + HTTPS，当前默认）**：仓库 `deploy/Caddyfile` 已用 `bermin.cn`（2026-05 切换）。要换成自己的域名，把所有 `bermin.cn` 替换即可；Caddy 自动申请并续期 Let's Encrypt 证书

```bash
sudo systemctl reload caddy
sudo systemctl status caddy --no-pager
```

### 3. 防火墙（两层都要配，缺一不可）

> 历史教训：这一步**已踩过 2 次坑**。两层防火墙互不知道对方存在；
> 只配一层另一层照样拦，表现都是"公网 timeout"。

#### 3a. ufw（host 层；新版 install.sh **已自动配好**）

`install.sh` 第 5/7 步会自动把 ufw 配成 expected state：

| 端口 | 状态 | 备注 |
|---|---|---|
| 22/tcp | allow | SSH |
| 80/tcp | allow | HTTP（Caddy） |
| 443/tcp | allow | HTTPS（Caddy） |
| 8080/tcp | **deny** | 应仅 127.0.0.1 用，**对外开就是漏洞面** |

如果你跑过旧版 install.sh，自检并修正：

```bash
sudo ufw status verbose
# 期望 80/tcp + 443/tcp ALLOW，8080/tcp 不在 allow 列表
# 修正：
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw delete allow 8080/tcp 2>/dev/null || true
sudo ufw delete allow 8080/tcp 2>/dev/null || true   # v6 那条再删一次
sudo ufw --force enable
```

> ⚠️ **先 allow 22 再 enable**，否则 SSH 立刻断，下次连不上。

#### 3b. 云厂商安全组（网络边界层；**必须手动配**）

ufw 在 server 内部，云厂商安全组在 server **进入网络之前**的边界。
两者完全独立，腾讯云就算 ufw 全开放，安全组没放行公网照样不通。

**腾讯云**（其他云厂商等同）：

```
控制台 → 云服务器 → 实例 → 行尾 "更多" → "安全组" → 当前安全组
→ 修改规则 → 入站规则 → 新增：
```

| 来源 | 协议端口 | 策略 | 备注 |
|---|---|---|---|
| `0.0.0.0/0` | TCP:22 | 允许 | SSH |
| `0.0.0.0/0` | TCP:80 | 允许 | HTTP |
| `0.0.0.0/0` | TCP:443 | 允许 | HTTPS（未来切方案 B 用） |

并删除任何对外开放 **8080** 的旧规则（早期开发可能加过）。

#### 3c. 自检：定位是哪一层在拦

按这个**顺序**测，能 0 歧义指出问题层：

```bash
# 1) 在服务器内部，绕过两层防火墙，直接打 Caddy
ssh ubuntu@<host>
curl -I --max-time 5 http://127.0.0.1/
# 通 → Caddy + Caddyfile OK，进下一层
# 不通 → 不是防火墙问题；看 Caddy: sudo systemctl status caddy

# 2) 仍在服务器内部，但走公网 IP（会被 ufw 检查；NAT loop 不经云安全组）
curl -I --max-time 5 http://<public-ip>/
# 通 → ufw OK，剩云安全组
# 不通 → ufw 在拦；sudo ufw status verbose 看规则

# 3) 你的本机笔记本（不 SSH 进服务器）
curl -I --max-time 5 http://<public-ip>/
# 通 → 全部 OK
# 不通 → 云安全组在拦（最常见，约 80% 的"timeout"都是这层）
```

每一步都通 → 进入下一节"GitHub repo 配置"。
某一步不通 → 在该步对应的层修。

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

### 公网 curl timeout（最常见 ⚠️ 已踩过 2 次）

90% 概率是**两层防火墙**之一没配（或两层都没配）。**先按 §3c 自检 3 步**
精确定位是 ufw 还是云安全组在拦，再去对应的层修。**不要直接重启服务器、
重装 Caddy 之类**——那是治不了防火墙问题的。

### 浏览器一直转圈

```bash
# 1. 静态文件是否服务到位
curl -I http://<host>/communicationCardWeb.wasm
# 应 200 + application/wasm

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

# ⑤ 修 ufw（旧版 install.sh 没自动配；公网 timeout 已踩过 2 次）
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw delete allow 8080/tcp 2>/dev/null || true
sudo ufw delete allow 8080/tcp 2>/dev/null || true   # v6 那条再删一次
sudo ufw --force enable
sudo ufw status verbose
# 确认 80/443 ALLOW，8080 不在 allow 列表

# ⑥ 云厂商安全组（ufw 之外的另一层 —— 必须手动配，install.sh 管不到）
#    腾讯云控制台 → 云服务器 → 安全组 → 入站规则：
#    放行 TCP:22, TCP:80, TCP:443；删掉 TCP:8080（如有）
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

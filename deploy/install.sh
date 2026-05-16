#!/usr/bin/env bash
# 沟通牌 · 服务器一次性初始化（Ubuntu 22.04）
#
# 用法（在腾讯云 / 任何 Ubuntu 22.04 机上）：
#   wget https://raw.githubusercontent.com/berming/AndroidAPP/main/deploy/install.sh
#   sudo bash install.sh
#
# 后续：每次 push 到 main 由 GitHub Actions 自动 SSH 部署，无需再跑此脚本。

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "请用 sudo 跑：sudo bash $0" >&2
    exit 1
fi

REPO_RAW="${REPO_RAW:-https://raw.githubusercontent.com/berming/AndroidAPP/main}"
WEB_DIR="/var/www/communication-card-web"
# admin SPA 静态文件目录（PR 4）
ADMIN_WEB_DIR="/var/www/communication-card-admin"
SERVER_DIR="/opt/communication-card/server"
LOG_DIR="/var/log/communication-card"
# admin 后台 SQLite 数据目录（PR 0 起预留；PR 1 admin_users / PR 2 games 表入库）
LIB_DIR="/var/lib/communication-card"
DEPLOY_USER="cards"

step() { echo ""; echo "==> $*"; }

step "1/7 安装系统依赖（Caddy / JRE 17 / rsync）"
apt-get update
apt-get install -y debian-keyring debian-archive-keyring apt-transport-https rsync curl gnupg

if ! command -v caddy >/dev/null 2>&1; then
    # gpg --dearmor 不带 --yes 时若文件已存在会失败；先删旧的更稳
    rm -f /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
        | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
        > /etc/apt/sources.list.d/caddy-stable.list
    apt-get update
    apt-get install -y caddy
fi
apt-get install -y openjdk-17-jre-headless

step "2/7 创建部署用户与目录"
id -u "$DEPLOY_USER" >/dev/null 2>&1 || \
    useradd --system --create-home --shell /bin/bash "$DEPLOY_USER"
mkdir -p "$WEB_DIR" "$ADMIN_WEB_DIR" "$SERVER_DIR" "$LOG_DIR" "$LIB_DIR"
chown -R "$DEPLOY_USER:$DEPLOY_USER" "$WEB_DIR" "$ADMIN_WEB_DIR" "$SERVER_DIR" "$LOG_DIR" "$LIB_DIR"
chmod 750 "$LIB_DIR"

step "3/7 部署 Caddyfile"
curl -fsSL "$REPO_RAW/deploy/Caddyfile" -o /etc/caddy/Caddyfile
echo "    !!! 编辑 /etc/caddy/Caddyfile：选 A（IP 直连）或 B（域名 + HTTPS）方案，"
echo "        删掉不需要那段，然后 systemctl reload caddy"

step "4/7 部署 systemd unit"
curl -fsSL "$REPO_RAW/deploy/communication-card-server.service" \
    -o /etc/systemd/system/communication-card-server.service
systemctl daemon-reload
systemctl enable communication-card-server.service
# 等首次部署落 server/bin/server 后再启动；现在不 start

# Admin 后台环境变量文件：首次安装生成一个**强随机**初始密码，写入
# /etc/communication-card/server.env（root:cards 600 权限；只有服务进程能读）。
# 服务端首次启动检测到 admin_users 表为空 → 用这个密码创建 SUPER_ADMIN 账号。
ENV_DIR=/etc/communication-card
ENV_FILE="$ENV_DIR/server.env"
mkdir -p "$ENV_DIR"
chown root:"$DEPLOY_USER" "$ENV_DIR"
chmod 750 "$ENV_DIR"
if [[ ! -f "$ENV_FILE" ]]; then
    INIT_PASS=$(openssl rand -base64 24 | tr -d '\n' | tr -d '/=+' | head -c 24)
    cat > "$ENV_FILE" <<EOF
# Admin 后台环境变量（install.sh 生成；切勿提交进 git）。
ADMIN_COOKIE_SECURE=true
# 留空 → host-only cookie，仅 bermin.cn 这一具体 host 收得到（更安全）。
# 如未来真增加 admin 子域且需要共享 cookie，再改为 ADMIN_COOKIE_DOMAIN=bermin.cn
# pr-reviewer PR #61 P2 #3：宽 domain 会被未来子域 XSS 用于 CSRF
ADMIN_COOKIE_DOMAIN=
ADMIN_INITIAL_USERNAME=root
ADMIN_INITIAL_PASSWORD=$INIT_PASS
EOF
    chown root:"$DEPLOY_USER" "$ENV_FILE"
    chmod 640 "$ENV_FILE"

    # 同时写一份 root-only 文件 /etc/communication-card/.initial_password
    # 让运维 sudo cat 取，**不直接打到 stdout / scrollback**
    # pr-reviewer PR #61 nit #4：防 SSH 录屏 / tmux 日志留痕
    PASS_FILE="$ENV_DIR/.initial_password"
    printf '%s\n' "$INIT_PASS" > "$PASS_FILE"
    chown root:root "$PASS_FILE"
    chmod 600 "$PASS_FILE"

    echo ""
    echo "    🔑 已生成初始 admin 账号 (root) 及随机密码。"
    echo "       初始密码已写入: $PASS_FILE（root:600）"
    echo "       查看密码：sudo cat $PASS_FILE"
    echo "       首次登录后：进 admin UI 改密 → 编辑 $ENV_FILE 删掉 ADMIN_INITIAL_PASSWORD 行 →"
    echo "                    rm $PASS_FILE"
    echo ""
else
    echo "    ℹ️  $ENV_FILE 已存在，保留不动。"
fi

step "5/7 配置 ufw 防火墙（host 层；腾讯云安全组是另一层，需在控制台单独配）"
# 历史教训（已发生 2 次）：仅配腾讯云安全组而漏 ufw，或反之，导致公网 timeout。
# 这一步把 ufw 配成 expected state：放 22/80/443，关掉 8080（应仅 127.0.0.1 用）。
if ! command -v ufw >/dev/null 2>&1; then
    apt-get install -y ufw
fi
# 确保 SSH 不被锁外（必须先放再 enable，否则 ssh 会断）
ufw allow 22/tcp comment 'SSH'
ufw allow 80/tcp comment 'HTTP (Caddy)'
ufw allow 443/tcp comment 'HTTPS (Caddy)'
# 8080 必须只在 loopback 用：如果之前手动允许过则删除（双 v4+v6）
ufw delete allow 8080/tcp 2>/dev/null || true
ufw delete allow 8080 2>/dev/null || true
# 启用（已 active 时是 noop）
ufw --force enable
ufw status verbose
echo ""
echo "    ⚠️ ufw 仅是 host 层防火墙。云厂商安全组（腾讯云 / 阿里云 / AWS）是另一层"
echo "       网络边界防火墙，必须 ALSO 在云控制台放行 80/443，否则公网仍 timeout。"

step "6/7 给 cards 用户开特定 sudo（重启 service / reload caddy）"
# 用 command -v 找 systemctl 真实路径：Ubuntu 22.04 实际是 /usr/bin/systemctl，
# /bin/systemctl 是 usrmerge symlink，sudo 按字符串匹配命令路径，写错了 NOPASSWD 失效
SYSCTL="$(command -v systemctl)"
if [[ -z "$SYSCTL" || ! -x "$SYSCTL" ]]; then
    echo "找不到 systemctl 可执行文件" >&2; exit 1
fi
SUDOERS_FILE=/etc/sudoers.d/communication-card-deploy
cat > "$SUDOERS_FILE" <<EOF
# 由 deploy/install.sh 生成；只允许 cards 重启自己的 service
$DEPLOY_USER ALL=(root) NOPASSWD: $SYSCTL daemon-reload, $SYSCTL restart communication-card-server, $SYSCTL reload caddy, $SYSCTL status communication-card-server
EOF
chmod 440 "$SUDOERS_FILE"
# 校验语法 —— 错误的 sudoers 会让整台机的 sudo 失效，必须 visudo -c
visudo -cf "$SUDOERS_FILE"

step "7/7 启 Caddy + 占位首页"
cat > "$WEB_DIR/index.html" <<'EOF'
<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><title>沟通牌</title></head>
<body style="background:#1b5e20;color:#fff;font-family:sans-serif;text-align:center;padding-top:30vh;">
<h1>沟通牌</h1><p>服务器已初始化，等待首次 GitHub Actions 部署…</p></body></html>
EOF
chown "$DEPLOY_USER:$DEPLOY_USER" "$WEB_DIR/index.html"
systemctl restart caddy

step "生成 GitHub Actions 用 SSH key"
SSH_DIR="/home/$DEPLOY_USER/.ssh"
sudo -u "$DEPLOY_USER" mkdir -p "$SSH_DIR"
chmod 700 "$SSH_DIR"
KEY_PATH="$SSH_DIR/github_deploy"
if [[ ! -f "$KEY_PATH" ]]; then
    sudo -u "$DEPLOY_USER" ssh-keygen -t ed25519 -N "" -f "$KEY_PATH" -C "github-actions-deploy"
fi
PUBKEY=$(cat "$KEY_PATH.pub")
AUTH="$SSH_DIR/authorized_keys"
touch "$AUTH"
chown "$DEPLOY_USER:$DEPLOY_USER" "$AUTH"
chmod 600 "$AUTH"
if ! grep -qF "$PUBKEY" "$AUTH"; then
    echo "$PUBKEY" >> "$AUTH"
fi

# 优先腾讯云 metadata（中国大陆访问 ipify 可能慢/不通），失败再降级
PUBLIC_IP=$(curl -sS --max-time 2 http://metadata.tencentyun.com/latest/meta-data/public-ipv4 2>/dev/null \
    || curl -sS --max-time 5 https://api.ipify.org 2>/dev/null \
    || echo "<your-server-public-ip>")

cat <<EOF

============================================================
✅ 服务器初始化完成

【ufw 已经自动配好】放行 22/80/443，关闭 8080。无需再动 ufw。

【⚠️ 但 ufw 不是唯一一层！还要去云控制台配安全组（已踩过 2 次坑）】

下一步：

1) 【必做】云厂商安全组（网络边界，与 ufw 是两层独立防火墙）
   腾讯云控制台 → 云服务器 → 实例 → "更多" → "安全组"
   → 修改规则 → 入站规则 → 新增以下两条：

     来源: 0.0.0.0/0   协议端口: TCP:80    策略: 允许   备注: HTTP
     来源: 0.0.0.0/0   协议端口: TCP:443   策略: 允许   备注: HTTPS

   并删除任何对外开放 8080 的旧规则（8080 应仅 127.0.0.1 用）。

   阿里云 / AWS / GCP 等等同操作。如果跳过这一步：
   - 服务器 ufw 全部放行 = 仍然公网 timeout
   - 自检命令（在你本机笔记本跑，不是服务器内）：
       curl -I --max-time 5 http://$PUBLIC_IP/
     超时 = 安全组没放行；秒回 200 = 通了

2) 【必做】GitHub repo → Settings → Secrets and variables → Actions
   → New repository secret，加 3 个 Secret：

     Name:   DEPLOY_SSH_HOST
     Value:  $PUBLIC_IP
     （或你的域名，如 bermin.cn）

     Name:   DEPLOY_SSH_USER
     Value:  $DEPLOY_USER

     Name:   DEPLOY_SSH_KEY
     Value:  （把下面整段私钥复制进去，含 BEGIN/END 行）
------------------------------------------------------------
EOF
cat "$KEY_PATH"
cat <<EOF
------------------------------------------------------------

3) 【必做】同一页 → Variables tab → New repository variable

     Name:   DEPLOY_ENABLED
     Value:  true

   （opt-in 开关；不为 true 时 deploy workflow 直接 skip。
    没设这个，下面第 5 步无论怎么按都不会跑。）

4) 【必做】编辑 /etc/caddy/Caddyfile：
   - 仓库默认配置已切到 bermin.cn 域名 + Let's Encrypt HTTPS + :80 IP fallback。
   - 改用其他域名：把所有 bermin.cn 替换成你的域名。
   - 只用纯 IP：把 bermin.cn 段整段删除，仅留 :80 段（去掉 redir 行，改回
     原 file_server 配置）。
   然后：sudo systemctl reload caddy

5) 【必做】触发首次部署：
   GitHub repo → Actions 页 → 选 "Deploy to server" workflow
   → 点 "Run workflow" 按钮（branch: main）

   ⚠️ 不要用 git commit --allow-empty —— deploy.yml 有 paths: 过滤器，
   空 commit 不命中任何路径，workflow 不会触发。workflow_dispatch
   是绕过 paths 的唯一方式。

6) 验证（按这个顺序排查，能精确定位到哪一层挂了）：

   # a) 服务器内部，绕过所有防火墙，直接打 Caddy
   curl -I --max-time 5 http://127.0.0.1/
   # 通 → Caddy + 服务文件 OK，问题在防火墙某层
   # 不通 → Caddy 没启或 Caddyfile 写错，看 systemctl status caddy

   # b) 服务器内部走 ufw（公网 IP 会经 NAT loop 回到自己）
   curl -I --max-time 5 http://$PUBLIC_IP/
   # 通 → ufw OK，剩腾讯云安全组
   # 超时 → ufw 仍在拦；sudo ufw status verbose 看规则

   # c) 你的本机笔记本（不是 SSH 进服务器）
   curl -I --max-time 5 http://$PUBLIC_IP/
   # 通 → 全部 OK
   # 超时 → 腾讯云安全组没放行 80（最常见，约 80% 概率）

   # d) server 进程日志
   journalctl -u communication-card-server -f
============================================================
EOF

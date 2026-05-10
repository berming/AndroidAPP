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
SERVER_DIR="/opt/communication-card/server"
LOG_DIR="/var/log/communication-card"
DEPLOY_USER="cards"

step() { echo ""; echo "==> $*"; }

step "1/6 安装系统依赖（Caddy / JRE 17 / rsync）"
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

step "2/6 创建部署用户与目录"
id -u "$DEPLOY_USER" >/dev/null 2>&1 || \
    useradd --system --create-home --shell /bin/bash "$DEPLOY_USER"
mkdir -p "$WEB_DIR" "$SERVER_DIR" "$LOG_DIR"
chown -R "$DEPLOY_USER:$DEPLOY_USER" "$WEB_DIR" "$SERVER_DIR" "$LOG_DIR"

step "3/6 部署 Caddyfile"
curl -fsSL "$REPO_RAW/deploy/Caddyfile" -o /etc/caddy/Caddyfile
echo "    !!! 编辑 /etc/caddy/Caddyfile：选 A（IP 直连）或 B（域名 + HTTPS）方案，"
echo "        删掉不需要那段，然后 systemctl reload caddy"

step "4/6 部署 systemd unit"
curl -fsSL "$REPO_RAW/deploy/communication-card-server.service" \
    -o /etc/systemd/system/communication-card-server.service
systemctl daemon-reload
systemctl enable communication-card-server.service
# 等首次部署落 server/bin/server 后再启动；现在不 start

step "5/6 给 cards 用户开特定 sudo（重启 service / reload caddy）"
# 用 command -v 找 systemctl 真实路径：Ubuntu 22.04 实际是 /usr/bin/systemctl，
# /bin/systemctl 是 usrmerge symlink，sudo 按字符串匹配命令路径，写错了 NOPASSWD 失效
SYSCTL="$(command -v systemctl)"
if [[ -z "$SYSCTL" || ! -x "$SYSCTL" ]]; then
    echo "找不到 systemctl 可执行文件" >&2; exit 1
fi
SUDOERS_FILE=/etc/sudoers.d/communication-card-deploy
cat > "$SUDOERS_FILE" <<EOF
# 由 deploy/install.sh 生成；只允许 cards 重启自己的 service
$DEPLOY_USER ALL=(root) NOPASSWD: $SYSCTL restart communication-card-server, $SYSCTL reload caddy, $SYSCTL status communication-card-server
EOF
chmod 440 "$SUDOERS_FILE"
# 校验语法 —— 错误的 sudoers 会让整台机的 sudo 失效，必须 visudo -c
visudo -cf "$SUDOERS_FILE"

step "6/6 启 Caddy + 占位首页"
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

下一步（在 GitHub repo 网页上）：

1) Settings → Secrets and variables → Actions → New repository secret
   依次添加 3 个 Secret：

   Name:   DEPLOY_SSH_HOST
   Value:  $PUBLIC_IP
   （或你的域名，如 cards.example.com）

   Name:   DEPLOY_SSH_USER
   Value:  $DEPLOY_USER

   Name:   DEPLOY_SSH_KEY
   Value:  （把下面整段私钥复制进去，含 BEGIN/END 行）
------------------------------------------------------------
EOF
cat "$KEY_PATH"
cat <<EOF
------------------------------------------------------------

2) 编辑 /etc/caddy/Caddyfile：
   - 用 IP 访问：保留 ":80" 那段，删掉域名段
   - 用域名访问：把 cards.example.com 改成你的域名，删掉 ":80" 段
   然后：sudo systemctl reload caddy

3) 腾讯云安全组：放行 22 / 80 / 443，关闭 8080（只在本机 127.0.0.1 用）

4) 触发首次部署：
   git commit --allow-empty -m "deploy: bootstrap"
   git push origin main

5) 验证：
   curl -I http://$PUBLIC_IP/         # 应 200
   journalctl -u communication-card-server -f
============================================================
EOF

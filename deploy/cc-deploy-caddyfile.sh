#!/usr/bin/env bash
# cc-deploy-caddyfile — 校验暂存的 Caddyfile，通过后安装到 /etc/caddy/Caddyfile 并 reload。
#
# install.sh 会把本脚本装到 /usr/local/sbin/cc-deploy-caddyfile（root:root 755），
# 并在 sudoers 里允许部署用户 cards 以 NOPASSWD 运行它（不带参数，精确匹配）。
#
# 设计原因：CI（cards 用户）只能把 Caddyfile rsync 到自己可写的暂存目录，
# 最终落盘到 /etc/caddy/ 由这个 root 脚本完成——且**必须 caddy validate 通过才 reload**，
# 坏配置不会把 Caddy 打挂。暂存路径写死在脚本里，cards 无法借它覆盖任意系统文件。
set -euo pipefail

STAGED=/opt/communication-card/caddy/Caddyfile
DEST=/etc/caddy/Caddyfile

[[ -f "$STAGED" ]] || { echo "❌ 暂存 Caddyfile 不存在：$STAGED" >&2; exit 1; }

# 1) 先校验暂存配置（不动现网）
if ! caddy validate --config "$STAGED" --adapter caddyfile; then
    echo "❌ caddy validate 失败，保留现有 /etc/caddy/Caddyfile 不变" >&2
    exit 1
fi

# 2) 内容无变化就跳过，避免无谓 reload
if [[ -f "$DEST" ]] && cmp -s "$STAGED" "$DEST"; then
    echo "ℹ️ Caddyfile 无变化，跳过 reload"
    exit 0
fi

# 3) 备份 → 安装 → reload
cp -f "$DEST" "$DEST.bak.$(date +%s)" 2>/dev/null || true
install -m 644 -o root -g root "$STAGED" "$DEST"
systemctl reload caddy
echo "✅ Caddyfile 已更新并 reload"

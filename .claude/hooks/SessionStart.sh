#!/usr/bin/env bash
# SessionStart hook —— Claude Code 会话启动时输出仓库状态摘要。
#
# 设计原则：
# - 必须 fail-soft：缺工具/不在 git 仓库 → 静默退出 0，绝不破坏会话
# - 输出 stdout 既给人看，也给模型看（成为 context）
# - 内容选择：分支 + dirty count + 最近 5 commit + 关键文档/命令索引

set -u
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)" 2>/dev/null || exit 0

if ! command -v git >/dev/null 2>&1; then
    exit 0
fi

# 仅在 git 仓库内输出
if ! git rev-parse --git-dir >/dev/null 2>&1; then
    exit 0
fi

branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "(detached)")
dirty=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')

echo "📍 沟通牌仓库 — 分支: ${branch}（${dirty} 个未提交改动）"
echo ""
echo "🕓 最近 5 个 commit:"
git log --oneline -5 2>/dev/null || true

if [ -n "${CLAUDE_MODEL:-}" ]; then
    echo ""
    echo "🤖 当前模型: ${CLAUDE_MODEL}"
fi

echo ""
echo "📚 关键文档: CLAUDE.md / docs/regressions.md / docs/playbooks/"
echo "🛠️  快速命令: /test-fast · /pre-commit-scan · /ship-check"

exit 0

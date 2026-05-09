#!/usr/bin/env bash
# UserPromptSubmit hook —— 检测 push/merge/ship 关键词，注入 ship-check 提醒。
#
# 设计原则：
# - 输入: { prompt: "..." }
# - 输出: JSON with hookSpecificOutput.additionalContext —— 让模型把 ship-check 纳入上下文
# - 关键词覆盖中英两种说法

set -u

input=$(cat 2>/dev/null || printf '{}')

if command -v jq >/dev/null 2>&1; then
    prompt=$(printf '%s' "$input" | jq -r '.prompt // empty' 2>/dev/null || echo "")
else
    # 兜底：抓首个 prompt 字段值（不严谨但够用）
    prompt=$(printf '%s' "$input" | grep -oE '"prompt"[[:space:]]*:[[:space:]]*"[^"]+"' \
              | head -1 | sed -E 's/.*"prompt"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')
fi

[ -z "$prompt" ] && exit 0

# 关键词检测：push / merge / ship / 发布 / 提 PR / 开 PR / pull request
if printf '%s' "$prompt" | grep -qiE '(\bpush\b|\bmerge\b|\bship\b|发布|提 *PR|开 *PR|pull request|create.*pr\b)'; then
    msg="🚦 [Ship 提醒] 检测到准备 push / merge / 发 PR 的关键词。\n建议先在本地跑 /ship-check 校验四关：\n  1. detekt + :shared:jvmTest 通过\n  2. commit message 含 Signed-off-by + AI-Assisted-By（pre-commit hook 已自动校验）\n  3. 关键路径与对应 *Test.kt 同改\n  4. GameMessage.kt 与 server/Messages.kt 同步（PR-H3 之后退役）\n\n如果只是讨论 push 的进度/状态，不需要再次跑 ship-check。"

    if command -v jq >/dev/null 2>&1; then
        jq -n --arg ctx "$msg" '{
            hookSpecificOutput: {
                hookEventName: "UserPromptSubmit",
                additionalContext: $ctx
            }
        }'
    else
        cat <<'JSON'
{"hookSpecificOutput":{"hookEventName":"UserPromptSubmit","additionalContext":"🚦 [Ship 提醒] 检测到 push/merge/发 PR 关键词。提交前请运行 /ship-check 校验 4 关。"}}
JSON
    fi
fi

exit 0

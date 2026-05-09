#!/usr/bin/env bash
# PostToolUse hook（matcher: Edit|Write）—— 编辑关键路径时注入 TDD 提醒。
#
# 设计原则：
# - 输入：tool 调用 JSON 从 stdin 读取，{ tool_input: { file_path: ... } }
# - 输出：JSON with hookSpecificOutput.additionalContext —— 让模型看到提醒
# - 关键路径（从 CLAUDE.md 第三章 + 计划 docs/regressions.md 抽取）：
#     shared/.../engine/CardRules.kt
#     shared/.../engine/SettlementCalculator.kt
#     server/.../ServerGameManager.kt

set -u

input=$(cat 2>/dev/null || printf '{}')

# 优先 jq 解析；jq 不在则 grep 兜底
if command -v jq >/dev/null 2>&1; then
    file_path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty' 2>/dev/null || echo "")
else
    file_path=$(printf '%s' "$input" | grep -oE '"file_path"[[:space:]]*:[[:space:]]*"[^"]+"' \
                 | head -1 | sed -E 's/.*"file_path"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')
fi

[ -z "$file_path" ] && exit 0

is_critical=0
case "$file_path" in
    *engine/CardRules.kt|*engine/SettlementCalculator.kt|*ServerGameManager.kt)
        is_critical=1
        ;;
esac
[ "$is_critical" -eq 0 ] && exit 0

msg="⚠️ [TDD 提醒] 改动了关键路径: ${file_path}\n\nCLAUDE.md 第三章「关键路径强制 TDD」要求：\n  1. 先在对应 *Test.kt 写失败测试，commit；\n  2. 实施修复，本地 /test-fast 转绿；\n  3. CI tdd-gate 校验 critical path 与对应 test 同改。\n\n参考 docs/regressions.md（历史 Bug）与 docs/playbooks/bug-triage.md。"

# 用 jq 构造 JSON 安全转义；没有 jq 时用最简静态版本
if command -v jq >/dev/null 2>&1; then
    jq -n --arg ctx "$msg" '{
        hookSpecificOutput: {
            hookEventName: "PostToolUse",
            additionalContext: $ctx
        }
    }'
else
    # 不带文件路径的兜底版本
    cat <<'JSON'
{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"⚠️ [TDD 提醒] 改动了关键路径文件。CLAUDE.md 第三章要求先写失败测试再实施修复。参考 docs/regressions.md 与 docs/playbooks/bug-triage.md。"}}
JSON
fi

exit 0

#!/usr/bin/env bash
# PostToolUse hook（matcher: Edit|Write|MultiEdit|Bash）
#
# 两种触发：
#   A. Edit/Write/MultiEdit 改动关键路径文件 → 注入 TDD 提醒
#   B. Bash 命令含 `git push` → 注入"主动检查 Codex review"提醒
#
# 设计原则：hook 不阻塞、不 sleep；仅向主会话注入 additionalContext。
# 主会话看到提醒后**应主动**走对应流程，而不是等用户再提一次。

set -u

input=$(cat 2>/dev/null || printf '{}')

emit_context() {
    local ctx="$1"
    if command -v jq >/dev/null 2>&1; then
        jq -n --arg ctx "$ctx" '{
            hookSpecificOutput: {
                hookEventName: "PostToolUse",
                additionalContext: $ctx
            }
        }'
    else
        # 没 jq 时退化到最朴素的静态注入（不带变量）
        printf '{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":%s}}\n' \
            "$(printf '%s' "$ctx" | sed 's/\\/\\\\/g; s/"/\\"/g; s/$/\\n/' | tr -d '\n' | sed 's/^/"/; s/$/"/')"
    fi
}

# 优先 jq 解析；jq 不在则 grep 兜底
if command -v jq >/dev/null 2>&1; then
    file_path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty' 2>/dev/null || echo "")
    bash_cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty' 2>/dev/null || echo "")
else
    file_path=$(printf '%s' "$input" | grep -oE '"file_path"[[:space:]]*:[[:space:]]*"[^"]+"' \
                 | head -1 | sed -E 's/.*"file_path"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')
    bash_cmd=$(printf '%s' "$input" | grep -oE '"command"[[:space:]]*:[[:space:]]*"[^"]+"' \
                | head -1 | sed -E 's/.*"command"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')
fi

# ─────────────────────────────────────────────────────────────
# 分支 A：关键路径文件改动 → TDD 提醒
# ─────────────────────────────────────────────────────────────
if [ -n "$file_path" ]; then
    case "$file_path" in
        *engine/CardRules.kt|*engine/SettlementCalculator.kt|*ServerGameManager.kt)
            emit_context "⚠️ [TDD 提醒] 改动了关键路径: ${file_path}

CLAUDE.md 第三章「关键路径强制 TDD」要求：
  1. 先在对应 *Test.kt 写失败测试，commit；
  2. 实施修复，本地 /test-fast 转绿；
  3. CI tdd-gate 校验 critical path 与对应 test 同改。

参考 docs/regressions.md（历史 Bug）与 docs/playbooks/bug-triage.md。"
            exit 0
            ;;
    esac
fi

# ─────────────────────────────────────────────────────────────
# 分支 B：git push 后 → 主动检查 Codex review 提醒
# ─────────────────────────────────────────────────────────────
if [ -n "$bash_cmd" ]; then
    case "$bash_cmd" in
        *"git push"*|*"git push -u"*|*"--force"*"git push"*|*"git push --force"*|*"git push --force-with-lease"*)
            emit_context "🤖 [Push 后自动 review-check 提醒] 刚 push 完。

**主会话应当主动**（不要等用户再提一次）：

1. 等 60 秒（Codex bot 通常 30-90s 出 review）
2. 拉 PR review：
     mcp__github__pull_request_read method=get_review_comments
     mcp__github__pull_request_read method=get_check_runs
3. 出现 P0/P1/P2 → 直接修 + commit + 回复 thread + push
4. 出现 CI red → 拉 PR comment 看 exfil 的 gradle 错日志（CLAUDE.md
   docs/playbooks/ci-failure-triage.md），定位错误，修，push
5. 全绿 → 简短报告状态给用户即可，不需要等用户问

依据：用户明确要求'每次 push 后都应当自动检查 Codex review 并修复'。
不要因为'没看到用户问'就不做 —— 这是常驻行为约定。"
            exit 0
            ;;
    esac
fi

exit 0

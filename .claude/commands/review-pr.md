---
description: Independent adversarial review of an open PR via the pr-reviewer subagent (Opus 4.7, locked rubric, MCP-only diff source). Replaces "open new session for /review" in the 4-gate flow.
allowed-tools: Agent(pr-reviewer)
---

# /review-pr

The replacement for "open a new Claude Code session and run /review" in
the 4-gate PR flow (CLAUDE.md 第五章 + `docs/playbooks/adversarial-review.md` §2).

## Why this exists

Opening a fresh session each time was the right *intent* (independent
context, no author-side bias) but expensive in practice — session
overhead, lost scrollback, fragmented review history.

`pr-reviewer` is a subagent (`.claude/agents/pr-reviewer.md`) that
preserves the only thing that matters about "new session" —
**context isolation** — without the ergonomic cost:

- Subagent runs in its own context window; cannot see this session's
  history.
- Its system prompt + rubric is **locked in the agent file**; the
  caller cannot reframe the question.
- It pulls the diff from `mcp__github__pull_request_read` — never
  from the caller's narrative.
- Returns to this session as a structured P0/P1/P2 list.

This satisfies the **same-vendor different-context** layer of the
adversarial pyramid. **Codex (different-vendor) and physical real-
device testing remain mandatory** — see playbook §1, §3, §4.

## Usage

```
/review-pr <PR#>
```

Examples:
```
/review-pr 42
/review-pr 41
```

## Procedure

This command does exactly one thing: invoke the `pr-reviewer` subagent
with the PR number. The subagent:

1. Pulls diff + check status + existing reviews via GitHub MCP
2. Buckets changes (critical path / wire protocol / concurrency /
   network / web UI / Android UI / CI-deploy / docs)
3. Runs the per-bucket P0/P1/P2 rubric (see agent file)
4. Returns a structured findings report

The main session displays the report verbatim. **No automatic posting
to GitHub PR comments** — the human reads, decides which findings to
address vs. contest, and either fixes or replies on the PR manually.

## When to use

- **Required**: any PR touching critical paths
  (CardRules / SettlementCalculator / ServerGameManager /
  GameMessage.kt / Application.kt).
- **Recommended**: any PR before merge, especially deploy / CI / web UI
  changes (that's where same-session review most often misses things —
  see PR #41's post-merge review for 5 missed P0/P1/P2).
- **Skip allowed**: doc-only PRs, single-line typo fixes.

## Output handling

The subagent returns its full report. Show it to the user as-is. If
P0/P1 found:
- Suggest opening a fix-up commit (don't auto-fix; the human decides)
- If on the PR's branch already, offer to apply fixes per finding
- If on main / different branch, suggest checking out the PR branch
  first

## Limitations (be honest with the user)

- **Same vendor**: pr-reviewer is Anthropic Claude Opus, same family
  as the most likely author. Cross-vendor coverage still requires
  Codex bot (auto on each PR) and quarterly second-vendor pass
  (playbook §3).
- **No real-machine verification**: subagent can't compile / run
  tests against a real server / push to a phone. Real-device check
  is gate #4, must be done by the human.
- **Subagent shares training data with author**: same-family
  blind spots correlate. Don't treat a clean pr-reviewer report as
  "this PR is bug-free" — treat it as "no issues found within the
  rubric I checked".

## Don't do in this command

- **Don't summarize the PR yourself first.** The whole point is the
  subagent gets clean context. If you describe the change before
  invoking, you've leaked your bias.
- **Don't edit the agent's prompt at invocation time.** The fixed
  rubric is the feature, not a bug.
- **Don't re-invoke if you don't like the result.** Once is the rule;
  reframe the underlying code change instead, and run again on the
  new commit.

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
**context isolation** — without the ergonomic cost. The isolation is
real because the subagent has its own properties (none of which
depend on this slash-command frontmatter):

- **Independent context window** — subagent literally cannot see this
  session's history. Enforced by the harness.
- **System prompt + rubric in the agent file** — what the reviewer
  "knows" at invocation is whatever's committed to
  `.claude/agents/pr-reviewer.md` plus the user-visible prompt sent
  to it; nothing leaks in from the caller's conversation.
- **Diff sourced from `mcp__github__pull_request_read`** — the agent's
  own procedure forces this. Even if a caller pasted a diff into the
  prompt, the agent is instructed to ignore it and re-pull from
  GitHub.
- **Output returns as a structured P0/P1/P2 list** — the calling
  session displays it; doesn't get to "edit before posting".

> ⚠️ **The `allowed-tools: Agent(pr-reviewer)` line above is _only_
> permission pre-approval**, not isolation. Per Claude Code's
> slash-command spec, frontmatter `allowed-tools` controls which tool
> calls bypass the user's approval prompt — it does **not** restrict
> which tools the calling session can invoke. The caller could still
> invoke Read / Bash / Grep / WebFetch from the same session by
> approving each prompt manually.
>
> So this command's safety against author-side bias rests on
> **discipline + the subagent's intrinsic properties**, not on
> frontmatter as a security boundary. If you (the human or the
> calling session) summarize the PR before invoking, you've leaked
> bias regardless of frontmatter. The "Don't do" section below is
> a contract, not a guard.

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

## GitHub comment format (when posting findings to the PR)

When posting the pr-reviewer report to GitHub via `add_issue_comment`,
wrap the full report in a `<details>` block so it is collapsed by
default. The `<summary>` line must show the verdict and finding counts
at a glance.

Template:

```
<details>
<summary><strong>pr-reviewer · PR #N · &lt;verdict emoji&gt; &lt;verdict&gt; · P0 × N · P1 × N · P2 × N</strong></summary>

[full report body here]

---
_Agent: pr-reviewer subagent · Model: &lt;actual model id, e.g. claude-opus-4-7&gt;_

</details>
```

The `---` attribution line inside the `<details>` block records which
agent and model produced this review for traceability. Use the **actual
model ID** the pr-reviewer subagent ran on (check its frontmatter:
`model: opus` maps to `claude-opus-4-7`). Do not use a placeholder.

When the **main session** posts a follow-up or fix-reply comment
(via `add_issue_comment` or `add_reply_to_pull_request_comment`),
append the same attribution but with the main session's model:

```
---
_Model: &lt;actual model id, e.g. claude-sonnet-4-6&gt;_
```

Verdict emoji:
- 🚫 有 P0（阻塞合并）
- ⚠️ 有 P1（合并前必修）
- ✅ 批准（无 P0/P1）

Example summary line:
```
pr-reviewer · PR #65 · ✅ 批准（无 P0/P1）· P0 × 0 · P1 × 0 · P2 × 4
```

When a follow-up comment updates finding status (e.g. P2 resolved),
use the same `<details>` wrapper with the same attribution footer.

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

---
description: Turn a user bug report into a failing test commit BEFORE attempting any fix (Loop B opener)
allowed-tools: Read, Edit, Write, Grep, Glob, Bash(git diff*), Bash(git log*), Bash(git status*), Bash(git add*), Bash(git commit*), Bash(grep*), Agent(tdd-scaffolder)
---

# /trace-bug

The opening move of `docs/playbooks/bug-triage.md` Loop B —— **"修了又坏"防火墙**.

## Why this command exists

`docs/dev_summary.md` 第六章 records the project's worst antipattern:
1. User reports a bug.
2. Claude reads the relevant code and "fixes" it.
3. The "fix" addresses a symptom, not the root cause.
4. The bug recurs in a new place a week later.
5. Repeat 4×.

The cure: never start a fix without a **failing test** that captures the
exact symptom. Tests outlive sessions; "I think I see why" doesn't.

## Procedure

When invoked with a bug description (text + optional logs/screenshots):

### Step 1 — Restate

In one sentence, restate what the user observed (not what you think causes
it). Example: "Player B's score shows 0 after a settlement that should
yield 60." If the report is too vague, ask one clarifying question and stop.

### Step 2 — Identify the contract

Find the function whose return / state mutation MUST be wrong for this
bug to be visible. Don't speculate beyond that. Examples:
- Score wrong → `SettlementCalculator.calculate` or
  `ServerGameManager.computeAllFinishedScores`
- AI plays wrong card → `CardRules.findValidPlays` or
  `ServerGameManager.decideAIAction`
- Reconnect drops state → `Application.handleReconnect`

If the function lives on a critical path (CardRules / SettlementCalculator
/ ServerGameManager), the failing test goes in the corresponding `*Test.kt`
(see CLAUDE.md 第三章 table). Otherwise, ask the user where the test home
should be.

### Step 3 — Build minimal scenario

Construct the smallest possible inputs that reproduce the symptom:
- For settlement bugs: minimal `playerScores` map + minimal `hands` map
- For canBeat bugs: 2 minimum CardGroups
- For UI/network bugs: a 2-3 message exchange transcript

Use existing test helpers (`card()`, `single()`, `bomb()`, `state()`, `sp()`)
to keep boilerplate down. Read the target `*Test.kt` to find them.

### Step 4 — Delegate to tdd-scaffolder

Invoke the `tdd-scaffolder` subagent (`Agent(tdd-scaffolder)`) with the
scenario. It writes the failing test directly into the right `*Test.kt`
and reports the test name back.

### Step 5 — Stage & commit (the RED commit)

```bash
git add <test_file>
git commit -m "test(bug): red for <one-line symptom>

Repro <user-report-summary>.

Next commit will fix.

Signed-off-by: ...
AI-Assisted-By: Claude Opus 4.7 (claude-opus-4-7)
"
```

The user pushes (or you push). CI MUST go red here. If it doesn't, the
test isn't actually reproducing the bug — go back to Step 3.

### Step 6 — Hand back to the user

Print:
```
🔴 trace-bug
  + <test_file>::<test_name>
  Status: written + committed (next: push and verify CI red)

Loop B next steps (don't do these in this command):
  · Reproduce by running /test-fast — should show this test failing
  · Use Opus 4.7 in a NEW session to identify the root cause
  · Implement fix in a separate commit
  · Open new session for adversarial review (dev_summary.md 8.3)
  · Add an entry to docs/regressions.md
  · Run /ship-check before pushing the fix
```

Stop. Do NOT attempt to fix the bug in this command. The whole point is
the discipline: red test BEFORE any speculation about the cause.

## Anti-pattern this command refuses

- Reading the code, forming a theory about the cause, writing a "fix" that
  may or may not address the actual bug, and skipping the test step.
- Writing a test that passes today (no reproducer); useless.
- Fixing inside this command. Fix is a separate commit + Loop B step.

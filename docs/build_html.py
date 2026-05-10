#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
docs/dev_summary.md → docs/dev_summary.html

将文档中的 ASCII 图替换成 inline SVG，输出单文件 HTML（自带 CSS，无外部依赖）。

用法：python3 docs/build_html.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

import markdown

DOCS = Path(__file__).resolve().parent
SRC = DOCS / "dev_summary.md"
DST = DOCS / "dev_summary.html"


# ─────────────────────────────────────────────────────────────────────────────
# SVG 生成
# ─────────────────────────────────────────────────────────────────────────────

def svg_architecture() -> str:
    """整体架构（多端共享 + 服务端权威）— 四层堆叠 + 反代。"""
    return """
<svg viewBox="0 0 920 620" xmlns="http://www.w3.org/2000/svg" class="diagram diagram-arch" role="img" aria-label="整体架构图">
  <defs>
    <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
      <path d="M0,0 L10,5 L0,10 z" fill="#4b5563"/>
    </marker>
    <linearGradient id="gAndroid" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#dcfce7"/><stop offset="1" stop-color="#bbf7d0"/>
    </linearGradient>
    <linearGradient id="gWeb" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#dbeafe"/><stop offset="1" stop-color="#bfdbfe"/>
    </linearGradient>
    <linearGradient id="gShared" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#fef3c7"/><stop offset="1" stop-color="#fde68a"/>
    </linearGradient>
    <linearGradient id="gServer" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#fce7f3"/><stop offset="1" stop-color="#fbcfe8"/>
    </linearGradient>
  </defs>

  <!-- Android client -->
  <g transform="translate(40 30)">
    <rect width="380" height="100" rx="10" fill="url(#gAndroid)" stroke="#16a34a" stroke-width="1.5"/>
    <text x="20" y="28" font-weight="700" font-size="15">:apps:android（Android 客户端，XML 布局）</text>
    <text x="20" y="50" font-size="12">ui/ → GameActivity（单机）/ OnlineGameActivity（联网）</text>
    <text x="20" y="68" font-size="12">network/ → NetworkManager / RoomManager / GameSyncManager</text>
    <text x="20" y="86" font-size="12">engine/ → MultiplayerGameEngine（桥接 :shared GameEngine）</text>
  </g>

  <!-- Web client -->
  <g transform="translate(500 30)">
    <rect width="380" height="100" rx="10" fill="url(#gWeb)" stroke="#2563eb" stroke-width="1.5"/>
    <text x="20" y="28" font-weight="700" font-size="15">:apps:web（Compose Multiplatform / wasmJs）</text>
    <text x="20" y="50" font-size="12">AppViewModel → 统一状态机；Screen.{Home/Lobby/Room/Game/Settlement}</text>
    <text x="20" y="68" font-size="12">SinglePlayerEngine → 包装 :shared GameEngine</text>
    <text x="20" y="86" font-size="12">net/ → 浏览器原生 WebSocket（@JsFun）；NetworkClient 与 Android 同职</text>
  </g>

  <!-- shared -->
  <g transform="translate(140 175)">
    <rect width="640" height="135" rx="10" fill="url(#gShared)" stroke="#d97706" stroke-width="1.5"/>
    <text x="20" y="28" font-weight="700" font-size="15">:shared（KMP：android + jvm + wasmJs）</text>
    <text x="20" y="52" font-size="12">model/   Card · Deck · Player</text>
    <text x="20" y="70" font-size="12">engine/  CardRules · SettlementCalculator · GameEngine</text>
    <text x="20" y="88" font-size="12">ai/      AIPlayer</text>
    <text x="20" y="106" font-size="12">network/ GameMessage（所有 sealed class + SerializedXxx DTO）</text>
    <text x="20" y="124" font-size="12">commonTest/ CardRulesTest · SettlementCalculatorTest · GameMessageSerializationTest</text>
  </g>

  <!-- server -->
  <g transform="translate(140 360)">
    <rect width="640" height="125" rx="10" fill="url(#gServer)" stroke="#be185d" stroke-width="1.5"/>
    <text x="20" y="28" font-weight="700" font-size="15">:server（Ktor + Netty，Gradle 子项目）</text>
    <text x="20" y="50" font-size="12">Application.kt → ServerRoomManager（房间 / AI 填充）</text>
    <text x="20" y="68" font-size="12">             └→ ServerGameManager（权威状态 / AI / 计时）</text>
    <text x="20" y="90" font-size="12" fill="#7f1d1d">• 每房间一把 Mutex 串行化所有状态修改</text>
    <text x="20" y="108" font-size="12" fill="#7f1d1d">• force-advance 兜底 + 三级 AI 回退 + 30s 超时</text>
  </g>

  <!-- Caddy -->
  <g transform="translate(280 530)">
    <rect width="360" height="60" rx="8" fill="#fff" stroke="#475569" stroke-width="1.5" stroke-dasharray="4 3"/>
    <text x="20" y="28" font-weight="700" font-size="14">Caddy（80 / 443 TLS）</text>
    <text x="20" y="48" font-size="12" fill="#475569">反代 → 127.0.0.1:8080；公网 ws:// 或 wss:// /game</text>
  </g>

  <!-- arrows -->
  <line x1="230" y1="135" x2="380" y2="170" stroke="#4b5563" stroke-width="1.4" marker-end="url(#arrow)"/>
  <line x1="690" y1="135" x2="540" y2="170" stroke="#4b5563" stroke-width="1.4" marker-end="url(#arrow)"/>
  <line x1="460" y1="315" x2="460" y2="355" stroke="#4b5563" stroke-width="1.4" marker-end="url(#arrow)"/>
  <line x1="460" y1="490" x2="460" y2="525" stroke="#4b5563" stroke-width="1.4" marker-end="url(#arrow)"/>

  <!-- side label -->
  <text x="48" y="170" font-size="11" fill="#475569">依赖 :shared</text>
  <text x="780" y="170" font-size="11" fill="#475569">依赖 :shared</text>
  <text x="470" y="345" font-size="11" fill="#475569">依赖 :shared</text>
  <text x="470" y="520" font-size="11" fill="#475569">WebSocket /game</text>
</svg>
"""


def svg_collab_levels() -> str:
    """协同的四个层次（Level 1-4，自下而上）。"""
    levels = [
        ("Level 1", "AI 执行人工指令", "传统：人主导。人写完整指令 → AI 按指令完成 → 等待下一条。AI 沦为'会编程的工具'", "#fee2e2", "#dc2626"),
        ("Level 2", "AI 提建议，人工决策", "审稿：人审 AI。AI 完成后输出方案+备选 → 人工选择/调整/驳回", "#fed7aa", "#ea580c"),
        ("Level 3", "人工反馈现象，AI 自主排查 ← 本项目大量使用", "人工：截图+'还卡住'/'分数错了'  AI：看代码+推理+多轮自查+修复", "#bbf7d0", "#16a34a"),
        ("Level 4", "AI 主动审查，人工验证 ← 最高效模式", "人工：开放性指令（'自查自纠所有问题'）  AI：全量扫描+输出清单+修复  人工：真机验证", "#bfdbfe", "#2563eb"),
    ]
    out = ['<svg viewBox="0 0 880 360" xmlns="http://www.w3.org/2000/svg" class="diagram" role="img" aria-label="协同的四个层次">']
    for i, (lvl, title, desc, fill, stroke) in enumerate(levels):
        y = 20 + i * 80
        out.append(f'<g transform="translate(40 {y})">')
        out.append(f'  <rect width="800" height="64" rx="8" fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>')
        out.append(f'  <text x="20" y="28" font-weight="700" font-size="15" fill="{stroke}">{lvl}　{title}</text>')
        out.append(f'  <text x="20" y="50" font-size="12" fill="#1f2937">{desc}</text>')
        out.append('</g>')
    out.append('</svg>')
    return '\n'.join(out)


def svg_4layer_defense() -> str:
    """游戏卡死 4 层防御（垂直 cascade）。"""
    items = [
        ("[症状]", "等待电脑出牌，永不响应", "#fee2e2", "#b91c1c"),
        ("[层 1]", "canBeat 炸弹比较错误 — 修复：大张数直接胜，张数相同再比牌点", "#fef3c7", "#a16207"),
        ("[层 2]", "AI 失败无任何回退 — 修复：首选 → 过牌 → 最小单张（三级回退）", "#fef3c7", "#a16207"),
        ("[层 3]", "多协程无锁并发写 state.hands — 修复：每房间一把 Mutex（写锁内，广播锁外）", "#fef3c7", "#a16207"),
        ("[层 4]", "所有回退均失败时游戏仍卡住 — 修复：broadcastForceAdvance 强制推进 + 同步所有客户端", "#dcfce7", "#15803d"),
    ]
    out = ['<svg viewBox="0 0 880 380" xmlns="http://www.w3.org/2000/svg" class="diagram" role="img" aria-label="游戏卡死 4 层防御">']
    out.append('<defs><marker id="arr2" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#475569"/></marker></defs>')
    for i, (tag, body, fill, stroke) in enumerate(items):
        y = 20 + i * 70
        out.append(f'<g transform="translate(60 {y})">')
        out.append(f'  <rect width="760" height="50" rx="6" fill="{fill}" stroke="{stroke}" stroke-width="1.4"/>')
        out.append(f'  <text x="20" y="22" font-weight="700" font-size="14" fill="{stroke}">{tag}</text>')
        out.append(f'  <text x="100" y="22" font-size="13" fill="#111827">{body[:60]}</text>')
        if len(body) > 60:
            out.append(f'  <text x="100" y="40" font-size="13" fill="#111827">{body[60:]}</text>')
        out.append('</g>')
        if i < len(items) - 1:
            out.append(f'<line x1="440" y1="{y+50}" x2="440" y2="{y+70}" stroke="#475569" stroke-width="1.5" marker-end="url(#arr2)"/>')
    out.append('</svg>')
    return '\n'.join(out)


def svg_settlement_formula() -> str:
    """两端结算统一公式（视觉化）。"""
    return """
<svg viewBox="0 0 880 280" xmlns="http://www.w3.org/2000/svg" class="diagram" role="img" aria-label="结算公式">
  <g transform="translate(40 30)">
    <rect width="800" height="90" rx="10" fill="#dcfce7" stroke="#16a34a" stroke-width="1.5"/>
    <text x="20" y="28" font-weight="700" font-size="15" fill="#15803d">赢方得分</text>
    <text x="20" y="55" font-size="13" fill="#111827">  = 赢方所有已收</text>
    <text x="20" y="78" font-size="13" fill="#dc2626">  + 输方未走完玩家（已收 + 手牌分）   ← 旧版漏了这一项</text>
  </g>
  <g transform="translate(40 145)">
    <rect width="800" height="56" rx="10" fill="#fee2e2" stroke="#dc2626" stroke-width="1.5"/>
    <text x="20" y="28" font-weight="700" font-size="15" fill="#b91c1c">输方得分</text>
    <text x="20" y="48" font-size="13" fill="#111827">  = 输方已走完玩家的已收</text>
  </g>
  <g transform="translate(40 222)">
    <rect width="800" height="40" rx="8" fill="#fef9c3" stroke="#ca8a04" stroke-width="1.4"/>
    <text x="20" y="25" font-size="13" fill="#854d0e">新增 state.playerScores: MutableMap&lt;Int, Int&gt; → 追踪每人实时已收分（原来硬编码 0）</text>
  </g>
</svg>
"""


def svg_three_box(title_color: str, items: list[tuple[str, str]]) -> str:
    """通用症状/根因/修复 三段式（用于 CJK 字体 / 双层防火墙 / Android URL 漂移）。"""
    color_map = {
        "症状": ("#fee2e2", "#dc2626"),
        "根因": ("#fef3c7", "#a16207"),
        "修复": ("#dcfce7", "#16a34a"),
        "教训": ("#dbeafe", "#2563eb"),
    }
    out = [f'<svg viewBox="0 0 880 {30 + len(items) * 80}" xmlns="http://www.w3.org/2000/svg" class="diagram" role="img">']
    for i, (tag, body) in enumerate(items):
        fill, stroke = color_map.get(tag, ("#f3f4f6", "#6b7280"))
        y = 15 + i * 80
        out.append(f'<g transform="translate(40 {y})">')
        out.append(f'  <rect width="800" height="64" rx="8" fill="{fill}" stroke="{stroke}" stroke-width="1.4"/>')
        out.append(f'  <text x="20" y="26" font-weight="700" font-size="14" fill="{stroke}">[{tag}]</text>')
        # body 可能很长，自动折两行
        if len(body) > 70:
            out.append(f'  <text x="80" y="26" font-size="13" fill="#111827">{body[:70]}</text>')
            rest = body[70:]
            out.append(f'  <text x="80" y="46" font-size="13" fill="#111827">{rest[:75]}</text>')
            if len(rest) > 75:
                out.append(f'  <text x="80" y="62" font-size="13" fill="#111827">{rest[75:150]}</text>')
        else:
            out.append(f'  <text x="80" y="40" font-size="13" fill="#111827">{body}</text>')
        out.append('</g>')
    out.append('</svg>')
    return '\n'.join(out)


def svg_pipeline(stages: list[tuple[str, str, str]]) -> str:
    """模型流水线（Opus → Sonnet → Haiku → Opus 等）。"""
    n = len(stages)
    width = 220
    gap = 20
    total = n * width + (n - 1) * gap + 80
    out = [f'<svg viewBox="0 0 {total} 180" xmlns="http://www.w3.org/2000/svg" class="diagram" role="img">']
    out.append('<defs><marker id="arr3" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#1f2937"/></marker></defs>')
    for i, (model, role, color) in enumerate(stages):
        x = 40 + i * (width + gap)
        out.append(f'<g transform="translate({x} 40)">')
        out.append(f'  <rect width="{width}" height="100" rx="10" fill="{color}" stroke="#1f2937" stroke-width="1.5"/>')
        out.append(f'  <text x="{width/2}" y="34" text-anchor="middle" font-weight="700" font-size="15" fill="#111827">{model}</text>')
        out.append(f'  <text x="{width/2}" y="62" text-anchor="middle" font-size="12" fill="#1f2937">{role[:18]}</text>')
        if len(role) > 18:
            out.append(f'  <text x="{width/2}" y="80" text-anchor="middle" font-size="12" fill="#1f2937">{role[18:36]}</text>')
        out.append('</g>')
        if i < n - 1:
            ax = x + width
            ax2 = ax + gap
            out.append(f'<line x1="{ax}" y1="90" x2="{ax2}" y2="90" stroke="#1f2937" stroke-width="1.6" marker-end="url(#arr3)"/>')
    out.append('</svg>')
    return '\n'.join(out)


def svg_tdd_cycle() -> str:
    """TDD 反向流（Haiku/Sonnet/Opus 循环）。"""
    return """
<svg viewBox="0 0 880 320" xmlns="http://www.w3.org/2000/svg" class="diagram" role="img" aria-label="TDD 反向流">
  <defs><marker id="arr4" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#1f2937"/></marker></defs>
  <g transform="translate(60 40)">
    <rect width="240" height="80" rx="10" fill="#fef3c7" stroke="#a16207" stroke-width="1.5"/>
    <text x="120" y="32" text-anchor="middle" font-weight="700" font-size="15">Haiku 4.5</text>
    <text x="120" y="56" text-anchor="middle" font-size="12">先写边界测试用例（红）</text>
  </g>
  <g transform="translate(380 40)">
    <rect width="240" height="80" rx="10" fill="#dbeafe" stroke="#2563eb" stroke-width="1.5"/>
    <text x="120" y="32" text-anchor="middle" font-weight="700" font-size="15">Sonnet 4.6</text>
    <text x="120" y="56" text-anchor="middle" font-size="12">实现代码让测试绿</text>
  </g>
  <g transform="translate(700 40)">
    <rect width="160" height="80" rx="10" fill="#fce7f3" stroke="#be185d" stroke-width="1.5"/>
    <text x="80" y="32" text-anchor="middle" font-weight="700" font-size="15">Opus 4.7</text>
    <text x="80" y="56" text-anchor="middle" font-size="12">审查测试覆盖</text>
  </g>
  <line x1="300" y1="80" x2="380" y2="80" stroke="#1f2937" stroke-width="1.6" marker-end="url(#arr4)"/>
  <line x1="620" y1="80" x2="700" y2="80" stroke="#1f2937" stroke-width="1.6" marker-end="url(#arr4)"/>
  <!-- loop arrow -->
  <path d="M 780 130 Q 780 240 460 240 Q 180 240 180 130" fill="none" stroke="#1f2937" stroke-width="1.6" stroke-dasharray="4 4" marker-end="url(#arr4)"/>
  <text x="460" y="265" text-anchor="middle" font-size="12" fill="#475569">补测试 → 循环</text>
</svg>
"""


def svg_implementation_results() -> str:
    """实施结果三栏（开发阶段 / PR 4 关 / TDD）。"""
    return """
<svg viewBox="0 0 880 360" xmlns="http://www.w3.org/2000/svg" class="diagram" role="img" aria-label="实施结果">
  <g transform="translate(20 20)">
    <rect width="280" height="320" rx="12" fill="#dcfce7" stroke="#16a34a" stroke-width="1.5"/>
    <text x="140" y="32" text-anchor="middle" font-weight="700" font-size="15" fill="#15803d">✅ 开发阶段</text>
    <text x="20" y="70" font-size="13">Opus 4.7（1M）</text>
    <text x="40" y="88" font-size="11" fill="#475569">架构 + 协议 + harness 设计</text>
    <text x="20" y="120" font-size="13">Sonnet 4.6（默认）</text>
    <text x="40" y="138" font-size="11" fill="#475569">主要实现</text>
    <text x="20" y="170" font-size="13">Haiku 4.5</text>
    <text x="40" y="188" font-size="11" fill="#475569">/pre-commit-scan 静态扫描</text>
  </g>
  <g transform="translate(310 20)">
    <rect width="260" height="320" rx="12" fill="#dbeafe" stroke="#2563eb" stroke-width="1.5"/>
    <text x="130" y="32" text-anchor="middle" font-weight="700" font-size="15" fill="#1d4ed8">✅ PR 阶段四道关</text>
    <text x="20" y="70" font-size="13">① Codex Bot</text>
    <text x="40" y="88" font-size="11" fill="#475569">细粒度风险（自动）</text>
    <text x="20" y="120" font-size="13">② Claude pr-reviewer</text>
    <text x="40" y="138" font-size="11" fill="#475569">Opus 新会话 + 跨文件契约</text>
    <text x="20" y="170" font-size="13">③ CI</text>
    <text x="40" y="188" font-size="11" fill="#475569">tdd-gate + detekt + tests</text>
    <text x="20" y="220" font-size="13">④ 真机验证</text>
    <text x="40" y="238" font-size="11" fill="#475569">环境问题兜底</text>
  </g>
  <g transform="translate(580 20)">
    <rect width="280" height="320" rx="12" fill="#fef3c7" stroke="#a16207" stroke-width="1.5"/>
    <text x="140" y="32" text-anchor="middle" font-weight="700" font-size="15" fill="#854d0e">✅ 关键路径 TDD</text>
    <text x="20" y="70" font-size="13">CardRulesTest</text>
    <text x="40" y="88" font-size="11" fill="#475569">~30 用例</text>
    <text x="20" y="120" font-size="13">ServerGameManagerTest</text>
    <text x="40" y="138" font-size="11" fill="#475569">~25 用例</text>
    <text x="20" y="170" font-size="13">SettlementCalculatorTest</text>
    <text x="40" y="188" font-size="11" fill="#475569">15 用例（3 个月无回归）</text>
    <text x="20" y="220" font-size="13">GameMessageSerializationTest</text>
    <text x="40" y="238" font-size="11" fill="#475569">协议 round-trip</text>
    <text x="20" y="280" font-size="11" fill="#854d0e">CI tdd-gate 强制：critical path 改</text>
    <text x="20" y="296" font-size="11" fill="#854d0e">动必带对应 *Test.kt 改动</text>
  </g>
</svg>
"""


def svg_harness_l0_l4() -> str:
    """Harness L0-L4 五层架构（叠堆）。"""
    layers = [
        ("L0", "记忆层", "CLAUDE.md（主索引）+ docs/regressions.md（Bug 冷藏库）+ docs/playbooks/{feature-development, bug-triage, ci-failure-triage, adversarial-review}.md", "#fee2e2", "#dc2626"),
        ("L1", "权限 & 钩子层", ".claude/settings.json（readonly bash 自动放行）+ .claude/hooks/{SessionStart, PostToolUse, UserPromptSubmit}.sh + .githooks/{pre-push, commit-msg}", "#fed7aa", "#ea580c"),
        ("L2", "命令 & 子代理", ".claude/commands/{test-fast, ship-check, pre-commit-scan, trace-bug, review-pr}.md + .claude/agents/{protocol-syncer, tdd-scaffolder, pr-reviewer}.md", "#fef3c7", "#a16207"),
        ("L3", "TDD 强制层", "CardRulesTest（~30）+ ServerGameManagerTest（~25）+ GameMessageSerializationTest（协议 round-trip）+ CI tdd-gate job", "#bbf7d0", "#16a34a"),
        ("L4", "跨 vendor 审查", "Codex bot（自动每 PR）+ pr-reviewer subagent（Opus 4.7 独立 context）+ 季度第二 vendor + 真机最后一关；4 关 PR 流程", "#bfdbfe", "#2563eb"),
    ]
    out = ['<svg viewBox="0 0 880 460" xmlns="http://www.w3.org/2000/svg" class="diagram" role="img" aria-label="Harness L0-L4 五层架构">']
    for i, (lvl, title, body, fill, stroke) in enumerate(layers):
        y = 20 + i * 88
        out.append(f'<g transform="translate(40 {y})">')
        out.append(f'  <rect width="800" height="76" rx="10" fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>')
        out.append(f'  <text x="20" y="28" font-weight="700" font-size="16" fill="{stroke}">{lvl}　{title}</text>')
        # body 折行
        chunks = []
        rest = body
        while rest:
            chunks.append(rest[:65])
            rest = rest[65:]
        for j, c in enumerate(chunks[:2]):
            out.append(f'  <text x="20" y="{50 + j*18}" font-size="12" fill="#1f2937">{c}</text>')
        out.append('</g>')
    out.append('</svg>')
    return '\n'.join(out)


# ─────────────────────────────────────────────────────────────────────────────
# 主转换
# ─────────────────────────────────────────────────────────────────────────────

# 每个图按 markdown 中的特征行（首行 / 锚点字符串）匹配并替换
# 顺序很重要：用 ASCII 块的"首行特征"做唯一定位，避免误伤
DIAGRAM_REPLACEMENTS: list[tuple[re.Pattern, str]] = [
    # 整体架构（含 :apps:android）
    (re.compile(r"```\n┌[─]+┐\n│  :apps:android（Android 客户端，XML 布局）.*?```", re.DOTALL),
     svg_architecture()),
    # 协同四层次
    (re.compile(r"```\nLevel 1：AI 执行人工指令.*?```", re.DOTALL),
     svg_collab_levels()),
    # 卡死 4 层防御
    (re.compile(r"```\n\[症状\] 等待电脑出牌，永不响应.*?```", re.DOTALL),
     svg_4layer_defense()),
    # 结算公式
    (re.compile(r"```\n赢方得分 = 赢方所有已收.*?```", re.DOTALL),
     svg_settlement_formula()),
    # CJK 字体（症状 / 根因 / 修复 / 教训）
    (re.compile(r"```\n\[症状\] 浏览器中所有中文显示为白色方块.*?```", re.DOTALL),
     svg_three_box("CJK", [
         ("症状", "浏览器中所有中文显示为白色方块 □□□；tab 标题正常"),
         ("根因", "CMP wasmJs 用 Skia 在 <canvas> 渲染；Skia 在 wasm 沙箱拿不到 OS 字体；默认打包字体只覆盖 Latin"),
         ("修复", "PR #45：Noto Sans CJK SC GB2312 子集（7540 字, ~3MB）打入 wasmJs resources；Fonts.kt @JsFun fetch + base64；MaterialTheme typography 全局替换"),
         ("教训", "Compose 跨平台 ≠ 字体跨平台；wasmJs target 必须显式打包字体"),
     ])),
    # 双层防火墙
    (re.compile(r"```\n\[症状\] install.sh 跑完、Caddy 监听 80.*?```", re.DOTALL),
     svg_three_box("FW", [
         ("症状", "install.sh 跑完、Caddy 监听 80；从公网 curl 仍 connect timeout"),
         ("根因", "云厂商安全组（L1）+ 服务器内 ufw（L2）两层独立；任一未放行 80 症状完全相同，无法从外部区分是哪层"),
         ("修复", "PR #44：install.sh 自动配 ufw；playbook §3c 分层自检 3 步"),
         ("教训", "部署 bug 的对称陷阱——给分层自检命令让用户 0 歧义定位哪层挂了，避免乱试"),
     ])),
    # Android URL 漂移
    (re.compile(r"```\n\[症状\] 拉最新 main 后 Android 联网超时.*?```", re.DOTALL),
     svg_three_box("URL", [
         ("症状", "拉最新 main 后 Android 联网超时；Web 客户端正常"),
         ("根因", "PR #41 把拓扑改为 Caddy 80 反代，:8080 不再外露；Web 用相对路径自动适配；Android SERVER_URL 仍硬编码 :8080"),
         ("修复", "PR #46：两处 SERVER_URL 去 :8080，走默认 80"),
         ("教训", "大改部署拓扑必须扫所有客户端；SERVER_URL 是配置项不是常量"),
     ])),
    # Generator/Reviewer 流水线
    (re.compile(r"```\nOpus 4\.7   架构设计 \+ 协议定义.*?```", re.DOTALL),
     svg_pipeline([
         ("Opus 4.7", "架构设计 + 协议定义 + 根因分析", "#fce7f3"),
         ("Sonnet 4.6", "主要实现（默认模型）", "#dbeafe"),
         ("Haiku 4.5", "/pre-commit-scan 批量静态扫描", "#fef3c7"),
         ("Opus（pr-reviewer）", "功能完整 + 跨文件契约审查", "#fce7f3"),
     ])),
    # TDD 反向流
    (re.compile(r"```\nHaiku：先写边界测试用例.*?```", re.DOTALL),
     svg_tdd_cycle()),
    # 实施结果三栏
    (re.compile(r"```\n✅ 开发阶段：.*?```", re.DOTALL),
     svg_implementation_results()),
    # L0-L4 五层架构
    (re.compile(r"```\nL0 记忆层      CLAUDE\.md.*?```", re.DOTALL),
     svg_harness_l0_l4()),
]


def preprocess_md(md: str) -> str:
    """先把 ASCII 图替换成 SVG 占位（用 HTML 注释包裹，markdown 不会动它）。"""
    for i, (pat, svg) in enumerate(DIAGRAM_REPLACEMENTS):
        marker = f"<!--SVG_PLACEHOLDER_{i}-->"
        new, count = pat.subn(marker, md)
        if count == 0:
            print(f"WARN: SVG #{i} 未匹配到任何 ASCII 图块", file=sys.stderr)
        md = new
    return md


def postprocess_html(html: str) -> str:
    """把 SVG 占位换回真实 SVG。"""
    for i, (_, svg) in enumerate(DIAGRAM_REPLACEMENTS):
        marker = f"<!--SVG_PLACEHOLDER_{i}-->"
        # markdown 包裹后通常是 <p><!--...--></p> 形式
        html = re.sub(r"<p>\s*" + re.escape(marker) + r"\s*</p>", svg, html)
        html = html.replace(marker, svg)
    return html


CSS = """
/* 沟通牌项目 · dev_summary 渲染样式 */
:root {
  --fg: #1f2937;
  --fg-muted: #475569;
  --bg: #fafafa;
  --accent: #2563eb;
  --accent-warn: #ea580c;
  --code-bg: #f3f4f6;
  --border: #e5e7eb;
  --container: 1180px;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body {
  font-family: -apple-system, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei",
               "Helvetica Neue", Arial, sans-serif;
  color: var(--fg);
  background: var(--bg);
  line-height: 1.7;
  margin: 0;
  padding: 0;
  font-size: 15px;
}
.wrap { max-width: var(--container); margin: 0 auto; padding: 32px 28px 80px; }
header.title {
  background: linear-gradient(135deg, #1e3a8a 0%, #312e81 100%);
  color: #fff;
  padding: 36px 28px;
  margin-bottom: 28px;
  border-radius: 0;
}
header.title h1 { margin: 0 0 8px; font-size: 28px; letter-spacing: 0.5px; }
header.title .meta { opacity: 0.85; font-size: 14px; }
h1, h2, h3, h4 { color: var(--fg); line-height: 1.35; }
h2 {
  font-size: 22px;
  margin-top: 48px;
  padding-bottom: 8px;
  border-bottom: 2px solid var(--accent);
}
h3 {
  font-size: 17px;
  margin-top: 30px;
  color: var(--accent);
}
h4 { font-size: 15px; margin-top: 22px; color: var(--fg-muted); }
p { margin: 12px 0; }
a { color: var(--accent); text-decoration: none; border-bottom: 1px dotted; }
a:hover { color: #1d4ed8; }
ul, ol { padding-left: 26px; }
li { margin: 4px 0; }
strong { color: #111; }
em { color: var(--accent-warn); font-style: normal; }
blockquote {
  margin: 16px 0;
  padding: 10px 16px;
  background: #fffbeb;
  border-left: 4px solid var(--accent-warn);
  color: #78350f;
  border-radius: 0 6px 6px 0;
  font-size: 14px;
}
blockquote p { margin: 4px 0; }
table {
  border-collapse: collapse;
  width: 100%;
  margin: 14px 0;
  font-size: 13.5px;
  overflow-x: auto;
  display: block;
}
table thead { background: #eef2ff; }
th, td {
  border: 1px solid var(--border);
  padding: 7px 10px;
  text-align: left;
  vertical-align: top;
}
th { font-weight: 700; color: #1e3a8a; white-space: nowrap; }
tr:nth-child(even) td { background: #fafbff; }
code {
  font-family: "JetBrains Mono", "SF Mono", Monaco, Consolas, monospace;
  background: var(--code-bg);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 13px;
  color: #b91c1c;
}
pre {
  background: #0f172a;
  color: #e2e8f0;
  padding: 14px 18px;
  border-radius: 8px;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.55;
}
pre code {
  background: transparent;
  color: inherit;
  padding: 0;
  font-size: inherit;
}
hr { border: none; border-top: 1px solid var(--border); margin: 32px 0; }
.diagram {
  display: block;
  margin: 18px auto;
  max-width: 100%;
  height: auto;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.diagram-arch { background: #fff; }
.toc {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 18px 24px;
  margin: 0 0 28px;
  font-size: 14px;
}
.toc h3 { margin-top: 0; color: var(--fg); border: none; }
.toc ul { padding-left: 22px; margin: 0; }
.toc a { color: var(--fg-muted); }
.footer {
  margin-top: 60px;
  padding: 16px 0;
  border-top: 1px solid var(--border);
  font-size: 12px;
  color: var(--fg-muted);
  text-align: center;
}
@media (max-width: 768px) {
  .wrap { padding: 16px; }
  h2 { font-size: 19px; }
  h3 { font-size: 16px; }
  table { font-size: 12.5px; }
}
"""


def build():
    md_text = SRC.read_text(encoding="utf-8")
    md_text = preprocess_md(md_text)

    md = markdown.Markdown(extensions=["tables", "fenced_code", "toc"], output_format="html5")
    body_html = md.convert(md_text)
    toc_html = md.toc

    body_html = postprocess_html(body_html)

    full = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>沟通牌 · AI 辅助联网游戏开发完整实践总结</title>
  <style>{CSS}</style>
</head>
<body>
  <header class="title">
    <div class="wrap" style="padding: 0">
      <h1>AI 辅助联网游戏开发——完整实践总结</h1>
      <div class="meta">沟通牌项目 · 约 25 分钟 · 移动端 / 后端 / 全栈开发团队</div>
    </div>
  </header>
  <div class="wrap">
    <nav class="toc">
      <h3>目录</h3>
      {toc_html}
    </nav>
    <main>
{body_html}
    </main>
    <div class="footer">
      由 <code>docs/build_html.py</code> 从 <code>docs/dev_summary.md</code> 渲染生成 ·
      ASCII 架构图替换为 inline SVG · 单文件无外部依赖
    </div>
  </div>
</body>
</html>
"""

    DST.write_text(full, encoding="utf-8")
    print(f"Wrote {DST} ({len(full)} bytes)")


if __name__ == "__main__":
    build()

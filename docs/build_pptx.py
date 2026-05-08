#!/usr/bin/env python3
"""Generate dev_summary.pptx — compact 10-slide version."""
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.enum.shapes import MSO_SHAPE

# Colors
PRIMARY = RGBColor(0x1A, 0x73, 0xE8)
DARK = RGBColor(0x20, 0x20, 0x20)
GRAY = RGBColor(0x60, 0x60, 0x60)
LIGHT_BG = RGBColor(0xF0, 0xF4, 0xFF)
RED = RGBColor(0xE5, 0x39, 0x35)
GREEN = RGBColor(0x2E, 0x7D, 0x32)
ORANGE = RGBColor(0xF5, 0x7C, 0x00)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)

FONT_CN = "Microsoft YaHei"
FONT_MONO = "Consolas"

# Font sizes per spec
TITLE_PT = 24
BODY_LG = 16
BODY_MD = 14
BODY_SM = 12

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
SLIDE_W = prs.slide_width
SLIDE_H = prs.slide_height

BLANK_LAYOUT = prs.slide_layouts[6]


def add_slide():
    return prs.slides.add_slide(BLANK_LAYOUT)


def add_textbox(slide, left, top, width, height, text, *,
                font_size=BODY_MD, bold=False, color=DARK,
                align=PP_ALIGN.LEFT, font_name=FONT_CN):
    tb = slide.shapes.add_textbox(left, top, width, height)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = Emu(0)
    tf.margin_right = Emu(0)
    tf.margin_top = Emu(0)
    tf.margin_bottom = Emu(0)
    p = tf.paragraphs[0]
    p.alignment = align
    run = p.add_run()
    run.text = text
    run.font.name = font_name
    run.font.size = Pt(font_size)
    run.font.bold = bold
    run.font.color.rgb = color
    return tb


def add_header(slide, title_text):
    add_textbox(slide, Inches(0.5), Inches(0.3), Inches(12.3), Inches(0.5),
                title_text, font_size=TITLE_PT, bold=True, color=PRIMARY)
    line = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE,
                                    Inches(0.5), Inches(0.85),
                                    Inches(12.3), Inches(0.04))
    line.fill.solid()
    line.fill.fore_color.rgb = PRIMARY
    line.line.fill.background()


def add_page_number(slide, num, total):
    add_textbox(slide, Inches(11.8), Inches(7.05), Inches(1.3), Inches(0.3),
                f"{num} / {total}", font_size=10, color=GRAY,
                align=PP_ALIGN.RIGHT)


def add_table(slide, left, top, width, height, headers, rows,
              header_color=PRIMARY, alt_color=LIGHT_BG,
              font_size=BODY_SM, header_font_size=BODY_SM,
              col_widths=None):
    n_rows = len(rows) + 1
    n_cols = len(headers)
    tbl_shape = slide.shapes.add_table(n_rows, n_cols, left, top, width, height)
    table = tbl_shape.table
    if col_widths:
        for i, w in enumerate(col_widths):
            table.columns[i].width = w

    for i, h in enumerate(headers):
        cell = table.cell(0, i)
        cell.fill.solid()
        cell.fill.fore_color.rgb = header_color
        tf = cell.text_frame
        tf.margin_left = Emu(40000)
        tf.margin_right = Emu(40000)
        tf.margin_top = Emu(15000)
        tf.margin_bottom = Emu(15000)
        tf.text = ""
        p = tf.paragraphs[0]
        p.alignment = PP_ALIGN.CENTER
        run = p.add_run()
        run.text = str(h)
        run.font.name = FONT_CN
        run.font.size = Pt(header_font_size)
        run.font.bold = True
        run.font.color.rgb = WHITE

    for r, row in enumerate(rows, start=1):
        for c, val in enumerate(row):
            cell = table.cell(r, c)
            if r % 2 == 0:
                cell.fill.solid()
                cell.fill.fore_color.rgb = alt_color
            else:
                cell.fill.solid()
                cell.fill.fore_color.rgb = WHITE
            tf = cell.text_frame
            tf.margin_left = Emu(40000)
            tf.margin_right = Emu(40000)
            tf.margin_top = Emu(10000)
            tf.margin_bottom = Emu(10000)
            tf.word_wrap = True
            tf.text = ""
            p = tf.paragraphs[0]
            p.alignment = PP_ALIGN.LEFT
            run = p.add_run()
            run.text = str(val)
            run.font.name = FONT_CN
            run.font.size = Pt(font_size)
            run.font.color.rgb = DARK
    return table


def add_code_block(slide, left, top, width, height, code, *, font_size=BODY_SM):
    box = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, left, top, width, height)
    box.fill.solid()
    box.fill.fore_color.rgb = RGBColor(0xF4, 0xF4, 0xF4)
    box.line.color.rgb = RGBColor(0xD0, 0xD0, 0xD0)
    tf = box.text_frame
    tf.word_wrap = True
    tf.margin_left = Emu(120000)
    tf.margin_right = Emu(120000)
    tf.margin_top = Emu(80000)
    tf.margin_bottom = Emu(80000)
    lines = code.split("\n")
    for i, line in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.text = ""
        run = p.add_run()
        run.text = line if line else " "
        run.font.name = FONT_MONO
        run.font.size = Pt(font_size)
        run.font.color.rgb = DARK


def add_callout(slide, left, top, width, height, text, *,
                bg=LIGHT_BG, border=PRIMARY, font_size=BODY_MD,
                color=DARK, bold=False, align=PP_ALIGN.LEFT):
    box = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                                    left, top, width, height)
    box.fill.solid()
    box.fill.fore_color.rgb = bg
    box.line.color.rgb = border
    if not text:
        return
    tf = box.text_frame
    tf.word_wrap = True
    tf.margin_left = Emu(150000)
    tf.margin_right = Emu(150000)
    tf.text = ""
    p = tf.paragraphs[0]
    p.alignment = align
    run = p.add_run()
    run.text = text
    run.font.name = FONT_CN
    run.font.size = Pt(font_size)
    run.font.bold = bold
    run.font.color.rgb = color


# =================================================================
# Slide 1: Cover + Agenda
# =================================================================
s = add_slide()
accent = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, Inches(0.3), SLIDE_H)
accent.fill.solid()
accent.fill.fore_color.rgb = PRIMARY
accent.line.fill.background()

add_textbox(s, Inches(0.8), Inches(1.2), Inches(11.5), Inches(0.6),
            "AI 辅助联网游戏开发", font_size=BODY_LG, color=GRAY)
add_textbox(s, Inches(0.8), Inches(1.8), Inches(11.5), Inches(1.0),
            "完整实践总结", font_size=44, bold=True, color=PRIMARY)
add_textbox(s, Inches(0.8), Inches(3.0), Inches(11.5), Inches(0.5),
            "沟通牌  ×  Claude Code  ×  约 3 个月实践",
            font_size=BODY_LG, color=DARK)

add_textbox(s, Inches(0.8), Inches(4.0), Inches(11.5), Inches(0.4),
            "本次内容（约 20 分钟）",
            font_size=BODY_MD, bold=True, color=PRIMARY)
items = [
    "①  项目背景与开发全貌    ②  架构设计与遗憾",
    "③  问题全景：人工 vs AI    ④  人机协同模式",
    "⑤  关键技术修复    ⑥  经验总结与后续行动",
]
y = 4.5
for it in items:
    add_textbox(s, Inches(1.0), Inches(y), Inches(11.0), Inches(0.4),
                it, font_size=BODY_MD, color=DARK)
    y += 0.5

# =================================================================
# Slide 2: Background + Tech Stack + Code Volume + Timeline
# =================================================================
s = add_slide()
add_header(s, "一、项目背景与开发全貌")

add_textbox(s, Inches(0.5), Inches(1.05), Inches(12.3), Inches(0.4),
            "沟通牌：4 副牌 216 张，6 人 3v3 卡牌游戏（单机 + 联网对抗）",
            font_size=BODY_MD, color=DARK)

add_textbox(s, Inches(0.5), Inches(1.55), Inches(6.0), Inches(0.4),
            "技术栈", font_size=BODY_LG, bold=True, color=PRIMARY)
tech = [
    ["客户端", "Kotlin + Coroutines + OkHttp + XML 布局"],
    ["服务端", "Ktor + Netty + WebSockets"],
    ["序列化", "kotlinx.serialization (JSON)"],
    ["构建", "AGP 8.5 / Gradle 8.14 + 8.4"],
]
add_table(s, Inches(0.5), Inches(2.0), Inches(6.0), Inches(1.9),
            ["层", "技术"], tech, font_size=BODY_SM,
            col_widths=[Inches(1.3), Inches(4.7)])

add_textbox(s, Inches(7.0), Inches(1.55), Inches(6.0), Inches(0.4),
            "代码规模  ·  90 文件 / ~14,865 行",
            font_size=BODY_LG, bold=True, color=PRIMARY)
vol = [
    ["客户端 Kotlin", "28 文件 · 8,909 行"],
    ["客户端 XML 布局", "56 文件 · 3,279 行"],
    ["服务端 Kotlin", "5 文件 · 2,161 行"],
    ["测试", "1 文件 · 516 行"],
]
add_table(s, Inches(7.0), Inches(2.0), Inches(6.0), Inches(1.9),
            ["模块", "规模"], vol, font_size=BODY_SM,
            col_widths=[Inches(2.2), Inches(3.8)])

add_textbox(s, Inches(0.5), Inches(4.1), Inches(12.3), Inches(0.4),
            "5 阶段时间线  ·  33 PR  ·  123 commit  ·  3 个月",
            font_size=BODY_LG, bold=True, color=PRIMARY)
phases = [
    ["2026-02", "单机游戏开发 (PRs #1–14)", "引擎/牌型/AI/结算 · 11 轮人工 UI 反馈"],
    ["2026-04-30", "联网模式首次完整实现 (#16)", "服务端 + 网络层 + 联网UI · 一次性 6,529 行"],
    ["2026-05-01", "构建与编译修复 (#17–21)", "CI 失败 / Gradle 缺失 / 编译错误"],
    ["2026-05-03", "部署与连通性修复 (#22–31)", "服务器URL / cleartext / 503 / Lobby UI"],
    ["2026-05-04~07", "游戏逻辑深度修复 (#32–33+)", "AI 全量审查×4轮 / 8 commit / 50+ Bug"],
]
add_table(s, Inches(0.5), Inches(4.6), Inches(12.3), Inches(2.4),
            ["时间", "阶段", "主要内容"], phases, font_size=BODY_SM,
            col_widths=[Inches(2.0), Inches(4.0), Inches(6.3)])

# =================================================================
# Slide 3: Architecture (diagram + decisions + regrets)
# =================================================================
s = add_slide()
add_header(s, "二、架构设计：双模式共生")

arch_code = """┌──────────────────────────────────────┐
│  Android 客户端                      │
│   GameActivity (单机)                │
│   OnlineGameActivity (联网)          │
│                                      │
│   engine/  CardRules                 │
│            SettlementCalculator      │
│            GameEngine /              │
│            MultiplayerGameEngine     │
│                                      │
│   network/ NetworkManager            │
│            RoomManager               │
│            GameSyncManager           │
└──────────────────────────────────────┘
              ▲ WebSocket /game (JSON)
              ▼
┌──────────────────────────────────────┐
│  服务端 (Ktor + Netty)               │
│   ServerRoomManager  房间/AI填充     │
│   ServerGameManager  权威状态        │
│     · 每房间 Mutex                   │
│     · 三级 AI 回退 + 兜底推进        │
│     · 30s 回合超时                   │
└──────────────────────────────────────┘"""
add_code_block(s, Inches(0.5), Inches(1.05), Inches(6.0), Inches(5.2),
                arch_code, font_size=BODY_SM)

add_textbox(s, Inches(6.8), Inches(1.05), Inches(6.2), Inches(0.4),
            "关键架构决策", font_size=BODY_LG, bold=True, color=PRIMARY)
decisions = [
    ["状态同步", "全量状态 + 单调 version"],
    ["并发模型", "每房间 Mutex（修改在锁内）"],
    ["重连机制", "sessionToken=playerId · 30s 内恢复"],
    ["AI 替补", "isAISubstitute 标记，不删玩家槽"],
]
add_table(s, Inches(6.8), Inches(1.5), Inches(6.2), Inches(2.3),
            ["决策", "选择"], decisions, font_size=BODY_SM,
            col_widths=[Inches(1.5), Inches(4.7)])

add_textbox(s, Inches(6.8), Inches(4.0), Inches(6.2), Inches(0.4),
            "架构遗憾（后期成本）", font_size=BODY_LG, bold=True, color=RED)
regrets = [
    ["未提取 KMP 共享规则", "导致两端 3 处不一致"],
    ["无协议版本号", "演进时无强制兼容检查"],
    ["无事件溯源", "历史行为无法追溯，调试难"],
]
add_table(s, Inches(6.8), Inches(4.45), Inches(6.2), Inches(1.7),
            ["遗憾", "影响"], regrets, font_size=BODY_SM,
            header_color=RED, col_widths=[Inches(2.5), Inches(3.7)])

add_callout(s, Inches(0.5), Inches(6.4), Inches(12.5), Inches(0.6),
            "核心原则：服务端权威  ·  客户端乐观响应  ·  全量状态 + version 同步",
            bg=PRIMARY, border=PRIMARY,
            font_size=BODY_MD, color=WHITE, bold=True, align=PP_ALIGN.CENTER)

# =================================================================
# Slide 4: Problem Discovery Overview
# =================================================================
s = add_slide()
add_header(s, "三、问题全景：人工 vs AI")

y = 1.3
cards = [
    ("🔴 人工测试 / 反馈", "~30 个",
     "UI 体验 · 部署环境 · 运行时崩溃", "", RED),
    ("🔵 AI #1：Claude Code", "~35 个",
     "全量扫描 · 跨文件链路 · 并发陷阱", "claude-opus-4-7 / sonnet-4-6", PRIMARY),
    ("🟡 AI #2：ChatGPT Codex", "3 个",
     "PR 自动审查 · 细粒度风险点", "chatgpt-codex-connector[bot]", ORANGE),
]
x_positions = [0.5, 4.7, 8.9]
for (title, num, desc, model, color), x in zip(cards, x_positions):
    box = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                                Inches(x), Inches(y),
                                Inches(4.0), Inches(2.5))
    box.fill.solid()
    box.fill.fore_color.rgb = color
    box.line.fill.background()
    add_textbox(s, Inches(x), Inches(y + 0.15), Inches(4.0), Inches(0.4),
                title, font_size=BODY_MD, bold=True, color=WHITE,
                align=PP_ALIGN.CENTER)
    add_textbox(s, Inches(x), Inches(y + 0.6), Inches(4.0), Inches(0.9),
                num, font_size=40, bold=True, color=WHITE,
                align=PP_ALIGN.CENTER)
    add_textbox(s, Inches(x), Inches(y + 1.65), Inches(4.0), Inches(0.35),
                desc, font_size=BODY_SM, color=WHITE, align=PP_ALIGN.CENTER)
    add_textbox(s, Inches(x), Inches(y + 2.05), Inches(4.0), Inches(0.35),
                model, font_size=10, color=WHITE,
                align=PP_ALIGN.CENTER)

add_callout(s, Inches(0.5), Inches(4.1), Inches(12.4), Inches(2.85),
            "", bg=LIGHT_BG, border=PRIMARY)
add_textbox(s, Inches(0.8), Inches(4.3), Inches(12.0), Inches(0.5),
            "💡 核心发现",
            font_size=BODY_LG, bold=True, color=PRIMARY)
add_textbox(s, Inches(0.8), Inches(4.85), Inches(12.0), Inches(0.5),
            "人工看见「症状」 · Claude 挖「根因」 · Codex 找「细节风险」 — 三者几乎不重叠",
            font_size=BODY_MD, bold=True, color=DARK)
add_textbox(s, Inches(0.8), Inches(5.35), Inches(12.0), Inches(0.5),
            "·  人工：截图 / 真机崩溃 / 部署环境问题",
            font_size=BODY_SM, color=DARK)
add_textbox(s, Inches(0.8), Inches(5.7), Inches(12.0), Inches(0.5),
            "·  Claude Code Agent：跨文件链路 / 并发陷阱 / 协议一致性",
            font_size=BODY_SM, color=DARK)
add_textbox(s, Inches(0.8), Inches(6.05), Inches(12.0), Inches(0.5),
            "·  ChatGPT Codex Bot：UUID 截断 / loading 卡死 / UI/服务端不一致",
            font_size=BODY_SM, color=DARK)
add_textbox(s, Inches(0.8), Inches(6.45), Inches(12.0), Inches(0.5),
            "→  多 AI 交叉审查比单一审查更可靠",
            font_size=BODY_SM, bold=True, color=PRIMARY)

# =================================================================
# Slide 5: Human-discovered Issues (3 phases compacted)
# =================================================================
s = add_slide()
add_header(s, "四、人工发现的问题（~32 个，3 阶段）")

add_textbox(s, Inches(0.5), Inches(1.05), Inches(12.3), Inches(0.4),
            "单机阶段（11 个）  ·  反复 UI 打磨",
            font_size=BODY_MD, bold=True, color=PRIMARY)
sp = [
    ["UI 显示", "看不到玩家出牌 · 玩家ID映射错位 · 字体不统一 · 布局拥挤 · 卡片圆角丢失"],
    ["游戏逻辑", "队伍积分只显示个人 · 手牌顺序乱 · AI 滥用炸弹"],
    ["环境兼容", "炸弹重叠比例 · APK 签名不匹配 · 五子棋图标旧 Android 崩溃"],
]
add_table(s, Inches(0.5), Inches(1.5), Inches(12.3), Inches(1.5),
            ["类型", "症状（合并相似项）"], sp, font_size=BODY_SM,
            col_widths=[Inches(2.0), Inches(10.3)])

add_textbox(s, Inches(0.5), Inches(3.15), Inches(12.3), Inches(0.4),
            "部署阶段（9 个）  ·  环境与构建",
            font_size=BODY_MD, bold=True, color=PRIMARY)
dep = [
    ["构建编译", "CI 拉入服务端模块 · Gradle Wrapper 缺失 · 联网代码 3 处编译错误 · JVM 工具链 · 视图 ID"],
    ["部署连通", "服务器 URL · Android 9+ cleartext · 503 错误"],
    ["UI 表述", "「开始游戏」按钮让用户困惑"],
]
add_table(s, Inches(0.5), Inches(3.6), Inches(12.3), Inches(1.5),
            ["类型", "症状（合并相似项）"], dep, font_size=BODY_SM,
            col_widths=[Inches(2.0), Inches(10.3)])

add_textbox(s, Inches(0.5), Inches(5.25), Inches(12.3), Inches(0.4),
            "联网游戏阶段（12 个）  ·  ⭐ 核心痛点",
            font_size=BODY_MD, bold=True, color=RED)
mp = [
    ["UI / 大厅", "loading 永久卡住 · 昵称限制 · 大厅崩溃 · AI 离线 · 无房间列表 · 无踢人"],
    ["📷 游戏卡死", "等待电脑 54 出牌（反复 3 次复现，触发 4 轮 AI 全量自查）"],
    ["📷 分数错误", "已收分全是 0"],
]
add_table(s, Inches(0.5), Inches(5.7), Inches(12.3), Inches(1.5),
            ["类型", "症状（合并相似项）"], mp, font_size=BODY_SM,
            col_widths=[Inches(2.0), Inches(10.3)])

# =================================================================
# Slide 6: AI-discovered Issues (Claude + Codex)
# =================================================================
s = add_slide()
add_header(s, "四、AI 发现的问题（~38 个）")

# Section 1: Claude Code Agent
add_textbox(s, Inches(0.5), Inches(1.0), Inches(12.3), Inches(0.4),
            "🔵 AI #1：Claude Code Agent（~35 个，4 轮审查）",
            font_size=BODY_MD, bold=True, color=PRIMARY)
add_textbox(s, Inches(0.5), Inches(1.4), Inches(12.3), Inches(0.4),
            "模型：claude-opus-4-7（1M 上下文）+ claude-sonnet-4-6  ·  工作模式：Level 4「自查自纠」",
            font_size=10, color=GRAY)

claude_rounds = [
    ["第 1 轮 综合审查 (~20)",
     "协议/序列化 4 · 会话/重连 3 · 房间状态机 4 · UI/状态同步 6 · 其他 3"],
    ["第 2-3 轮 深层 (~8)",
     "回合错位 · ArrayList 并发 · AI 回退链缺失 · collectedScore 硬编码 0"],
    ["第 4 轮 根因 (~7) ⭐",
     "WebSocket CONNECTING send() 静默失败 · 多协程无锁并发 · 结算公式漏算"],
]
add_table(s, Inches(0.5), Inches(1.85), Inches(12.3), Inches(1.95),
            ["审查轮次", "主要问题"], claude_rounds, font_size=BODY_SM,
            col_widths=[Inches(3.0), Inches(9.3)])

# Section 2: ChatGPT Codex Review Bot
add_textbox(s, Inches(0.5), Inches(4.05), Inches(12.3), Inches(0.4),
            "🟡 AI #2：ChatGPT Codex Review Bot（3 个）",
            font_size=BODY_MD, bold=True, color=ORANGE)
add_textbox(s, Inches(0.5), Inches(4.45), Inches(12.3), Inches(0.4),
            "Agent：chatgpt-codex-connector[bot]  ·  PR 自动审查触发  ·  按 P1/P2 标注优先级",
            font_size=10, color=GRAY)

codex_findings = [
    ["#29", "P1", "Application.kt:48", "UUID 截断到 8 字符 → 碰撞致跨用户混乱", "❌→✅ 本次修复"],
    ["#31", "P1", "LobbyActivity.kt:219", "重连后 loading 遮罩可能永久卡住", "✅ PR #33 已修复"],
    ["#33", "P2", "MainActivity.kt", "UI 提示按房间名加入但服务端不支持", "✅ UI 重构后修复"],
]
add_table(s, Inches(0.5), Inches(4.95), Inches(12.3), Inches(1.6),
            ["PR", "优先级", "位置", "意见", "处置"], codex_findings,
            header_color=ORANGE,
            font_size=BODY_SM,
            col_widths=[Inches(0.8), Inches(0.9), Inches(2.4),
                        Inches(5.5), Inches(2.7)])

# Bottom callout
add_callout(s, Inches(0.5), Inches(6.7), Inches(12.4), Inches(0.55),
            "💡 关键观察：Claude 偏向「全局逻辑链路」 vs Codex 偏向「细粒度风险点」 — 两 AI 几乎不重叠",
            bg=LIGHT_BG, border=PRIMARY,
            font_size=BODY_SM, color=PRIMARY, bold=True, align=PP_ALIGN.CENTER)

# =================================================================
# Slide 7: Collaboration — 4 levels + Matrix
# =================================================================
s = add_slide()
add_header(s, "五、人机协同：4 层次 + 实际分工")

add_textbox(s, Inches(0.5), Inches(1.05), Inches(6.5), Inches(0.4),
            "协同的 4 个层次", font_size=BODY_LG, bold=True, color=PRIMARY)
levels = [
    ("L1", "AI 执行人工指令", "传统：人主导，瓶颈", GRAY),
    ("L2", "AI 提建议，人工决策", "审稿模式", DARK),
    ("L3", "人工反馈现象，AI 自主排查", "本项目大量使用 ★", PRIMARY),
    ("L4", "AI 主动审查，人工验证", "本项目最高效模式 ★★", PRIMARY),
]
y = 1.55
for lvl, title, tag, color in levels:
    add_textbox(s, Inches(0.5), Inches(y), Inches(0.5), Inches(0.4),
                lvl, font_size=BODY_MD, bold=True, color=color)
    add_textbox(s, Inches(1.1), Inches(y), Inches(5.5), Inches(0.4),
                title, font_size=BODY_MD, bold=True, color=DARK)
    add_textbox(s, Inches(1.1), Inches(y + 0.4), Inches(5.5), Inches(0.4),
                tag, font_size=BODY_SM, color=color)
    y += 0.95

add_textbox(s, Inches(7.2), Inches(1.05), Inches(6.0), Inches(0.4),
            "实际任务分工矩阵", font_size=BODY_LG, bold=True, color=PRIMARY)
matrix = [
    ["需求定义", "100%", "—"],
    ["架构设计", "70%", "30%"],
    ["编码实现", "5%", "95%"],
    ["UI 调试", "60%", "40%"],
    ["逻辑 Bug 排查", "20%", "80%"],
    ["部署/网络", "80%", "20%"],
    ["文档编写", "10%", "90%"],
    ["代码审查", "30%", "70%"],
]
add_table(s, Inches(7.2), Inches(1.5), Inches(5.8), Inches(4.6),
            ["任务", "人工", "AI"], matrix, font_size=BODY_SM,
            col_widths=[Inches(2.4), Inches(1.7), Inches(1.7)])

add_callout(s, Inches(0.5), Inches(6.4), Inches(12.4), Inches(0.6),
            "本项目 L3 + L4 占用约 70% 协同时间  ·  开放性指令激发全量审查",
            bg=LIGHT_BG, border=PRIMARY,
            font_size=BODY_MD, color=PRIMARY, bold=True, align=PP_ALIGN.CENTER)

# =================================================================
# Slide 8: Strengths + Anti-patterns + Best practices + Efficiency
# =================================================================
s = add_slide()
add_header(s, "五、优势分工 · 反模式 · 效率")

add_textbox(s, Inches(0.5), Inches(1.05), Inches(6.0), Inches(0.4),
            "✅ AI 显著优于人工", font_size=BODY_MD, bold=True, color=GREEN)
ai_pts = [
    "·  全量代码审查（35 问题 / 90 文件）",
    "·  跨文件链路追踪",
    "·  重复模式识别（5 处一次发现）",
    "·  测试用例生成（15 边界用例）",
]
y = 1.5
for pt in ai_pts:
    add_textbox(s, Inches(0.6), Inches(y), Inches(6.0), Inches(0.35),
                pt, font_size=BODY_SM, color=DARK)
    y += 0.32

add_textbox(s, Inches(6.8), Inches(1.05), Inches(6.0), Inches(0.4),
            "❌ 人工不可替代", font_size=BODY_MD, bold=True, color=RED)
hu_pts = [
    "·  真机环境验证（cleartext / 503）",
    "·  时序竞争复现（网络抖动 / 并发）",
    "·  用户体验判断（视觉感知）",
    "·  部署 & 业务决策",
]
y = 1.5
for pt in hu_pts:
    add_textbox(s, Inches(6.9), Inches(y), Inches(6.0), Inches(0.35),
                pt, font_size=BODY_SM, color=DARK)
    y += 0.32

add_textbox(s, Inches(0.5), Inches(3.0), Inches(12.3), Inches(0.4),
            "🚨 协同反模式（要避免）",
            font_size=BODY_MD, bold=True, color=RED)
anti = [
    ["过度信任", "AI 说「已修复」就直接合入 → 反复复现"],
    ["模糊指令", "「把这个 bug 修了」 → 只修表象"],
    ["一次到位幻想", "期待一次解决所有问题 → 本项目经历 4 轮"],
    ["跳过验证", "AI 修完直接发布 → 真机问题永远暴露不出来"],
]
add_table(s, Inches(0.5), Inches(3.5), Inches(7.5), Inches(2.4),
            ["反模式", "后果"], anti, font_size=BODY_SM,
            header_color=RED, col_widths=[Inches(2.0), Inches(5.5)])

add_textbox(s, Inches(8.3), Inches(3.0), Inches(4.7), Inches(0.4),
            "📊 效率数据",
            font_size=BODY_MD, bold=True, color=PRIMARY)
eff = [
    ["人工总投入", "~30 小时"],
    ["AI 等效工作量", "~300 小时"],
    ["提速比", "约 10 倍"],
    ["单次成功率", "~12%"],
]
add_table(s, Inches(8.3), Inches(3.5), Inches(4.7), Inches(2.4),
            ["指标", "数值"], eff, font_size=BODY_SM,
            col_widths=[Inches(2.5), Inches(2.2)])

add_textbox(s, Inches(0.5), Inches(6.05), Inches(12.3), Inches(0.4),
            "💡 高效协同 6 条实践",
            font_size=BODY_MD, bold=True, color=PRIMARY)
add_textbox(s, Inches(0.7), Inches(6.5), Inches(12.0), Inches(0.4),
            "①症状要具体  ②截图优于文字  ③允许多轮迭代  ④关键决策人工拍板  ⑤人工守发布闸门  ⑥开放性指令激发全量审查",
            font_size=BODY_SM, color=DARK)

# =================================================================
# Slide 9: Key Technical Fixes (3 in 1)
# =================================================================
s = add_slide()
add_header(s, "六、关键技术修复（3 处核心）")

add_textbox(s, Inches(0.5), Inches(1.05), Inches(12.3), Inches(0.4),
            "1️⃣ 游戏卡死 — 四层防御",
            font_size=BODY_MD, bold=True, color=PRIMARY)
fix1 = """[层1] canBeat 炸弹比较错误：大张数应直接胜（修复前要求同张数）
[层2] AI 失败无回退：增加三级回退（首选 → 过牌 → 最小单张）
[层3] 多协程无锁并发写 state.hands：每房间一把 Mutex（改在锁内/广播在锁外）
[层4] 三级回退仍失败：broadcastForceAdvance 强制推进兜底"""
add_code_block(s, Inches(0.5), Inches(1.5), Inches(12.3), Inches(1.7),
                fix1, font_size=BODY_SM)

add_textbox(s, Inches(0.5), Inches(3.3), Inches(12.3), Inches(0.4),
            "2️⃣ 重连失效 — 异步时序陷阱",
            font_size=BODY_MD, bold=True, color=PRIMARY)
fix2 = """❌ 修复前：newWebSocket() 后立即 send(Reconnect)，ws 仍在 CONNECTING → 静默丢弃
✅ 修复后：在 onOpen 回调内 send，确保 ws 已 OPEN
教训：「创建连接」≠「连接已建立」，异步 API 必须在回调中操作"""
add_code_block(s, Inches(0.5), Inches(3.75), Inches(12.3), Inches(1.4),
                fix2, font_size=BODY_SM)

add_textbox(s, Inches(0.5), Inches(5.25), Inches(12.3), Inches(0.4),
            "3️⃣ 两端结算不一致 — 统一公式",
            font_size=BODY_MD, bold=True, color=PRIMARY)
fix3 = """根因：collectedScore 硬编码为 0；服务端漏算「输方未走完已收分」
统一公式：赢方 = 赢方所有已收 + 输方未走完(已收 + 手牌分)；输方 = 输方已走完已收
新增 state.playerScores: MutableMap<Int,Int> 实时追踪每人已收分
✓ 15 个验证用例全部通过（含提前结算、速度流、极端场景）"""
add_code_block(s, Inches(0.5), Inches(5.7), Inches(12.3), Inches(1.5),
                fix3, font_size=BODY_SM)

# =================================================================
# Slide 10: Lessons + Action Items + Closing
# =================================================================
s = add_slide()
add_header(s, "七、经验总结 & 后续行动")

add_textbox(s, Inches(0.5), Inches(1.05), Inches(6.2), Inches(0.4),
            "🎯 核心经验",
            font_size=BODY_LG, bold=True, color=PRIMARY)
lessons = [
    "1. 「修了又坏」的根因：只修表层，没往深挖",
    "2. 单机/联网共享逻辑必须保持一致",
    "3. AI 静态扫描 + 人工真机验证不可互相替代",
    "4. 协议与环境问题必须人工打通",
]
y = 1.55
for l in lessons:
    add_textbox(s, Inches(0.6), Inches(y), Inches(6.2), Inches(0.4),
                l, font_size=BODY_SM, color=DARK)
    y += 0.5

add_textbox(s, Inches(7.0), Inches(1.05), Inches(6.0), Inches(0.4),
            "🚀 后续建议行动",
            font_size=BODY_LG, bold=True, color=PRIMARY)
actions = [
    ["P0", "自动化集成测试（炸弹/结算/重连）"],
    ["P0", "共享规则层（KMP 模块）"],
    ["P1", "force-advance 监控告警"],
    ["P2", "弱网回归测试"],
    ["P2", "协议版本号兼容检查"],
]
add_table(s, Inches(7.0), Inches(1.5), Inches(6.0), Inches(2.5),
            ["优先级", "行动"], actions, font_size=BODY_SM,
            col_widths=[Inches(1.2), Inches(4.8)])

add_callout(s, Inches(0.5), Inches(4.2), Inches(12.4), Inches(2.5),
            "", bg=LIGHT_BG, border=PRIMARY)
add_textbox(s, Inches(0.8), Inches(4.4), Inches(12.0), Inches(0.5),
            "🏆 本次交付成果",
            font_size=BODY_LG, bold=True, color=PRIMARY)
add_textbox(s, Inches(0.8), Inches(4.95), Inches(12.0), Inches(0.4),
            "·  全程 33 PR / 123 commit  ·  修复约 67 个问题  ·  本次深度调试 8 commit / 重写 700 行",
            font_size=BODY_SM, color=DARK)
add_textbox(s, Inches(0.8), Inches(5.4), Inches(12.0), Inches(0.4),
            "·  游戏永不卡死 ✓  ·  重连无缝恢复 ✓  ·  两端结算一致 ✓",
            font_size=BODY_SM, color=GREEN, bold=True)
add_textbox(s, Inches(0.8), Inches(5.95), Inches(12.0), Inches(0.5),
            "💡 核心结论：AI 静态全量审查 + 人工动态真机验证 = 互补提速 10 倍",
            font_size=BODY_MD, bold=True, color=PRIMARY)

add_textbox(s, Inches(0.5), Inches(6.85), Inches(12.3), Inches(0.4),
            "谢谢  ·  问题 & 讨论",
            font_size=BODY_MD, bold=True, color=GRAY,
            align=PP_ALIGN.CENTER)

# =================================================================
total = len(prs.slides)
for i, slide in enumerate(prs.slides, start=1):
    if i == 1:
        continue
    add_page_number(slide, i, total)

output = "/home/user/AndroidAPP/docs/dev_summary.pptx"
prs.save(output)
print(f"✓ Saved {total} slides to {output}")

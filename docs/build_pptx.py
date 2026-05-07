#!/usr/bin/env python3
"""Generate dev_summary.pptx from the development summary content."""
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn
from copy import deepcopy

# Colors
PRIMARY = RGBColor(0x1A, 0x73, 0xE8)   # Blue
DARK = RGBColor(0x20, 0x20, 0x20)
GRAY = RGBColor(0x60, 0x60, 0x60)
LIGHT_BG = RGBColor(0xF0, 0xF4, 0xFF)
RED = RGBColor(0xE5, 0x39, 0x35)
GREEN = RGBColor(0x2E, 0x7D, 0x32)
ORANGE = RGBColor(0xF5, 0x7C, 0x00)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)

FONT_CN = "Microsoft YaHei"
FONT_MONO = "Consolas"

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
SLIDE_W = prs.slide_width
SLIDE_H = prs.slide_height

BLANK_LAYOUT = prs.slide_layouts[6]


def add_slide():
    return prs.slides.add_slide(BLANK_LAYOUT)


def add_textbox(slide, left, top, width, height, text, *,
                font_size=18, bold=False, color=DARK, align=PP_ALIGN.LEFT,
                font_name=FONT_CN):
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
    return tb, tf


def add_paragraphs(tf, lines, *, font_size=14, color=DARK, font_name=FONT_CN, bold=False):
    """Add multiple paragraphs to an existing text frame."""
    for i, line in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 and tf.paragraphs[0].text == "" else tf.add_paragraph()
        run = p.add_run()
        run.text = line
        run.font.name = font_name
        run.font.size = Pt(font_size)
        run.font.color.rgb = color
        run.font.bold = bold


def add_header(slide, title_text, subtitle_text=None):
    """Add the standard slide header bar."""
    # Title
    add_textbox(slide, Inches(0.5), Inches(0.3), Inches(12.3), Inches(0.6),
                title_text, font_size=28, bold=True, color=PRIMARY)
    # Underline
    line = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE,
                                    Inches(0.5), Inches(0.95),
                                    Inches(12.3), Inches(0.04))
    line.fill.solid()
    line.fill.fore_color.rgb = PRIMARY
    line.line.fill.background()
    if subtitle_text:
        add_textbox(slide, Inches(0.5), Inches(1.05), Inches(12.3), Inches(0.4),
                    subtitle_text, font_size=14, color=GRAY)


def add_page_number(slide, num, total):
    add_textbox(slide, Inches(11.8), Inches(7.0), Inches(1.3), Inches(0.3),
                f"{num} / {total}", font_size=10, color=GRAY,
                align=PP_ALIGN.RIGHT)


def add_table(slide, left, top, width, height, headers, rows,
              header_color=PRIMARY, alt_color=LIGHT_BG,
              font_size=12, header_font_size=13,
              col_widths=None):
    """Add a styled table."""
    n_rows = len(rows) + 1
    n_cols = len(headers)
    tbl_shape = slide.shapes.add_table(n_rows, n_cols, left, top, width, height)
    table = tbl_shape.table

    if col_widths:
        for i, w in enumerate(col_widths):
            table.columns[i].width = w

    # Header
    for i, h in enumerate(headers):
        cell = table.cell(0, i)
        cell.fill.solid()
        cell.fill.fore_color.rgb = header_color
        tf = cell.text_frame
        tf.margin_left = Emu(50000)
        tf.margin_right = Emu(50000)
        tf.margin_top = Emu(20000)
        tf.margin_bottom = Emu(20000)
        tf.text = ""
        p = tf.paragraphs[0]
        p.alignment = PP_ALIGN.CENTER
        run = p.add_run()
        run.text = str(h)
        run.font.name = FONT_CN
        run.font.size = Pt(header_font_size)
        run.font.bold = True
        run.font.color.rgb = WHITE

    # Body
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
            tf.margin_left = Emu(50000)
            tf.margin_right = Emu(50000)
            tf.margin_top = Emu(15000)
            tf.margin_bottom = Emu(15000)
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


def add_code_block(slide, left, top, width, height, code, *, font_size=12):
    """Add a monospace code block with light gray background."""
    box = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, left, top, width, height)
    box.fill.solid()
    box.fill.fore_color.rgb = RGBColor(0xF4, 0xF4, 0xF4)
    box.line.color.rgb = RGBColor(0xD0, 0xD0, 0xD0)

    tf = box.text_frame
    tf.word_wrap = True
    tf.margin_left = Emu(150000)
    tf.margin_right = Emu(150000)
    tf.margin_top = Emu(100000)
    tf.margin_bottom = Emu(100000)
    lines = code.split("\n")
    for i, line in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.text = ""
        run = p.add_run()
        run.text = line if line else " "
        run.font.name = FONT_MONO
        run.font.size = Pt(font_size)
        run.font.color.rgb = DARK


# =================================================================
# Slide 1: Cover
# =================================================================
s = add_slide()
# Background bar
bar = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, SLIDE_W, Inches(7.5))
bar.fill.solid()
bar.fill.fore_color.rgb = WHITE
bar.line.fill.background()

# Side accent bar
accent = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, Inches(0.3), SLIDE_H)
accent.fill.solid()
accent.fill.fore_color.rgb = PRIMARY
accent.line.fill.background()

add_textbox(s, Inches(1.0), Inches(2.0), Inches(11.5), Inches(0.6),
            "AI 辅助联网游戏开发", font_size=20, color=GRAY)
add_textbox(s, Inches(1.0), Inches(2.6), Inches(11.5), Inches(1.2),
            "完整实践总结", font_size=54, bold=True, color=PRIMARY)
add_textbox(s, Inches(1.0), Inches(4.2), Inches(11.5), Inches(0.5),
            "沟通牌 × Claude Code × 约 3 个月实践", font_size=22, color=DARK)
add_textbox(s, Inches(1.0), Inches(5.5), Inches(11.5), Inches(0.4),
            "技术架构  ·  问题全景  ·  人机协同模式  ·  工程经验",
            font_size=16, color=GRAY)

# =================================================================
# Slide 2: Agenda
# =================================================================
s = add_slide()
add_header(s, "目录", "约 20 分钟")

agenda = [
    ("1", "项目背景", "技术栈 · 代码规模 · 开发全貌"),
    ("2", "架构设计", "双模式架构 · 关键决策 · 妥协与遗憾"),
    ("3", "问题全景", "人工 ~32 个 vs AI ~35 个，如何互补"),
    ("4", "人机协同", "4 个层次 · 分工矩阵 · 反模式 · 最佳实践"),
    ("5", "关键技术修复", "四层防卡死 · 重连时序 · 结算公式"),
    ("6", "经验与行动", "核心结论 · 后续建议"),
]
y = 1.6
for num, title, sub in agenda:
    # Number circle
    circ = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(0.8), Inches(y),
                                Inches(0.7), Inches(0.7))
    circ.fill.solid()
    circ.fill.fore_color.rgb = PRIMARY
    circ.line.fill.background()
    tf = circ.text_frame
    tf.text = ""
    tf.margin_top = Emu(0)
    tf.margin_bottom = Emu(0)
    p = tf.paragraphs[0]
    p.alignment = PP_ALIGN.CENTER
    run = p.add_run()
    run.text = num
    run.font.name = FONT_CN
    run.font.size = Pt(22)
    run.font.bold = True
    run.font.color.rgb = WHITE

    add_textbox(s, Inches(1.8), Inches(y), Inches(11.0), Inches(0.4),
                title, font_size=22, bold=True, color=DARK)
    add_textbox(s, Inches(1.8), Inches(y + 0.4), Inches(11.0), Inches(0.4),
                sub, font_size=14, color=GRAY)
    y += 0.85

# =================================================================
# Slide 3: Project Background — Tech Stack
# =================================================================
s = add_slide()
add_header(s, "一、项目背景：产品 + 技术栈")

# Left: Product description
add_textbox(s, Inches(0.5), Inches(1.3), Inches(6.0), Inches(0.5),
            "产品简介", font_size=18, bold=True, color=PRIMARY)
add_textbox(s, Inches(0.5), Inches(1.85), Inches(6.0), Inches(0.5),
            "沟通牌：4 副牌 216 张，6 人 3v3 卡牌游戏", font_size=15, color=DARK)

bullets_p = [
    ("●  单机模式：本机 AI 对手", DARK),
    ("●  联网对抗：WebSocket 实时对战  ← 本次重点", PRIMARY),
]
y = 2.4
for txt, c in bullets_p:
    add_textbox(s, Inches(0.7), Inches(y), Inches(6.0), Inches(0.4),
                txt, font_size=14, color=c, bold=(c == PRIMARY))
    y += 0.4

# Right: Tech stack table
add_textbox(s, Inches(7.0), Inches(1.3), Inches(6.0), Inches(0.5),
            "技术栈", font_size=18, bold=True, color=PRIMARY)
tech = [
    ["客户端语言", "Kotlin + Coroutines + Flow"],
    ["客户端 UI", "Android XML 布局（不使用 Compose）"],
    ["客户端 WebSocket", "OkHttp"],
    ["服务端框架", "Ktor + Netty + WebSockets"],
    ["序列化", "kotlinx.serialization (JSON)"],
    ["构建", "AGP 8.5 / Gradle 8.14 + 8.4"],
]
add_table(s, Inches(7.0), Inches(1.85), Inches(6.0), Inches(3.5),
            ["层", "技术"], tech, font_size=12, header_font_size=13,
            col_widths=[Inches(2.0), Inches(4.0)])

# Bottom: Code volume
add_textbox(s, Inches(0.5), Inches(5.5), Inches(12.3), Inches(0.5),
            "代码规模：共 90 个文件 / ~14,865 行",
            font_size=18, bold=True, color=PRIMARY)
code_vol = [
    ["客户端 Kotlin", "客户端 XML", "服务端 Kotlin", "测试"],
    ["28 文件 · 8,909 行", "56 文件 · 3,279 行", "5 文件 · 2,161 行", "1 文件 · 516 行"],
]
add_table(s, Inches(0.5), Inches(6.05), Inches(12.3), Inches(1.0),
            code_vol[0], [code_vol[1]], font_size=13, header_font_size=13)

# =================================================================
# Slide 4: Development Timeline
# =================================================================
s = add_slide()
add_header(s, "二、开发全貌：5 阶段 · 33 PR · 3 个月")

phases = [
    ("2026-02", "单机游戏开发", "PRs #1–14", "游戏引擎 / 牌型规则 / AI / 结算 / 单机UI · 约11轮人工 UI 反馈"),
    ("2026-04-30", "联网模式首次完整实现", "PR #16", "服务端 + 客户端网络层 + 联网UI · 一次性 6,529 行新增"),
    ("2026-05-01", "构建与编译修复", "PRs #17–21", "CI 失败 / Gradle 缺失 / 编译错误"),
    ("2026-05-03", "部署与连通性修复", "PRs #22–31", "服务器URL / Android cleartext / 503 调试 / Lobby UI"),
    ("2026-05-04~07", "游戏逻辑深度修复", "PRs #32–33 + 本次", "AI 全量审查×4轮 / 8 次 commit / 50+ Bug"),
]

y = 1.4
for date, title, prs_, desc in phases:
    # Date
    add_textbox(s, Inches(0.5), Inches(y), Inches(2.0), Inches(0.4),
                date, font_size=14, bold=True, color=PRIMARY)
    # Title
    add_textbox(s, Inches(2.6), Inches(y), Inches(7.0), Inches(0.4),
                title, font_size=15, bold=True, color=DARK)
    # PRs
    add_textbox(s, Inches(10.0), Inches(y), Inches(2.5), Inches(0.4),
                prs_, font_size=13, color=GRAY)
    # Description
    add_textbox(s, Inches(2.6), Inches(y + 0.45), Inches(10.0), Inches(0.45),
                desc, font_size=12, color=GRAY)
    y += 1.0

# Footer summary
total_box = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                                Inches(0.5), Inches(6.6),
                                Inches(12.3), Inches(0.7))
total_box.fill.solid()
total_box.fill.fore_color.rgb = LIGHT_BG
total_box.line.color.rgb = PRIMARY
tf = total_box.text_frame
tf.text = ""
p = tf.paragraphs[0]
p.alignment = PP_ALIGN.CENTER
run = p.add_run()
run.text = "总计：33 PR  ·  123 commit  ·  修复约 67 个问题"
run.font.name = FONT_CN
run.font.size = Pt(18)
run.font.bold = True
run.font.color.rgb = PRIMARY

# =================================================================
# Slide 5: Architecture - Diagram
# =================================================================
s = add_slide()
add_header(s, "三、架构设计：双模式共生")

arch_code = """┌─────────────────────────────────────────────────────────────┐
│  Android 客户端                                             │
│                                                             │
│   GameActivity (单机)  ←→  OnlineGameActivity (联网)        │
│                                                             │
│   engine/    CardRules · SettlementCalculator (共享规则)    │
│              GameEngine (单机) · MultiplayerGameEngine      │
│                                                             │
│   network/   NetworkManager  · RoomManager                  │
│              GameSyncManager (状态同步 / 版本号)            │
└─────────────────────────────────────────────────────────────┘
                       ▲   WebSocket /game (JSON)
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  服务端 (Ktor + Netty)                                      │
│                                                             │
│   ServerRoomManager   房间生命周期 / AI 填充                │
│   ServerGameManager   权威状态 / AI 决策 / 30s 计时         │
│        每房间一把 Mutex · 三级 AI 回退 · 兜底推进           │
└─────────────────────────────────────────────────────────────┘"""
add_code_block(s, Inches(0.5), Inches(1.3), Inches(12.3), Inches(5.3),
                arch_code, font_size=12)

# Footer principle
foot = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                            Inches(0.5), Inches(6.8),
                            Inches(12.3), Inches(0.5))
foot.fill.solid()
foot.fill.fore_color.rgb = PRIMARY
foot.line.fill.background()
tf = foot.text_frame
tf.text = ""
p = tf.paragraphs[0]
p.alignment = PP_ALIGN.CENTER
run = p.add_run()
run.text = "核心原则：服务端权威  ·  客户端乐观响应  ·  全量状态 + version 同步"
run.font.name = FONT_CN
run.font.size = Pt(14)
run.font.bold = True
run.font.color.rgb = WHITE

# =================================================================
# Slide 6: Architecture - Decisions & Compromises
# =================================================================
s = add_slide()
add_header(s, "三、架构：关键决策 & 遗憾")

add_textbox(s, Inches(0.5), Inches(1.3), Inches(12.3), Inches(0.4),
            "关键架构决策", font_size=18, bold=True, color=PRIMARY)

decisions = [
    ["状态同步", "全量状态 + 单调 version", "比增量简单，天然支持重连"],
    ["并发模型", "每房间 Mutex", "串行化修改，广播在锁外"],
    ["重连机制", "sessionToken = playerId", "30s 内无缝恢复游戏"],
    ["AI 替补", "isAISubstitute 标记", "不删玩家槽，重连仍有效"],
]
add_table(s, Inches(0.5), Inches(1.8), Inches(12.3), Inches(2.3),
            ["决策", "选择", "理由"], decisions, font_size=12,
            col_widths=[Inches(2.0), Inches(4.0), Inches(6.3)])

add_textbox(s, Inches(0.5), Inches(4.4), Inches(12.3), Inches(0.4),
            "架构妥协与遗憾",
            font_size=18, bold=True, color=RED)
regrets = [
    ["⚠ 未提取 KMP 共享规则模块",
     "CardRules 与服务端 canBeat 双份  →  后期出现 3 处不一致"],
    ["⚠ 无协议版本号", "客户端/服务端协议演进时无强制兼容检查"],
    ["⚠ 无事件溯源", "全量状态同步，历史行为无法追溯，调试困难"],
]
add_table(s, Inches(0.5), Inches(4.9), Inches(12.3), Inches(2.0),
            ["遗憾", "影响"], regrets, font_size=12,
            header_color=RED,
            col_widths=[Inches(4.5), Inches(7.8)])

# =================================================================
# Slide 7: Problem Discovery Overview
# =================================================================
s = add_slide()
add_header(s, "四、问题全景：人工 vs AI")

# Big numbers row
y = 1.5
# Human box
hb = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                         Inches(0.5), Inches(y),
                         Inches(4.0), Inches(2.5))
hb.fill.solid()
hb.fill.fore_color.rgb = RED
hb.line.fill.background()
add_textbox(s, Inches(0.5), Inches(y + 0.2), Inches(4.0), Inches(0.4),
            "🔴 人工测试 / 反馈", font_size=18, bold=True, color=WHITE,
            align=PP_ALIGN.CENTER)
add_textbox(s, Inches(0.5), Inches(y + 0.8), Inches(4.0), Inches(0.9),
            "~32 个", font_size=54, bold=True, color=WHITE,
            align=PP_ALIGN.CENTER)
add_textbox(s, Inches(0.5), Inches(y + 1.85), Inches(4.0), Inches(0.5),
            "UI体验  ·  部署环境  ·  运行时崩溃",
            font_size=12, color=WHITE, align=PP_ALIGN.CENTER)

# AI box
ab = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                         Inches(4.7), Inches(y),
                         Inches(4.0), Inches(2.5))
ab.fill.solid()
ab.fill.fore_color.rgb = PRIMARY
ab.line.fill.background()
add_textbox(s, Inches(4.7), Inches(y + 0.2), Inches(4.0), Inches(0.4),
            "🔵 AI 静态审查", font_size=18, bold=True, color=WHITE,
            align=PP_ALIGN.CENTER)
add_textbox(s, Inches(4.7), Inches(y + 0.8), Inches(4.0), Inches(0.9),
            "~35 个", font_size=54, bold=True, color=WHITE,
            align=PP_ALIGN.CENTER)
add_textbox(s, Inches(4.7), Inches(y + 1.85), Inches(4.0), Inches(0.5),
            "逻辑错误  ·  并发  ·  协议细节",
            font_size=12, color=WHITE, align=PP_ALIGN.CENTER)

# Bot box
bb = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                         Inches(8.9), Inches(y),
                         Inches(4.0), Inches(2.5))
bb.fill.solid()
bb.fill.fore_color.rgb = ORANGE
bb.line.fill.background()
add_textbox(s, Inches(8.9), Inches(y + 0.2), Inches(4.0), Inches(0.4),
            "🟡 Code Review Bot", font_size=18, bold=True, color=WHITE,
            align=PP_ALIGN.CENTER)
add_textbox(s, Inches(8.9), Inches(y + 0.8), Inches(4.0), Inches(0.9),
            "2 个", font_size=54, bold=True, color=WHITE,
            align=PP_ALIGN.CENTER)
add_textbox(s, Inches(8.9), Inches(y + 1.85), Inches(4.0), Inches(0.5),
            "PR 自动审查额外发现",
            font_size=12, color=WHITE, align=PP_ALIGN.CENTER)

# Conclusion
cb = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                          Inches(0.5), Inches(4.5),
                          Inches(12.4), Inches(2.4))
cb.fill.solid()
cb.fill.fore_color.rgb = LIGHT_BG
cb.line.color.rgb = PRIMARY
add_textbox(s, Inches(0.8), Inches(4.7), Inches(12.0), Inches(0.5),
            "核心发现",
            font_size=20, bold=True, color=PRIMARY)
add_textbox(s, Inches(0.8), Inches(5.3), Inches(12.0), Inches(0.6),
            "人工看见「症状」，AI 挖出「根因」",
            font_size=24, bold=True, color=DARK)
add_textbox(s, Inches(0.8), Inches(5.95), Inches(12.0), Inches(0.6),
            "两者几乎不重叠，而是互补关系",
            font_size=18, color=GRAY)
add_textbox(s, Inches(0.8), Inches(6.45), Inches(12.0), Inches(0.4),
            "→ 接下来逐阶段展开详情",
            font_size=14, color=GRAY)

# =================================================================
# Slide 8: Human Issues - Single Player Phase
# =================================================================
s = add_slide()
add_header(s, "四、人工发现 (1/3)：单机阶段（11 个）")

issues_sp = [
    ["1", "看不到每个玩家打出的牌", "重新设计 UI，每玩家槽显示出牌"],
    ["2", "玩家 ID 映射错位，玩家2 不显示", "修正 ID 偏移（从 1 而非 2 开始）"],
    ["3", "队伍积分只显示个人，非全队合计", "改为 totalCollectedScore"],
    ["4", "炸弹牌重叠比例不对，显示拥挤", "调整为 20% 重叠"],
    ["5", "AI 动不动打炸弹，太激进", "炸弹作为最后手段策略"],
    ["6", "手牌顺序乱，炸弹应排在前面", "按炸弹优先排序"],
    ["7", "更新 APK 提示签名不匹配", "添加固定 debug keystore"],
    ["8", "重构后卡片圆角消失", "恢复 10dp 圆角"],
    ["9", "五子棋图标在旧 Android 崩溃", "修复 API < 26 矢量图兼容性"],
    ["10", "字体大小不统一", "统一为 14sp"],
    ["11", "布局太拥挤，信息看不清", "两行手牌 / 水平玩家排列"],
]
add_table(s, Inches(0.5), Inches(1.3), Inches(12.3), Inches(5.6),
            ["#", "症状 / 反馈", "对应修复"], issues_sp, font_size=12,
            col_widths=[Inches(0.6), Inches(6.0), Inches(5.7)])

# =================================================================
# Slide 9: Human Issues - Deployment Phase
# =================================================================
s = add_slide()
add_header(s, "四、人工发现 (2/3)：部署阶段（9 个）")

issues_dep = [
    ["12", "CI 失败：服务端被 Android 构建拉入", "从 settings.gradle 移除服务端"],
    ["13", "服务端无 Gradle Wrapper，无法启动", "补充 gradlew + wrapper"],
    ["14", "联网模块编译错误（3 处 API 错误）", "修复 CardGroup / GameResult / ConnectionState"],
    ["15", "服务端 JVM 工具链配置错误", "修复 toolchain 配置"],
    ["16", "ChatAdapter 引用不存在的 View ID", "修正为实际存在的 tvSender"],
    ["17", "部署腾讯云后连接不上", "更新服务器 URL"],
    ["18", "Android 9+ cleartext 连接被拒绝", "添加 network_security_config.xml"],
    ["19", "连接返回 503，无法诊断", "添加健康检查接口 + 详细日志"],
    ["20", "「开始游戏」按钮让用户困惑", "改为「单机游戏」"],
]
add_table(s, Inches(0.5), Inches(1.3), Inches(12.3), Inches(5.0),
            ["#", "症状 / 反馈", "对应修复"], issues_dep, font_size=12,
            col_widths=[Inches(0.6), Inches(6.0), Inches(5.7)])

# =================================================================
# Slide 10: Human Issues - Multiplayer Game Phase
# =================================================================
s = add_slide()
add_header(s, "四、人工发现 (3/3)：联网游戏阶段（12 个）")

issues_mp = [
    ["21", "loading 遮罩断线后永远不消失", "断线后直接反馈错误"],
    ["22", "单个中文昵称被 2 字符限制拒绝", "移除最低长度限制"],
    ["23", "进入大厅崩溃（CardSuit 枚举不匹配）", "统一枚举值"],
    ["24", "AI 玩家显示为离线状态", "AI 默认 isConnected=true"],
    ["25", "房间内没有踢人按钮", "添加房主踢人功能"],
    ["26", "看不到有哪些房间可加入", "添加房间列表功能"],
    ["27", "📷 等待电脑54出牌，长时间卡死", "canBeat 炸弹逻辑 + AI 回退链"],
    ["28", "📷 修复后依然卡死（反复 3 次）", "并发 Mutex + 兜底推进"],
    ["29", "📷 已收分全是 0", "playerScores 追踪 + 结算公式"],
    ["30", "反复修复反复复现 → 触发全量自查", "4 轮 AI 审查"],
    ["31", "[Bot P1] 断线重连遮罩永久卡住", "修复重连 UI 路径"],
    ["32", "[Bot P2] UI按房间名加入但服务端不支持", "待修复"],
]
add_table(s, Inches(0.5), Inches(1.3), Inches(12.3), Inches(5.7),
            ["#", "症状 / 反馈", "对应修复"], issues_mp, font_size=11,
            col_widths=[Inches(0.6), Inches(6.5), Inches(5.2)])

# =================================================================
# Slide 11: AI-discovered Issues
# =================================================================
s = add_slide()
add_header(s, "四、AI 发现：~35 个，4 轮审查")

# Round 1
add_textbox(s, Inches(0.5), Inches(1.3), Inches(12.3), Inches(0.4),
            "第 1 轮：综合审查（~20 个）",
            font_size=16, bold=True, color=PRIMARY)
r1 = [
    ["协议 / 序列化 (4)", "sealed class 缺 classDiscriminator；枚举值不对齐"],
    ["会话 / 重连 (3)", "sessionToken 未设置；leaveRoom 未清空 token"],
    ["房间状态机 (4)", "未检查 WAITING；退出不补 AI；重复加入"],
    ["UI / 状态同步 (6)", "seatIndex%2 误算；初始化后未刷新；onDestroy 未 guard"],
    ["其他 (3)", "applyState 版本反向；senderId 不稳定"],
]
add_table(s, Inches(0.5), Inches(1.75), Inches(12.3), Inches(2.3),
            ["类别", "主要问题"], r1, font_size=12,
            col_widths=[Inches(3.0), Inches(9.3)])

# Round 2-3
add_textbox(s, Inches(0.5), Inches(4.3), Inches(12.3), Inches(0.4),
            "第 2-3 轮：深层审查（~8 个）",
            font_size=16, bold=True, color=PRIMARY)
add_textbox(s, Inches(0.5), Inches(4.75), Inches(12.3), Inches(0.5),
            "赢家未设为下轮首家  ·  ArrayList 并发修改  ·  AI 回退链缺失  ·  collectedScore 硬编码 0",
            font_size=12, color=DARK)

# Round 4
add_textbox(s, Inches(0.5), Inches(5.5), Inches(12.3), Inches(0.4),
            "第 4 轮：根因专项（~7 个）",
            font_size=16, bold=True, color=RED)
add_textbox(s, Inches(0.5), Inches(5.95), Inches(12.3), Inches(0.5),
            "WebSocket CONNECTING send() 静默失败  ·  多协程无锁并发写  ·  playerScores 整体缺失",
            font_size=12, color=DARK)
add_textbox(s, Inches(0.5), Inches(6.4), Inches(12.3), Inches(0.5),
            "结算公式漏算输方未走完已收分  ·  两端逻辑不一致",
            font_size=12, color=DARK)

# =================================================================
# Slide 12: Collaboration - 4 Levels
# =================================================================
s = add_slide()
add_header(s, "五、人机协同：4 个层次")

levels = [
    ("Level 1", "AI 执行人工指令", "传统：人主导",
     "人工写完整指令 → AI 执行 → 等下一条", "缺点：人工成为瓶颈", GRAY),
    ("Level 2", "AI 提建议，人工决策", "审稿模式",
     "AI 输出方案+备选 → 人工选择/驳回", "优点：人工不动手，但对质量负责", DARK),
    ("Level 3", "人工反馈现象，AI 自主排查", "本项目大量使用 ★",
     "人工：「还卡住」 / 截图", "AI：看代码 + 推理 + 多轮自查 + 修复", PRIMARY),
    ("Level 4", "AI 主动审查，人工验证", "本项目最高效模式 ★★",
     "人工：「自查自纠所有问题」", "AI：全量扫描 + 清单 + 修复  ·  人工：真机验证", PRIMARY),
]
y = 1.3
for lvl, title, tag, line1, line2, color in levels:
    box = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                                Inches(0.5), Inches(y),
                                Inches(12.3), Inches(1.3))
    box.fill.solid()
    box.fill.fore_color.rgb = LIGHT_BG if color == PRIMARY else WHITE
    box.line.color.rgb = color

    add_textbox(s, Inches(0.7), Inches(y + 0.1), Inches(2.0), Inches(0.4),
                lvl, font_size=18, bold=True, color=color)
    add_textbox(s, Inches(2.7), Inches(y + 0.1), Inches(6.5), Inches(0.4),
                title, font_size=16, bold=True, color=DARK)
    add_textbox(s, Inches(9.0), Inches(y + 0.1), Inches(3.5), Inches(0.4),
                tag, font_size=13, color=color, align=PP_ALIGN.RIGHT)
    add_textbox(s, Inches(0.7), Inches(y + 0.55), Inches(11.8), Inches(0.35),
                line1, font_size=12, color=DARK)
    add_textbox(s, Inches(0.7), Inches(y + 0.9), Inches(11.8), Inches(0.35),
                line2, font_size=12, color=GRAY)
    y += 1.45

# =================================================================
# Slide 13: Task Allocation Matrix
# =================================================================
s = add_slide()
add_header(s, "五、实际分工矩阵")

matrix = [
    ["需求定义", "100%", "—", "人工口述 / 截图"],
    ["架构设计", "70%", "30%", "人工拍板，AI 提方案对比"],
    ["编码实现", "5%", "95%", "AI 主导，人工修正方向"],
    ["UI 调试", "60%", "40%", "人工真机截图，AI 改代码"],
    ["逻辑 Bug 排查", "20%", "80%", "人工提症状，AI 挖根因"],
    ["部署/网络问题", "80%", "20%", "人工诊断环境，AI 改配置"],
    ["文档编写", "10%", "90%", "AI 起草，人工指出遗漏"],
    ["代码审查", "30%", "70%", "AI 全量扫描 + 人工 PR review"],
]
add_table(s, Inches(0.5), Inches(1.3), Inches(12.3), Inches(5.6),
            ["任务类型", "人工", "AI", "协同方式"], matrix, font_size=13,
            header_font_size=14,
            col_widths=[Inches(2.5), Inches(1.5), Inches(1.5), Inches(6.8)])

# =================================================================
# Slide 14: AI Strengths vs Human Irreplaceability
# =================================================================
s = add_slide()
add_header(s, "五、AI 优势 vs 人工不可替代")

# Left: AI strengths
add_textbox(s, Inches(0.5), Inches(1.3), Inches(6.0), Inches(0.5),
            "✅ AI 显著优于人工", font_size=18, bold=True, color=GREEN)
ai_strengths = [
    ["全量代码审查", "单次 35 个问题，覆盖 90 个文件"],
    ["跨文件链路追踪", "客户端到服务端的完整调用链"],
    ["重复模式识别", "5 处类似并发问题一次发现"],
    ["测试用例生成", "15 个结算用例自动覆盖边界"],
]
add_table(s, Inches(0.5), Inches(1.85), Inches(6.0), Inches(2.8),
            ["场景", "说明"], ai_strengths, font_size=11,
            header_color=GREEN, header_font_size=12,
            col_widths=[Inches(2.0), Inches(4.0)])

# Right: Human irreplaceable
add_textbox(s, Inches(7.0), Inches(1.3), Inches(6.0), Inches(0.5),
            "❌ 人工不可替代", font_size=18, bold=True, color=RED)
human_only = [
    ["真机环境验证", "Android cleartext / 503 错误 AI 看不见"],
    ["时序竞争复现", "网络抖动 / 并发，需真机才能复现"],
    ["用户体验判断", "「布局太挤」AI 无视觉感知"],
    ["部署 & 业务决策", "服务器选择 / 密钥 / 业务规则拍板"],
]
add_table(s, Inches(7.0), Inches(1.85), Inches(6.0), Inches(2.8),
            ["场景", "原因"], human_only, font_size=11,
            header_color=RED, header_font_size=12,
            col_widths=[Inches(2.0), Inches(4.0)])

# Footer key insight
foot = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                            Inches(0.5), Inches(5.3),
                            Inches(12.4), Inches(1.7))
foot.fill.solid()
foot.fill.fore_color.rgb = LIGHT_BG
foot.line.color.rgb = PRIMARY
add_textbox(s, Inches(0.8), Inches(5.5), Inches(12.0), Inches(0.5),
            "💡 关键洞察",
            font_size=18, bold=True, color=PRIMARY)
add_textbox(s, Inches(0.8), Inches(6.0), Inches(12.0), Inches(0.5),
            "AI 静态分析 vs 人工动态验证 — 二者覆盖的 Bug 类型几乎不重叠",
            font_size=15, color=DARK)
add_textbox(s, Inches(0.8), Inches(6.45), Inches(12.0), Inches(0.5),
            "→ 任何只有一方参与的流程，都会漏掉至少 40% 的问题",
            font_size=13, color=GRAY)

# =================================================================
# Slide 15: Anti-patterns
# =================================================================
s = add_slide()
add_header(s, "五、协同反模式（要避免）")

antis = [
    ["🚨 过度信任", "AI 说「已修复」就直接合入", "表层修复未触根因，反复复现"],
    ["🚨 过度怀疑", "每个修改都逐行 review", "失去 AI 提速核心价值"],
    ["🚨 模糊指令", "「把这个 bug 修了」", "只修表象，根因仍在"],
    ["🚨 一次到位幻想", "期待一次审查解决所有问题", "本项目经历 4 轮才彻底解决"],
    ["🚨 跳过验证", "AI 修完直接发布", "真机问题永远暴露不出来"],
]
add_table(s, Inches(0.5), Inches(1.5), Inches(12.3), Inches(4.0),
            ["反模式", "表现", "后果"], antis, font_size=14,
            header_color=RED, header_font_size=15,
            col_widths=[Inches(2.5), Inches(4.5), Inches(5.3)])

# Bottom callout
foot = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                            Inches(0.5), Inches(6.0),
                            Inches(12.4), Inches(1.0))
foot.fill.solid()
foot.fill.fore_color.rgb = RGBColor(0xFF, 0xF3, 0xE0)
foot.line.color.rgb = ORANGE
add_textbox(s, Inches(0.8), Inches(6.15), Inches(12.0), Inches(0.4),
            "本项目的真实经历",
            font_size=14, bold=True, color=ORANGE)
add_textbox(s, Inches(0.8), Inches(6.55), Inches(12.0), Inches(0.4),
            "卡死问题历经 4 层防御才彻底解决：canBeat → 回退链 → Mutex → 兜底推进",
            font_size=13, color=DARK)

# =================================================================
# Slide 16: Best Practices & Efficiency
# =================================================================
s = add_slide()
add_header(s, "五、最佳实践 & 效率数据")

# Left: 6 practices
add_textbox(s, Inches(0.5), Inches(1.3), Inches(7.0), Inches(0.5),
            "高效协同的 6 条实践", font_size=18, bold=True, color=PRIMARY)
practices = [
    "1. 症状描述要具体 — 截图比文字信息量大 10 倍",
    "2. 截图优于文字 — UI/现象类问题直接给上下文",
    "3. 允许多轮迭代 — 表象 → 根因 → 防护",
    "4. 关键决策人工拍板 — 架构、协议、依赖",
    "5. 人工把守发布闸门 — commit/push 前最终 review",
    "6. 开放性指令激发全量审查 — 「自查自纠」 > 「修这个 bug」",
]
y = 1.85
for p in practices:
    add_textbox(s, Inches(0.7), Inches(y), Inches(7.0), Inches(0.4),
                p, font_size=13, color=DARK)
    y += 0.55

# Right: Efficiency data
add_textbox(s, Inches(7.8), Inches(1.3), Inches(5.0), Inches(0.5),
            "效率数据", font_size=18, bold=True, color=PRIMARY)
eff = [
    ["人工总投入", "~30 小时"],
    ["AI 等效工作量", "~300 小时"],
    ["提速比", "约 10 倍"],
    ["单次修复成功率", "~12%"],
    ["总迭代次数", "8 次 commit"],
]
add_table(s, Inches(7.8), Inches(1.85), Inches(5.0), Inches(3.3),
            ["指标", "数值"], eff, font_size=13,
            col_widths=[Inches(2.5), Inches(2.5)])

foot = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                            Inches(7.8), Inches(5.4),
                            Inches(5.0), Inches(1.6))
foot.fill.solid()
foot.fill.fore_color.rgb = LIGHT_BG
foot.line.color.rgb = PRIMARY
add_textbox(s, Inches(8.0), Inches(5.55), Inches(4.6), Inches(0.4),
            "💡 启示", font_size=14, bold=True, color=PRIMARY)
add_textbox(s, Inches(8.0), Inches(5.95), Inches(4.6), Inches(1.0),
            "提速 10 倍的代价是迭代次数增加，需要轻量 review 流程配合",
            font_size=12, color=DARK)

# =================================================================
# Slide 17: Fix 1 - 4-layer defense for stuck game
# =================================================================
s = add_slide()
add_header(s, "六、关键技术修复 (1/3)：四层防卡死")

add_textbox(s, Inches(0.5), Inches(1.3), Inches(12.3), Inches(0.4),
            "症状：游戏卡在等待 AI 出牌，永不响应",
            font_size=16, bold=True, color=RED)

defense = """[层 1]  canBeat 炸弹比较错误
        修复前：current.size != last.size → return false（大炸弹反而打不过！）
        修复后：大张数直接胜，张数相同再比牌点

[层 2]  AI 失败无任何回退
        修复：首选动作 → 过牌 → 最小单张（三级回退）

[层 3]  多协程无锁并发写 state.hands
        修复：每房间一把 Mutex
              修改 state 在锁内 · 广播在锁外（避免 I/O 阻塞锁）

[层 4]  三级回退均失败时游戏仍卡
        修复：broadcastForceAdvance 强制推进，同步所有客户端"""
add_code_block(s, Inches(0.5), Inches(1.85), Inches(12.3), Inches(4.4),
                defense, font_size=13)

foot = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                            Inches(0.5), Inches(6.4),
                            Inches(12.4), Inches(0.7))
foot.fill.solid()
foot.fill.fore_color.rgb = LIGHT_BG
foot.line.color.rgb = PRIMARY
add_textbox(s, Inches(0.8), Inches(6.55), Inches(12.0), Inches(0.5),
            "💡 教训：单机与联网验证逻辑必须保持一致，差异越早发现代价越低",
            font_size=14, color=PRIMARY, bold=True)

# =================================================================
# Slide 18: Fix 2 - Reconnect timing
# =================================================================
s = add_slide()
add_header(s, "六、关键技术修复 (2/3)：重连时序陷阱")

add_textbox(s, Inches(0.5), Inches(1.3), Inches(12.3), Inches(0.4),
            "症状：断线后重连，游戏永远显示「连接中」",
            font_size=16, bold=True, color=RED)

code1 = """// ❌ 修复前
ws = client.newWebSocket(request, listener)  // 异步，立即返回
// ws 仍在 CONNECTING 状态，send() 返回 false，消息静默丢弃 ↓
sessionToken?.let { send(Reconnect(it)) }    // BUG：永远失败"""
add_code_block(s, Inches(0.5), Inches(1.95), Inches(12.3), Inches(2.0),
                code1, font_size=14)

code2 = """// ✅ 修复后：在 onOpen 回调内发送，确保 ws 已 OPEN
override fun onOpen(ws: WebSocket, response: Response) {
    scope.launch {
        _connectionState.value = Connected
        sessionToken?.let { send(Reconnect(it)) }  // OK
    }
}"""
add_code_block(s, Inches(0.5), Inches(4.1), Inches(12.3), Inches(2.4),
                code2, font_size=14)

foot = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                            Inches(0.5), Inches(6.65),
                            Inches(12.4), Inches(0.6))
foot.fill.solid()
foot.fill.fore_color.rgb = LIGHT_BG
foot.line.color.rgb = PRIMARY
add_textbox(s, Inches(0.8), Inches(6.78), Inches(12.0), Inches(0.4),
            "💡 教训：「创建连接」 ≠ 「连接已建立」；异步 API 必须在回调中操作",
            font_size=14, color=PRIMARY, bold=True)

# =================================================================
# Slide 19: Fix 3 - Settlement formula alignment
# =================================================================
s = add_slide()
add_header(s, "六、关键技术修复 (3/3)：两端结算不一致")

add_textbox(s, Inches(0.5), Inches(1.3), Inches(12.3), Inches(0.4),
            "症状：已收分全是 0；游戏结束时双方分数不符合预期",
            font_size=16, bold=True, color=RED)

add_textbox(s, Inches(0.5), Inches(1.85), Inches(12.3), Inches(0.4),
            "根因：",
            font_size=15, bold=True, color=DARK)
root_causes = [
    "1. getStateForPlayer 中 collectedScore 硬编码为 0（playerScores 字段缺失）",
    "2. 结算公式服务端遗漏「输方未走完玩家已收分」",
]
y = 2.3
for r in root_causes:
    add_textbox(s, Inches(0.7), Inches(y), Inches(12.0), Inches(0.4),
                r, font_size=13, color=DARK)
    y += 0.4

formula_code = """统一公式：
  赢方得分 = 赢方所有已收
           + 输方未走完玩家（已收 + 手牌分）  ← 服务端原来漏了这项
  输方得分 = 输方已走完玩家的已收

新增字段：
  state.playerScores: MutableMap<Int, Int>
  在 handleRoundEnd 每次赢牌时同步累加 → collectedScore 实时正确"""
add_code_block(s, Inches(0.5), Inches(3.4), Inches(12.3), Inches(2.8),
                formula_code, font_size=13)

foot = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                            Inches(0.5), Inches(6.4),
                            Inches(12.4), Inches(0.7))
foot.fill.solid()
foot.fill.fore_color.rgb = RGBColor(0xE8, 0xF5, 0xE9)
foot.line.color.rgb = GREEN
add_textbox(s, Inches(0.8), Inches(6.55), Inches(12.0), Inches(0.5),
            "✓ 15 个验证用例全部通过（含提前结算、速度流、极端场景）",
            font_size=14, color=GREEN, bold=True)

# =================================================================
# Slide 20: Lessons + Action Items
# =================================================================
s = add_slide()
add_header(s, "七、工程经验 & 后续行动")

add_textbox(s, Inches(0.5), Inches(1.3), Inches(12.3), Inches(0.4),
            "核心经验",
            font_size=18, bold=True, color=PRIMARY)
lessons = [
    "1. 「修了又坏」根因 — 只修表层，没往深挖；卡死历经 4 层才解决",
    "2. 单机/联网共享逻辑必须保持一致 — 炸弹/结算/回合 3 处不一致",
    "3. AI 辅助的正确分工 — AI 静态扫描 + 人工真机验证，不可互相替代",
    "4. 协议与环境问题必须人工打通 — Android cleartext、503 等纯代码看不出",
]
y = 1.85
for l in lessons:
    add_textbox(s, Inches(0.7), Inches(y), Inches(12.0), Inches(0.4),
                l, font_size=13, color=DARK)
    y += 0.45

add_textbox(s, Inches(0.5), Inches(3.85), Inches(12.3), Inches(0.4),
            "后续建议行动",
            font_size=18, bold=True, color=PRIMARY)
actions = [
    ["🔴 P0", "自动化集成测试", "炸弹比较 / 结算 / 重连流程写服务端单元测试"],
    ["🔴 P0", "共享规则层", "canBeat + SettlementCalculator → KMP 共享模块"],
    ["🟠 P1", "监控告警", "上线 force-advance 计数指标，触发即告警"],
    ["🟡 P2", "弱网测试", "集成限速工具，系统化回归重连场景"],
    ["🟡 P2", "协议版本号", "添加 protocolVersion 强制兼容检查"],
]
add_table(s, Inches(0.5), Inches(4.4), Inches(12.3), Inches(2.6),
            ["优先级", "行动", "说明"], actions, font_size=12,
            col_widths=[Inches(1.2), Inches(2.5), Inches(8.6)])

# =================================================================
# Slide 21: Closing
# =================================================================
s = add_slide()
# Side accent bar
accent = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, Inches(0.3), SLIDE_H)
accent.fill.solid()
accent.fill.fore_color.rgb = PRIMARY
accent.line.fill.background()

add_textbox(s, Inches(1.0), Inches(2.3), Inches(11.5), Inches(1.5),
            "谢谢", font_size=84, bold=True, color=PRIMARY,
            align=PP_ALIGN.CENTER)

# Core conclusion box
box = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                            Inches(1.5), Inches(4.5),
                            Inches(10.3), Inches(1.6))
box.fill.solid()
box.fill.fore_color.rgb = LIGHT_BG
box.line.color.rgb = PRIMARY
add_textbox(s, Inches(1.7), Inches(4.7), Inches(9.9), Inches(0.5),
            "核心结论",
            font_size=18, bold=True, color=PRIMARY,
            align=PP_ALIGN.CENTER)
add_textbox(s, Inches(1.7), Inches(5.2), Inches(9.9), Inches(0.5),
            "AI 负责「静态全量审查」，人工负责「动态真机验证」",
            font_size=18, color=DARK, align=PP_ALIGN.CENTER)
add_textbox(s, Inches(1.7), Inches(5.65), Inches(9.9), Inches(0.5),
            "互补而非替代  ·  协同效率约 10 倍",
            font_size=18, bold=True, color=PRIMARY, align=PP_ALIGN.CENTER)

add_textbox(s, Inches(1.0), Inches(6.6), Inches(11.5), Inches(0.5),
            "问题 & 讨论", font_size=20, color=GRAY,
            align=PP_ALIGN.CENTER)

# =================================================================
# Add page numbers (skip cover and closing)
# =================================================================
total_slides = len(prs.slides)
for i, slide in enumerate(prs.slides, start=1):
    if i == 1 or i == total_slides:
        continue
    add_page_number(slide, i, total_slides)

# Save
output = "/home/user/AndroidAPP/docs/dev_summary.pptx"
prs.save(output)
print(f"✓ Saved {total_slides} slides to {output}")

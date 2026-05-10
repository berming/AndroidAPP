#!/usr/bin/env python3
"""Generate dev_summary.pptx — compact 16-slide version.

架构 / 协同 4 层 / L0-L4 三个图按 native PPTX 形状画（圆角矩形 + 文本 + 连接线
+ 箭头），不再嵌入 PNG。这样：
  - 中文用 PPT 自身字体（Microsoft YaHei），无 cairosvg 字体缺失问题
  - 形状可编辑（用户能在 PowerPoint 里直接拖、改文本、改色）
  - 矢量缩放无锯齿
形状的视觉布局参考 docs/dev_summary.html 里的 SVG 图（保持视觉一致性）。
"""
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.enum.shapes import MSO_SHAPE, MSO_CONNECTOR
from pptx.oxml.ns import qn
from lxml import etree

# Colors
# 主配色：华为红（品牌色 #C7000B）— 仅用于"重点突出"（封面 / 章节标题 / callout）
PRIMARY = RGBColor(0xC7, 0x00, 0x0B)
DARK = RGBColor(0x20, 0x20, 0x20)
GRAY = RGBColor(0x60, 0x60, 0x60)
# 浅色衬底改为低饱和红粉色（# FFF1F2），与 PRIMARY 同色系协调
LIGHT_BG = RGBColor(0xFF, 0xF1, 0xF2)
# 表格标题 / 非重点区分块的浅灰底（替换原来的红 / 绿底）
LIGHT_HEADER = RGBColor(0xF5, 0xF5, 0xF5)
LIGHT_HEADER_BORDER = RGBColor(0xD0, 0xD0, 0xD0)
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
              header_color=LIGHT_HEADER, alt_color=LIGHT_BG,
              font_size=BODY_SM, header_font_size=BODY_SM,
              col_widths=None, header_text_color=DARK):
    """表格。默认 header 浅灰底 + 深字（用户要求："非重点突出部分用浅灰底"）；
    重点强调的表格可显式传 header_color=PRIMARY + header_text_color=WHITE。"""
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
        run.font.color.rgb = header_text_color

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


# ─────────────────────────────────────────────────────────────────────────────
# Native PPT shape helpers — 替代 cairosvg PNG 嵌入（用户要求）
# ─────────────────────────────────────────────────────────────────────────────

def _native_box(slide, x_in, y_in, w_in, h_in, *,
                fill_rgb, border_rgb, border_pt=1.5,
                title=None, title_color=None, title_size=14, title_bold=True,
                body_lines=None, body_size=11, body_color=None, body_bold=False,
                shape_type=MSO_SHAPE.ROUNDED_RECTANGLE):
    """画一个圆角矩形 + 顶部加粗标题 + 多行正文。模仿 SVG 里的 <rect/> + <text/>。"""
    shape = slide.shapes.add_shape(
        shape_type,
        Inches(x_in), Inches(y_in), Inches(w_in), Inches(h_in),
    )
    shape.fill.solid()
    shape.fill.fore_color.rgb = fill_rgb
    shape.line.color.rgb = border_rgb
    shape.line.width = Pt(border_pt)
    # 把圆角调小一点（默认太圆）
    if shape_type == MSO_SHAPE.ROUNDED_RECTANGLE:
        try:
            shape.adjustments[0] = 0.06
        except Exception:
            pass

    tf = shape.text_frame
    tf.word_wrap = True
    tf.margin_left = Emu(110000)
    tf.margin_right = Emu(110000)
    tf.margin_top = Emu(70000)
    tf.margin_bottom = Emu(70000)

    is_first = True
    if title is not None:
        p = tf.paragraphs[0]
        p.text = ""
        p.alignment = PP_ALIGN.LEFT
        run = p.add_run()
        run.text = title
        run.font.name = FONT_CN
        run.font.size = Pt(title_size)
        run.font.bold = title_bold
        run.font.color.rgb = title_color if title_color else DARK
        is_first = False

    if body_lines:
        for line in body_lines:
            p = tf.paragraphs[0] if is_first else tf.add_paragraph()
            is_first = False
            p.text = ""
            p.alignment = PP_ALIGN.LEFT
            run = p.add_run()
            run.text = line
            run.font.name = FONT_CN
            run.font.size = Pt(body_size)
            run.font.bold = body_bold
            run.font.color.rgb = body_color if body_color else DARK

    return shape


def _native_arrow(slide, x1_in, y1_in, x2_in, y2_in, *,
                  color=GRAY, width_pt=1.5):
    """从 (x1,y1) 画一条直线到 (x2,y2)，末端带箭头。"""
    connector = slide.shapes.add_connector(
        MSO_CONNECTOR.STRAIGHT,
        Inches(x1_in), Inches(y1_in),
        Inches(x2_in), Inches(y2_in),
    )
    line = connector.line
    line.color.rgb = color
    line.width = Pt(width_pt)
    # python-pptx 的 connector 默认无箭头；在 a:ln 下显式加 a:tailEnd
    ln = line._get_or_add_ln()
    # 移除已有的 tailEnd 避免重复
    for tail in ln.findall(qn("a:tailEnd")):
        ln.remove(tail)
    tail_end = etree.SubElement(ln, qn("a:tailEnd"))
    tail_end.set("type", "triangle")
    tail_end.set("w", "med")
    tail_end.set("len", "med")
    return connector


def _native_text(slide, x_in, y_in, w_in, h_in, text, *,
                 font_size=10, color=GRAY, bold=False, align=PP_ALIGN.LEFT):
    """无背景的纯文字（用于箭头旁的小标签等）。"""
    tb = slide.shapes.add_textbox(
        Inches(x_in), Inches(y_in), Inches(w_in), Inches(h_in),
    )
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = Emu(0)
    tf.margin_right = Emu(0)
    tf.margin_top = Emu(0)
    tf.margin_bottom = Emu(0)
    p = tf.paragraphs[0]
    p.alignment = align
    p.text = ""
    run = p.add_run()
    run.text = text
    run.font.name = FONT_CN
    run.font.size = Pt(font_size)
    run.font.bold = bold
    run.font.color.rgb = color
    return tb


# ─────────────────────────────────────────────────────────────────────────────
# Diagram 1：整体架构（多端共享 + 服务端权威）
# 4 个客户端/服务端层 + Caddy 反代，箭头表示依赖 / 通信
# ─────────────────────────────────────────────────────────────────────────────

def draw_architecture(slide, x_in, y_in, w_in):
    """画架构图。水平占 w_in 英寸（建议 ≥ 6.4），高度自动计算约 5.6 英寸。"""
    # 配色（与 dev_summary.html SVG 同源）
    g_android_fill = RGBColor(0xDC, 0xFC, 0xE7)
    g_android_border = RGBColor(0x16, 0xA3, 0x4A)
    g_web_fill = RGBColor(0xDB, 0xEA, 0xFE)
    g_web_border = RGBColor(0x25, 0x63, 0xEB)
    g_shared_fill = RGBColor(0xFE, 0xF3, 0xC7)
    g_shared_border = RGBColor(0xD9, 0x77, 0x06)
    g_server_fill = RGBColor(0xFC, 0xE7, 0xF3)
    g_server_border = RGBColor(0xBE, 0x18, 0x5D)

    # 上半部两列宽度
    half = (w_in - 0.15) / 2.0  # 中间留 0.15" gap
    col_l_x = x_in
    col_r_x = x_in + half + 0.15

    # 层 1：Android（左）/ Web（右）
    _native_box(slide, col_l_x, y_in, half, 0.95,
                fill_rgb=g_android_fill, border_rgb=g_android_border,
                title=":apps:android（Android 客户端，XML 布局）",
                title_color=g_android_border, title_size=11,
                body_lines=[
                    "ui/ → GameActivity（单机）/ OnlineGameActivity（联网）",
                    "network/ → NetworkManager / RoomManager / GameSyncManager",
                    "engine/ → MultiplayerGameEngine（桥接 :shared GameEngine）",
                ], body_size=9)
    _native_box(slide, col_r_x, y_in, half, 0.95,
                fill_rgb=g_web_fill, border_rgb=g_web_border,
                title=":apps:web（Compose Multiplatform / wasmJs）",
                title_color=g_web_border, title_size=11,
                body_lines=[
                    "AppViewModel → 统一状态机；Screen.{Home/Lobby/Room/Game/Settlement}",
                    "SinglePlayerEngine → 包装 :shared GameEngine",
                    "net/ → 浏览器原生 WebSocket（@JsFun）；NetworkClient 与 Android 同职",
                ], body_size=9)

    # 层 2：:shared（全宽）
    inner_x = x_in + 0.45
    inner_w = w_in - 0.9
    shared_y = y_in + 1.10
    _native_box(slide, inner_x, shared_y, inner_w, 1.20,
                fill_rgb=g_shared_fill, border_rgb=g_shared_border,
                title=":shared（KMP：android + jvm + wasmJs）",
                title_color=g_shared_border, title_size=11,
                body_lines=[
                    "model/   Card · Deck · Player",
                    "engine/  CardRules · SettlementCalculator · GameEngine",
                    "ai/      AIPlayer",
                    "network/ GameMessage（所有 sealed class + SerializedXxx DTO）",
                    "commonTest/ CardRulesTest · SettlementCalculatorTest · GameMessageSerializationTest",
                ], body_size=9)

    # 层 3：:server（全宽）
    server_y = shared_y + 1.40
    _native_box(slide, inner_x, server_y, inner_w, 1.05,
                fill_rgb=g_server_fill, border_rgb=g_server_border,
                title=":server（Ktor + Netty，Gradle 子项目）",
                title_color=g_server_border, title_size=11,
                body_lines=[
                    "Application.kt → ServerRoomManager（房间 / AI 填充）",
                    "             └→ ServerGameManager（权威状态 / AI / 计时）",
                    "• 每房间一把 Mutex 串行化所有状态修改",
                    "• force-advance 兜底 + 三级 AI 回退 + 30s 超时",
                ], body_size=9)

    # 层 4：Caddy（半宽，居中）
    caddy_w = w_in * 0.55
    caddy_x = x_in + (w_in - caddy_w) / 2.0
    caddy_y = server_y + 1.25
    _native_box(slide, caddy_x, caddy_y, caddy_w, 0.55,
                fill_rgb=WHITE, border_rgb=GRAY, border_pt=1.0,
                title="Caddy（80 / 443 TLS）→ 反代 127.0.0.1:8080",
                title_color=DARK, title_size=10, title_bold=True,
                body_lines=["公网 ws:// 或 wss:// /game"], body_size=9,
                body_color=GRAY)

    # 箭头：Android → :shared, Web → :shared, :shared → :server, :server → Caddy
    arr_color = RGBColor(0x4B, 0x55, 0x63)
    # Android (中下) → :shared (左上)
    _native_arrow(slide,
                  col_l_x + half * 0.55, y_in + 0.95,
                  inner_x + inner_w * 0.30, shared_y,
                  color=arr_color)
    # Web (中下) → :shared (右上)
    _native_arrow(slide,
                  col_r_x + half * 0.45, y_in + 0.95,
                  inner_x + inner_w * 0.70, shared_y,
                  color=arr_color)
    # :shared → :server
    mid_x = inner_x + inner_w / 2.0
    _native_arrow(slide,
                  mid_x, shared_y + 1.20,
                  mid_x, server_y,
                  color=arr_color)
    # :server → Caddy
    _native_arrow(slide,
                  mid_x, server_y + 1.05,
                  mid_x, caddy_y,
                  color=arr_color)
    # 标签
    _native_text(slide, x_in + 0.05, shared_y + 0.50, 0.7, 0.18,
                 "依赖 :shared", font_size=8, color=GRAY)
    _native_text(slide, x_in + w_in - 0.85, shared_y + 0.50, 0.85, 0.18,
                 "依赖 :shared", font_size=8, color=GRAY)
    _native_text(slide, mid_x + 0.05, shared_y + 1.22, 0.85, 0.18,
                 "依赖 :shared", font_size=8, color=GRAY)
    _native_text(slide, mid_x + 0.05, server_y + 1.07, 1.4, 0.18,
                 "WebSocket /game", font_size=8, color=GRAY)


# ─────────────────────────────────────────────────────────────────────────────
# Diagram 2：协同的 4 个层次
# 4 个垂直堆叠的彩色边框框，标题 + 描述
# ─────────────────────────────────────────────────────────────────────────────

def draw_collab_levels(slide, x_in, y_in, w_in):
    """画协同 4 层次。高度约 3.6 英寸。"""
    rows = [
        ("Level 1　AI 执行人工指令",
         "传统：人主导。人写完整指令 → AI 按指令完成 → 等待下一条。AI 沦为'会编程的工具'",
         RGBColor(0xFE, 0xE2, 0xE2), RGBColor(0xDC, 0x26, 0x26)),
        ("Level 2　AI 提建议，人工决策",
         "审稿：人审 AI。AI 完成后输出方案 + 备选 → 人工选择 / 调整 / 驳回",
         RGBColor(0xFE, 0xD7, 0xAA), RGBColor(0xEA, 0x58, 0x0C)),
        ("Level 3　人工反馈现象，AI 自主排查 ← 本项目大量使用",
         "人工：截图 + '还卡住' / '分数错了'  AI：看代码 + 推理 + 多轮自查 + 修复",
         RGBColor(0xBB, 0xF7, 0xD0), RGBColor(0x16, 0xA3, 0x4A)),
        ("Level 4　AI 主动审查，人工验证 ← 最高效模式",
         "人工：开放性指令（'自查自纠所有问题'）  AI：全量扫描 + 输出清单 + 修复  人工：真机验证",
         RGBColor(0xBF, 0xDB, 0xFE), RGBColor(0x25, 0x63, 0xEB)),
    ]
    h_box = 0.78
    gap = 0.08
    for i, (title, desc, fill, border) in enumerate(rows):
        y = y_in + i * (h_box + gap)
        _native_box(slide, x_in, y, w_in, h_box,
                    fill_rgb=fill, border_rgb=border,
                    title=title, title_color=border, title_size=12,
                    body_lines=[desc], body_size=10)


# ─────────────────────────────────────────────────────────────────────────────
# Diagram 3：Harness L0-L4 五层架构
# ─────────────────────────────────────────────────────────────────────────────

def draw_harness_l0_l4(slide, x_in, y_in, w_in):
    """画 L0-L4 五层。高度约 4.4 英寸。"""
    layers = [
        ("L0  记忆层",
         ["CLAUDE.md（主索引）+ docs/regressions.md（Bug 冷藏库）",
          "+ docs/playbooks/{feature-development, bug-triage, ci-failure-triage}.md"],
         RGBColor(0xFE, 0xE2, 0xE2), RGBColor(0xDC, 0x26, 0x26)),
        ("L1  权限 & 钩子层",
         [".claude/settings.json + .claude/hooks/{SessionStart,PostToolUse,UserPromptSubmit}.sh",
          "+ .githooks/{pre-push, commit-msg}"],
         RGBColor(0xFE, 0xD7, 0xAA), RGBColor(0xEA, 0x58, 0x0C)),
        ("L2  命令 & 子代理",
         [".claude/commands/{test-fast, ship-check, pre-commit-scan, trace-bug, review-pr}.md",
          "+ .claude/agents/{protocol-syncer, tdd-scaffolder, pr-reviewer}.md"],
         RGBColor(0xFE, 0xF3, 0xC7), RGBColor(0xA1, 0x62, 0x07)),
        ("L3  TDD 强制层",
         ["CardRulesTest（~30）+ ServerGameManagerTest（~25）",
          "+ GameMessageSerializationTest（协议 round-trip）+ CI tdd-gate job"],
         RGBColor(0xBB, 0xF7, 0xD0), RGBColor(0x16, 0xA3, 0x4A)),
        ("L4  跨 vendor 审查",
         ["Codex bot（自动每 PR）+ pr-reviewer subagent（Opus 4.7 独立 context）",
          "+ 季度第二 vendor + 真机最后一关；4 关 PR 流程"],
         RGBColor(0xBF, 0xDB, 0xFE), RGBColor(0x25, 0x63, 0xEB)),
    ]
    h_box = 0.78
    gap = 0.08
    for i, (title, body, fill, border) in enumerate(layers):
        y = y_in + i * (h_box + gap)
        _native_box(slide, x_in, y, w_in, h_box,
                    fill_rgb=fill, border_rgb=border,
                    title=title, title_color=border, title_size=12,
                    body_lines=body, body_size=9)


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
            "沟通牌  ×  Claude Code  ×  54 PR · 有效开发 18 天",
            font_size=BODY_LG, color=DARK)

add_textbox(s, Inches(0.8), Inches(4.0), Inches(11.5), Inches(0.4),
            "本次内容（约 20 分钟）",
            font_size=BODY_MD, bold=True, color=PRIMARY)
items = [
    "①  项目背景与开发全貌    ②  架构设计",
    "③  问题发现：人工 + AI",
    "④  人机协同模式（4 层次 / 分工 / 反模式）",
    "⑤  AI 质量改进路径：反思 + 多 Claude + 跨 vendor",
    "⑥  经验总结 & 后续行动",
    "⑦  Harness 跨会话经验体系",
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
            "沟通牌：4 副牌 216 张，6 人 3v3 卡牌游戏（单机 + 联网 + Web 三模式）",
            font_size=BODY_MD, color=DARK)

add_textbox(s, Inches(0.5), Inches(1.55), Inches(6.0), Inches(0.4),
            "技术栈", font_size=BODY_LG, bold=True, color=PRIMARY)
tech = [
    ["共享逻辑", "Kotlin Multiplatform (android+jvm+wasmJs)"],
    ["Android", "Kotlin + Coroutines + Flow + OkHttp + XML"],
    ["Web", "Compose Multiplatform 1.6.10 / Wasm-JS"],
    ["服务端", "Ktor 2.3.6 + Netty + :shared 依赖"],
    ["反代部署", "Caddy 80/443 → :8080 + systemd auto-deploy"],
]
add_table(s, Inches(0.5), Inches(2.0), Inches(6.0), Inches(2.3),
            ["层", "技术"], tech, font_size=BODY_SM,
            col_widths=[Inches(1.5), Inches(4.5)])

add_textbox(s, Inches(7.0), Inches(1.55), Inches(6.0), Inches(0.4),
            "代码规模 (PR #54 后)  ·  约 19,250 行",
            font_size=BODY_LG, bold=True, color=PRIMARY)
vol = [
    [":apps:android (UI+网络)", "19 文件 · ~6,300 行"],
    [":apps:web (Compose MP)", "23 文件 · ~3,470 行"],
    [":shared (KMP commonMain)", "9 文件 · ~2,670 行"],
    [":server (Ktor)", "4 文件 · ~1,960 行"],
    ["测试 + Android XML", "24 文件 · ~4,850 行"],
]
add_table(s, Inches(7.0), Inches(2.0), Inches(6.0), Inches(2.3),
            ["模块", "规模"], vol, font_size=BODY_SM,
            col_widths=[Inches(2.4), Inches(3.6)])

add_textbox(s, Inches(0.5), Inches(4.45), Inches(12.3), Inches(0.4),
            "12 阶段时间线  ·  54 PR  ·  ~170 commit  ·  有效开发 18 天",
            font_size=BODY_LG, bold=True, color=PRIMARY)
phases = [
    ["2026-02-02 / 07~12 / 24", "单机游戏 (#1–14)", "引擎/牌型/AI/结算 · 11 轮人工 UI 反馈"],
    ["2026-04-30", "联网首版 (#16)", "服务端 + 网络层 · 一次性 6,529 行"],
    ["2026-05-01~03", "部署 (#17–31)", "CI/Gradle/cleartext/503/Lobby UI"],
    ["2026-05-07", "深度修复 (#34)", "AI 全量审查×4轮 / 8 commit / ~50 Bug"],
    ["2026-05-08", "KMP 重构 + Web (#35)", "抽 :shared / Compose MP wasmJs"],
    ["2026-05-08", "Harness H1–H5 (#36–43)", "hooks / TDD-gate / pr-reviewer"],
    ["2026-05-09", "Web UI 4 阶段 (#47–50)", "菜单/响应式/视觉/Android 同等"],
    ["2026-05-10", "AI 接管 + 单机修复 (#51–54)", "G34-G38 · PROTOCOL_VERSION 3"],
]
add_table(s, Inches(0.5), Inches(4.9), Inches(12.3), Inches(2.0),
            ["时间", "阶段", "主要内容"], phases, font_size=BODY_SM,
            col_widths=[Inches(2.6), Inches(3.7), Inches(6.0)])

# =================================================================
# Slide 3: Architecture (diagram + decisions + regrets)
# =================================================================
s = add_slide()
add_header(s, "二、架构设计：多端共享 + 服务端权威")

# 架构图：native PPTX 形状（圆角矩形 + 箭头），中文用 PPT 自身字体
draw_architecture(s, x_in=0.3, y_in=1.05, w_in=6.6)

add_textbox(s, Inches(7.0), Inches(1.05), Inches(6.0), Inches(0.4),
            "关键架构决策", font_size=BODY_LG, bold=True, color=PRIMARY)
decisions = [
    ["状态同步", "全量状态 + 单调 version"],
    ["并发模型", "每房间 Mutex（修改在锁内）"],
    ["重连机制", "sessionToken=playerId · 30s 内恢复"],
    ["AI 替补", "isAISubstitute 标记，不删玩家槽"],
    ["共享逻辑", ":shared KMP（编译期保证一致）"],
    ["协议版本", "PROTOCOL_VERSION=3 · 握手时拒老客户端"],
]
add_table(s, Inches(7.0), Inches(1.5), Inches(6.0), Inches(2.8),
            ["决策", "选择"], decisions, font_size=BODY_SM,
            col_widths=[Inches(1.5), Inches(4.5)])

add_textbox(s, Inches(7.0), Inches(4.5), Inches(6.0), Inches(0.4),
            "架构演进：从遗憾到修复", font_size=BODY_LG, bold=True, color=GREEN)
evolution = [
    ["KMP 共享模块", "✅ PR #35 + H3：编译期唯一份"],
    ["协议版本号", "✅ PR-H3：PROTOCOL_VERSION + 握手"],
    ["事件溯源", "⚪ 未规划，全量同步够用"],
]
# 表格 header 用浅灰底（与全局规则一致；不再用 GREEN 填底）
add_table(s, Inches(7.0), Inches(4.95), Inches(6.0), Inches(1.5),
            ["遗憾", "状态"], evolution, font_size=BODY_SM,
            col_widths=[Inches(2.0), Inches(4.0)])

add_callout(s, Inches(0.5), Inches(6.6), Inches(12.5), Inches(0.55),
            "核心原则：服务端权威  ·  客户端乐观响应  ·  全量状态 + version 同步",
            bg=PRIMARY, border=PRIMARY,
            font_size=BODY_MD, color=WHITE, bold=True, align=PP_ALIGN.CENTER)

# =================================================================
# Slide 4: 三、问题发现：人工 + AI（合并旧 slides 4+5+6）
# =================================================================
s = add_slide()
add_header(s, "三、问题发现：人工 + AI（~128 个，4 视角不重叠）")

# Top: 4 metric cards (compact 高度从 2.6 → 1.5)
y = 1.05
cards = [
    ("🔴 人工测试 / 反馈", "~35", "27%", "UI · 部署 · 真机崩溃", RED),
    ("🔵 Claude Code 主会话", "~55", "43%", "全量扫描 · 跨文件 · 并发", PRIMARY),
    ("🟢 Claude pr-reviewer", "~15", "12%", "独立 context · 跨文件契约", GREEN),
    ("🟡 ChatGPT Codex Bot", "~13", "10%", "语句级边界 · entropy · 文案", ORANGE),
]
x_positions = [0.5, 3.7, 6.9, 10.1]
for (title, num, pct, desc, color), x in zip(cards, x_positions):
    box = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                                Inches(x), Inches(y),
                                Inches(3.0), Inches(1.55))
    box.fill.solid()
    box.fill.fore_color.rgb = color
    box.line.fill.background()
    add_textbox(s, Inches(x), Inches(y + 0.05), Inches(3.0), Inches(0.32),
                title, font_size=11, bold=True, color=WHITE,
                align=PP_ALIGN.CENTER)
    add_textbox(s, Inches(x), Inches(y + 0.38), Inches(3.0), Inches(0.55),
                f"{num}  ({pct})", font_size=22, bold=True, color=WHITE,
                align=PP_ALIGN.CENTER)
    add_textbox(s, Inches(x), Inches(y + 1.02), Inches(3.0), Inches(0.4),
                desc, font_size=10, color=WHITE, align=PP_ALIGN.CENTER)

# Middle: Left = 人工 (3 阶段); Right = AI (Claude 4 轮 + Codex 3 个)
add_textbox(s, Inches(0.5), Inches(2.75), Inches(6.2), Inches(0.35),
            "🔴 人工发现（~35，3 阶段）",
            font_size=BODY_MD, bold=True, color=RED)
human = [
    ["单机 (11)", "UI 显示 / 游戏逻辑 / 环境兼容（炸弹重叠 · APK 签名 · 旧 Android）"],
    ["部署 (9)",  "构建编译 / 部署连通（cleartext · 503 · server URL）"],
    ["联网 (12)⭐", "📷 卡死（×3 复现）· 📷 已收分=0 · loading 卡死 · 大厅崩溃"],
]
add_table(s, Inches(0.5), Inches(3.15), Inches(6.2), Inches(2.0),
          ["阶段", "症状（合并相似项）"], human, font_size=10,
          col_widths=[Inches(1.4), Inches(4.8)])

add_textbox(s, Inches(7.0), Inches(2.75), Inches(6.0), Inches(0.35),
            "🔵 AI 发现（Claude ~55 · pr-reviewer ~15 · Codex ~13）",
            font_size=BODY_MD, bold=True, color=PRIMARY)
ai_findings = [
    ["Claude R1 综合 (~20)", "协议 4 · 会话 3 · 房间 4 · UI 6 · 其他 3"],
    ["Claude R2-3 深层 (~8)", "回合错位 · ArrayList 并发 · AI 回退缺失"],
    ["Claude R4 根因 (~7)⭐", "send() 静默失败 · Mutex 缺失 · 结算漏算"],
    ["Codex (3) P1/P2", "UUID.take(8) · loading 卡死 · UI 文案不一致"],
]
add_table(s, Inches(7.0), Inches(3.15), Inches(6.0), Inches(2.0),
          ["来源", "主要问题"], ai_findings, font_size=10,
          col_widths=[Inches(1.7), Inches(4.3)])

# Bottom callout
add_callout(s, Inches(0.5), Inches(5.35), Inches(12.4), Inches(1.7),
            "", bg=LIGHT_BG, border=PRIMARY)
add_textbox(s, Inches(0.8), Inches(5.5), Inches(12.0), Inches(0.4),
            "💡 4 视角缺一不可（盲区互补）",
            font_size=BODY_MD, bold=True, color=PRIMARY)
add_textbox(s, Inches(0.8), Inches(5.9), Inches(12.0), Inches(0.35),
            "·  人工 = 截图 / 真机崩溃 / 部署环境（动态运行时）",
            font_size=10, color=DARK)
add_textbox(s, Inches(0.8), Inches(6.22), Inches(12.0), Inches(0.35),
            "·  Claude 主会话 = 跨文件链路 / 并发陷阱（静态全量扫描）",
            font_size=10, color=DARK)
add_textbox(s, Inches(0.8), Inches(6.54), Inches(12.0), Inches(0.35),
            "·  Codex bot = 语句级边界 / entropy 漏洞 — Claude 几乎找不出",
            font_size=10, color=DARK)
add_textbox(s, Inches(0.8), Inches(6.86), Inches(12.0), Inches(0.35),
            "→  单独跑一个至少漏一类问题",
            font_size=10, bold=True, color=PRIMARY)

# =================================================================
# Slide 5: 四、人机协同：4 层次 + 分工 + 反模式（合并旧 slides 7+8）
# =================================================================
s = add_slide()
add_header(s, "四、人机协同：4 层次 · 分工 · 反模式 · 效率")

# 上半：4 层次（左）+ 分工矩阵（右）
add_textbox(s, Inches(0.4), Inches(1.0), Inches(6.5), Inches(0.35),
            "协同的 4 个层次（L3+L4 占本项目 ~70% 协同时间）",
            font_size=BODY_MD, bold=True, color=PRIMARY)
draw_collab_levels(s, x_in=0.4, y_in=1.4, w_in=6.6)

add_textbox(s, Inches(7.2), Inches(1.0), Inches(6.0), Inches(0.35),
            "实际任务分工矩阵",
            font_size=BODY_MD, bold=True, color=PRIMARY)
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
add_table(s, Inches(7.2), Inches(1.4), Inches(5.8), Inches(2.7),
            ["任务", "人工", "AI"], matrix, font_size=10,
            col_widths=[Inches(2.4), Inches(1.7), Inches(1.7)])

# 下半：反模式 + 效率 + 实践
add_textbox(s, Inches(0.4), Inches(4.95), Inches(6.5), Inches(0.35),
            "🚨 协同反模式",
            font_size=BODY_MD, bold=True, color=RED)
anti = [
    ["过度信任", "AI 说「已修复」直接合入 → 反复"],
    ["模糊指令", "「把 bug 修了」→ 只修表象"],
    ["一次到位幻想", "期待一次解决 → 本项目经历 4 轮"],
    ["跳过验证", "AI 修完直接发布 → 真机问题暴露不出来"],
]
add_table(s, Inches(0.4), Inches(5.3), Inches(6.6), Inches(1.55),
            ["反模式", "后果"], anti, font_size=10,
            col_widths=[Inches(1.7), Inches(4.9)])

add_textbox(s, Inches(7.2), Inches(4.95), Inches(5.8), Inches(0.35),
            "📊 效率数据",
            font_size=BODY_MD, bold=True, color=PRIMARY)
eff = [
    ["人工总投入", "~30 小时"],
    ["AI 等效工作量", "~300 小时"],
    ["提速比", "约 10 倍"],
    ["单次成功率", "~12%（需多轮）"],
]
add_table(s, Inches(7.2), Inches(5.3), Inches(5.8), Inches(1.55),
            ["指标", "数值"], eff, font_size=10,
            col_widths=[Inches(2.4), Inches(3.4)])

add_callout(s, Inches(0.4), Inches(6.95), Inches(12.5), Inches(0.45),
            "💡 高效协同 6 条：①症状具体  ②截图优于文字  ③允许多轮  ④关键决策人工拍板  ⑤人工守发布闸门  ⑥开放性指令激发全量审查",
            bg=LIGHT_BG, border=PRIMARY,
            font_size=10, color=PRIMARY, bold=True, align=PP_ALIGN.CENTER)

# =================================================================
# Slide 6（新编号）: 五、反思 — 为什么 AI 写的代码 Bug 不少？
# =================================================================
s = add_slide()
add_header(s, "五、反思：为什么 AI 写的代码 Bug 不少？")

add_textbox(s, Inches(0.5), Inches(1.0), Inches(12.3), Inches(0.4),
            "🔵 生成阶段必然有 Bug 的 5 个结构性原因",
            font_size=BODY_MD, bold=True, color=PRIMARY)
gen_reasons = [
    ["1", "生成 vs 验证是不同认知任务", "canBeat 套用「同张数比大小」常用模式，忽略大炸弹直胜"],
    ["2", "生成时无执行反馈，时序/并发是盲区", "WebSocket CONNECTING 时 send() 静默失败"],
    ["3", "自然语言规格隐式不完整", "「重连后游戏继续」未明确秒数和保留状态"],
    ["4", "模式匹配编码「常用 ≠ 正确」", "UUID.take(8) / ArrayList — 常用但联网场景错"],
    ["5", "跨文件一致性是结构盲区", "单机 CardRules vs 服务端 canBeat — 双份 3 处不一致"],
]
add_table(s, Inches(0.5), Inches(1.45), Inches(12.3), Inches(2.5),
            ["#", "原因", "本项目例子"], gen_reasons, font_size=BODY_SM,
            col_widths=[Inches(0.5), Inches(4.5), Inches(7.3)])

add_textbox(s, Inches(0.5), Inches(4.15), Inches(12.3), Inches(0.4),
            "🔁 多轮自审仍能发现新问题的 4 个原因",
            font_size=BODY_MD, bold=True, color=ORANGE)
multi_reasons = [
    ["1", "每轮在问不同的问题", "R1:「代码整洁吗？」 R4:「为什么 3 次修了还卡？」"],
    ["2", "自审有确认偏差，用户「还卡住」才打破", "4 轮逐层挖到 send() 静默失败"],
    ["3", "症状被消除后下一层根因才暴露", "canBeat → 回退链 → Mutex → 兜底 必须按序解锁"],
    ["4", "注意力有限，单轮无法全部仔细看", "1M 上下文 ≠ 全部能仔细分析"],
]
add_table(s, Inches(0.5), Inches(4.6), Inches(12.3), Inches(2.0),
            ["#", "原因", "本项目体现"], multi_reasons, font_size=BODY_SM,
            col_widths=[Inches(0.5), Inches(4.5), Inches(7.3)])

add_callout(s, Inches(0.5), Inches(6.75), Inches(12.4), Inches(0.55),
            "💡 启示：AI 写代码不是「一次到位」，而是「快速迭代到位」 — 接受多轮，但用工具/流程压低轮次",
            bg=LIGHT_BG, border=PRIMARY,
            font_size=BODY_SM, color=PRIMARY, bold=True, align=PP_ALIGN.CENTER)

# =================================================================
# Slide 10: 5 Multi-Claude Collaboration Patterns
# =================================================================
s = add_slide()
add_header(s, "五、多 Claude 模型协同的 5 种模式")

# Top: available models
add_textbox(s, Inches(0.5), Inches(1.0), Inches(12.3), Inches(0.4),
            "可用模型",
            font_size=BODY_MD, bold=True, color=PRIMARY)
models = [
    ["Claude Opus 4.7（1M）", "推理深度最强，全局视角", "架构 / 根因分析 / 深度审查"],
    ["Claude Sonnet 4.6", "平衡速度与能力", "主要实现 / PR review"],
    ["Claude Haiku 4.5", "极快、便宜", "静态扫描 / 测试生成 / 批量检查"],
]
add_table(s, Inches(0.5), Inches(1.45), Inches(12.3), Inches(1.6),
            ["模型", "特点", "适合的角色"], models, font_size=BODY_SM,
            col_widths=[Inches(3.0), Inches(4.5), Inches(4.8)])

# Bottom: 5 patterns
add_textbox(s, Inches(0.5), Inches(3.25), Inches(12.3), Inches(0.4),
            "5 种协同模式（按可行性排序）",
            font_size=BODY_MD, bold=True, color=PRIMARY)
patterns = [
    ["①", "开新会话 = 等价的「另一个 Claude」",
     "上下文重置打破自审偏差，零成本，本项目 4 轮自查实质即此 — 推荐"],
    ["②", "Generator / Reviewer 分工",
     "Opus 架构 → Sonnet 实现 → Haiku 静态扫描 → Opus（新会话）根因审查"],
    ["③", "对抗式审查（Adversarial）",
     "A 实现 / B 攻击「找崩溃场景」 / C 仲裁 — 适合金钱/分数/安全代码"],
    ["④", "TDD 反向流",
     "Haiku 先写边界用例 → Sonnet 实现到通过 → Opus 审查覆盖率 — 最压低 Bug"],
    ["⑤", "Self-Consistency 校验",
     "同一任务 Opus 跑 3 次对比，差异处标记为「不确定区」 — 关键代码"],
]
add_table(s, Inches(0.5), Inches(3.7), Inches(12.3), Inches(3.4),
            ["#", "模式", "做法 / 适用场景"], patterns, font_size=BODY_SM,
            col_widths=[Inches(0.5), Inches(3.5), Inches(8.3)])

# =================================================================
# Slide 11: Coverage ceiling + Cross-vendor + Recommended workflow
# =================================================================
s = add_slide()
add_header(s, "五、协同天花板 & 推荐工作流")

# Top: ceiling
add_textbox(s, Inches(0.5), Inches(1.0), Inches(12.3), Inches(0.4),
            "⚠ 多 Claude 协同的天花板：相关性盲区",
            font_size=BODY_MD, bold=True, color=RED)
add_textbox(s, Inches(0.7), Inches(1.45), Inches(12.0), Inches(0.4),
            "原因：所有 Claude 共享同一训练语料 + 同一训练目标 + 同一架构",
            font_size=BODY_SM, color=DARK)
add_textbox(s, Inches(0.7), Inches(1.85), Inches(12.0), Inches(0.4),
            "→ Codex Bot 找到的 3 个问题（UUID 截断 / loading 卡死 / UI 不一致）多 Claude 也大概率找不出",
            font_size=BODY_SM, color=GRAY)

# Middle: Cross-vendor strategy
add_textbox(s, Inches(0.5), Inches(2.4), Inches(12.3), Inches(0.4),
            "📊 真正补盲区的策略对比",
            font_size=BODY_MD, bold=True, color=PRIMARY)
cross = [
    ["多个 Claude 实例", "全局架构 + 逻辑链路（多角度）", "中等"],
    ["Claude + Codex（OpenAI）", "增加细粒度风险点", "较高"],
    ["Claude + Codex + Gemini", "不同训练目标，覆盖最广", "最高"],
    ["Claude + Detekt / SpotBugs / kover", "规则化盲区", "必要"],
]
add_table(s, Inches(0.5), Inches(2.85), Inches(12.3), Inches(1.8),
            ["组合", "找到的问题类型", "互补程度"], cross, font_size=BODY_SM,
            col_widths=[Inches(4.5), Inches(5.5), Inches(2.3)])

# Bottom: Recommended workflow
add_textbox(s, Inches(0.5), Inches(4.85), Inches(12.3), Inches(0.4),
            "🚀 给本项目的推荐工作流（如果重做联网模块）",
            font_size=BODY_MD, bold=True, color=GREEN)

workflow = """开发：  Opus 4.7 (1M)  架构 + 协议    Sonnet 4.6  实现    Haiku 4.5  静态扫描

PR 4 关：  Claude PR Review (新会话)  +  Codex Bot  +  Detekt  +  人工真机验证

测试驱动：  关键路径（结算 / 协议 / 并发）必须 TDD，Haiku 4.5 批量生成边界用例"""
add_code_block(s, Inches(0.5), Inches(5.3), Inches(12.3), Inches(1.6),
                workflow, font_size=BODY_SM)

add_callout(s, Inches(0.5), Inches(7.0), Inches(12.4), Inches(0.4),
            "💡 单 Claude 多轮 = 时间换覆盖率  ·  多 Claude = 视角换覆盖率  ·  多 vendor + 静态 + 真机 = 异构换覆盖率（边际收益最大）",
            bg=LIGHT_BG, border=PRIMARY,
            font_size=10, color=PRIMARY, bold=True, align=PP_ALIGN.CENTER)

# =================================================================
# （第七章「关键技术修复」按用户要求删除）
# =================================================================

# =================================================================
# Slide 9（新编号）: 六、经验总结 & 后续行动
# =================================================================
s = add_slide()
add_header(s, "六、经验总结 & 后续行动")

add_textbox(s, Inches(0.5), Inches(1.05), Inches(6.2), Inches(0.4),
            "🎯 核心经验",
            font_size=BODY_LG, bold=True, color=PRIMARY)
lessons = [
    "1. 「修了又坏」的根因：只修表层，没往深挖（需要分层根因审查）",
    "2. 跨端共享逻辑必须用 KMP 强制（已实现 :shared 模块）",
    "3. AI 静态扫描 + 人工真机 + Codex bot 不可互相替代（4 视角全跑）",
    "4. 协议 / 环境 / 部署拓扑变更必须人工打通（grep 所有客户端）",
    "5. 大特性 Phase 分段提交（协议 → 主客户端 → 次客户端）",
    "6. delay() 之后必须重检所有依赖项，不只 currentPlayerIndex",
]
y = 1.5
for l in lessons:
    add_textbox(s, Inches(0.6), Inches(y), Inches(6.2), Inches(0.35),
                l, font_size=BODY_SM, color=DARK)
    y += 0.4

add_textbox(s, Inches(7.0), Inches(1.05), Inches(6.0), Inches(0.4),
            "🚀 后续建议行动（已部分落地）",
            font_size=BODY_LG, bold=True, color=PRIMARY)
actions = [
    ["✅ 已实现", ":shared KMP 共享模块（PR #35）"],
    ["✅ 已实现", "PROTOCOL_VERSION + 握手（PR-H3）"],
    ["✅ 已实现", "harness L0-L4（hooks/playbook/regression）"],
    ["✅ 已实现", "tdd-gate CI 硬关（PR-H2）"],
    ["✅ 已实现", "pr-reviewer subagent（PR-H5）"],
    ["⚪ 待规划", "force-advance 监控告警 / 弱网 e2e"],
]
add_table(s, Inches(7.0), Inches(1.5), Inches(6.0), Inches(2.6),
            ["状态", "行动"], actions, font_size=BODY_SM,
            col_widths=[Inches(1.5), Inches(4.5)])

add_callout(s, Inches(0.5), Inches(4.4), Inches(12.4), Inches(2.3),
            "", bg=LIGHT_BG, border=PRIMARY)
add_textbox(s, Inches(0.8), Inches(4.55), Inches(12.0), Inches(0.5),
            "🏆 全程交付成果",
            font_size=BODY_LG, bold=True, color=PRIMARY)
add_textbox(s, Inches(0.8), Inches(5.05), Inches(12.0), Inches(0.4),
            "·  全程 54 PR / ~170 commit  ·  有效开发 18 天  ·  发现 ~128 个问题",
            font_size=BODY_SM, color=DARK)
add_textbox(s, Inches(0.8), Inches(5.45), Inches(12.0), Inches(0.4),
            "·  Android + Web 双端  ·  KMP 共享模块（编译期保证一致）  ·  Caddy 自托管自动部署",
            font_size=BODY_SM, color=DARK)
add_textbox(s, Inches(0.8), Inches(5.85), Inches(12.0), Inches(0.4),
            "·  游戏永不卡死 ✓  ·  重连无缝恢复 ✓  ·  两端结算一致 ✓  ·  PROTOCOL_VERSION = 3 ✓",
            font_size=BODY_SM, color=GREEN, bold=True)
add_textbox(s, Inches(0.8), Inches(6.3), Inches(12.0), Inches(0.4),
            "💡 核心结论：4 视角覆盖率 + harness 跨会话沉淀 = 教训不浪费",
            font_size=BODY_MD, bold=True, color=PRIMARY)

add_textbox(s, Inches(0.5), Inches(6.85), Inches(12.3), Inches(0.4),
            "谢谢  ·  问题 & 讨论",
            font_size=BODY_MD, bold=True, color=GRAY,
            align=PP_ALIGN.CENTER)

# =================================================================
# Slide 10（新编号）: 七、Harness 跨会话经验（合并旧 slides 14+15+16，
#                     已删除 wasmJs 陷阱 / 实战教训 / delay race / 核心原则）
# =================================================================
s = add_slide()
add_header(s, "七、Harness 跨会话经验体系")

# 左半：Phase 分段 + Codex/Claude 互补
add_textbox(s, Inches(0.4), Inches(1.0), Inches(6.5), Inches(0.35),
            "📦 大特性的 Phase 分段提交",
            font_size=BODY_MD, bold=True, color=PRIMARY)
phase_rows = [
    ["Phase 1", "协议 + 服务端 + 单测", "底层稳后再动客户端"],
    ["Phase 2", "主客户端（Android）", "一份吃通验证 server"],
    ["Phase 3", "次客户端（Web）+ 跨端测", "最后补齐"],
]
add_table(s, Inches(0.4), Inches(1.4), Inches(6.6), Inches(1.55),
            ["Phase", "内容", "价值"], phase_rows, font_size=10,
            col_widths=[Inches(1.0), Inches(2.8), Inches(2.8)])

add_textbox(s, Inches(0.4), Inches(3.05), Inches(6.6), Inches(0.35),
            "🔍 Codex bot 与 Claude /review-pr 盲区互补",
            font_size=BODY_MD, bold=True, color=PRIMARY)
reviewer_rows = [
    ["Claude /review-pr", "跨文件契约 / 文档与代码不一致"],
    ["Codex bot", "语句级边界 / 数值溢出 / UI 文案"],
    ["→ 必须都跑", "盲区不重叠：单独跑漏一类问题"],
]
add_table(s, Inches(0.4), Inches(3.45), Inches(6.6), Inches(1.55),
            ["Reviewer", "擅长找到的问题"], reviewer_rows, font_size=10,
            col_widths=[Inches(2.0), Inches(4.6)])

# 右半：L0-L4 体系
add_textbox(s, Inches(7.2), Inches(1.0), Inches(6.0), Inches(0.35),
            "🏗️  Harness 5 层防御体系（PR-H1..H5 实战搭建）",
            font_size=BODY_MD, bold=True, color=PRIMARY)
# native PPTX 形状（5 层堆叠框），中文用 FONT_CN 渲染
draw_harness_l0_l4(s, x_in=7.2, y_in=1.4, w_in=5.9)

add_callout(s, Inches(0.4), Inches(5.15), Inches(12.5), Inches(0.5),
            "💎 核心：harness 让教训跨 session 沉淀，新人 / 新 AI 不需要每次从头踩坑",
            bg=PRIMARY, border=PRIMARY,
            font_size=BODY_MD, color=WHITE, bold=True, align=PP_ALIGN.CENTER)

# =================================================================
total = len(prs.slides)
for i, slide in enumerate(prs.slides, start=1):
    if i == 1:
        continue
    add_page_number(slide, i, total)

output = "/home/user/AndroidAPP/docs/dev_summary.pptx"
prs.save(output)
print(f"✓ Saved {total} slides to {output}")

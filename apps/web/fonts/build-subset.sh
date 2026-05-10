#!/usr/bin/env bash
#
# 重新生成 Web 客户端的中文字体子集
#
# 用途：当你想给字体改大小 / 换字体 / 换覆盖范围时跑这个脚本。
# 默认产出：apps/web/src/wasmJsMain/resources/fonts/NotoSansSC-Subset.ttf
#
# 用法：
#   bash apps/web/fonts/build-subset.sh                 # 默认 GB2312（~3MB，7540 字）
#   SUBSET_MODE=project bash apps/web/fonts/build-subset.sh   # 仅项目用到的字（~200KB）
#   SUBSET_MODE=gb2312  bash apps/web/fonts/build-subset.sh   # 显式 GB2312
#
# 依赖（macOS / Linux）：
#   pip3 install fonttools brotli
#
# 字体来源 / License：
#   Noto Sans CJK SC（SIL Open Font License 1.1，可商用 / 嵌入 / 修改 / 再分发）
#   下载地址：https://github.com/googlefonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf

set -euo pipefail

SUBSET_MODE="${SUBSET_MODE:-gb2312}"

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
WEB_RESOURCES="$REPO_ROOT/apps/web/src/wasmJsMain/resources/fonts"
FONTS_DIR="$REPO_ROOT/apps/web/fonts"
SOURCE_FONT="/tmp/NotoSansCJK-SC.otf"

mkdir -p "$WEB_RESOURCES"

# 1. 下载源字体（16MB；只有不存在或文件大小异常时才下）
if [[ ! -f "$SOURCE_FONT" ]] || [[ $(stat -f%z "$SOURCE_FONT" 2>/dev/null || stat -c%s "$SOURCE_FONT") -lt 5000000 ]]; then
    echo "==> 下载 Noto Sans CJK SC 源字体（16MB）..."
    curl -sSL --max-time 300 -o "$SOURCE_FONT" \
        https://github.com/googlefonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf
fi

# 2. 准备 codepoint 列表（按 mode）
case "$SUBSET_MODE" in
    project)
        echo "==> mode=project：扫源码提取实际用到的 CJK 字符"
        grep -roP '[\x{4e00}-\x{9fff}\x{3000}-\x{303f}\x{ff00}-\x{ffef}]' \
            "$REPO_ROOT/apps/web/src/wasmJsMain/kotlin" \
            | grep -oP '[\x{4e00}-\x{9fff}\x{3000}-\x{303f}\x{ff00}-\x{ffef}]' \
            | sort -u | tr -d '\n' > "$FONTS_DIR/chars-project.txt"
        echo "    项目用到 $(wc -m < "$FONTS_DIR/chars-project.txt") 个 CJK 字符"
        SUBSET_ARGS=("--text-file=$FONTS_DIR/chars-project.txt"
                     "--unicodes=U+0020-007E,U+00A0-00FF,U+2000-206F")
        ;;
    gb2312)
        echo "==> mode=gb2312：GB2312 全集（7540 字符 + ASCII）"
        python3 -c "
codepoints = set()
for hi in range(0xA1, 0xFF):
    for lo in range(0xA1, 0xFF):
        try:
            ch = bytes([hi, lo]).decode('gb2312')
            codepoints.add(ord(ch))
        except UnicodeDecodeError:
            pass
for cp in range(0x0020, 0x007F):
    codepoints.add(cp)
with open('$FONTS_DIR/chars-gb2312.txt', 'w') as f:
    for cp in sorted(codepoints):
        f.write(f'U+{cp:04X}\n')
"
        SUBSET_ARGS=("--unicodes-file=$FONTS_DIR/chars-gb2312.txt")
        ;;
    *)
        echo "ERROR: 未知 SUBSET_MODE=$SUBSET_MODE（合法值：project / gb2312）" >&2
        exit 1
        ;;
esac

# 3. 跑 pyftsubset
echo "==> 生成子集字体到 $WEB_RESOURCES/NotoSansSC-Subset.ttf"
pyftsubset "$SOURCE_FONT" \
    "${SUBSET_ARGS[@]}" \
    --output-file="$WEB_RESOURCES/NotoSansSC-Subset.ttf" \
    --no-hinting \
    --desubroutinize \
    --drop-tables=BASE,JSTF,DSIG

echo ""
echo "✅ 完成"
ls -lh "$WEB_RESOURCES/NotoSansSC-Subset.ttf"
echo ""
echo "下一步：./gradlew :apps:web:wasmJsBrowserDistribution 重新打包；deploy.yml push 后会随产物上传"

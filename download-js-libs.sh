#!/usr/bin/env bash
#
# YuMark 前端依赖管理：完整性校验 / 重新下载
#
# 渲染管线（预览、导出、AI 气泡、更新日志）依赖 6 个随源码入库（vendored）的第三方库。
# 它们运行在开了 JavaScript 的 WebView 里，因此属于**供应链攻击面**：一旦被替换成带后门的
# 版本，CSP 与 DOMPurify 都拦不住（净化器本身就在这批文件里）。
#
# 用法：
#   ./download-js-libs.sh verify        # 默认。离线校验在库文件哈希，可直接进 CI
#   ./download-js-libs.sh download      # 按锁定版本下载到临时目录并逐个比对哈希
#   ./download-js-libs.sh download --force   # 比对后无论是否匹配都覆盖在库文件（需人工复核 diff）
#
# 升级依赖的正确流程：改 PINS 里的版本 → download → 人工复核 diff → --force 覆盖
# → 用新哈希更新 SHA256 → 提交。绝不要在不更新哈希的情况下换文件。
set -uo pipefail

ASSETS_DIR="app/src/main/assets/raw"

# ─── 锁定版本与来源 ────────────────────────────────────────────────────────────
# 格式：<在库文件名>|<下载 URL>
PINS=(
  "markedjs.js|https://cdn.jsdelivr.net/npm/marked@11.0.0/marked.min.js"
  "katexjs.js|https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js"
  "katexcss.css|https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.css"
  "katex-auto-render.js|https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/contrib/auto-render.min.js"
  "mermaidjs.js|https://cdn.jsdelivr.net/npm/mermaid@10.6.1/dist/mermaid.min.js"
  "prism.js|https://cdn.jsdelivr.net/npm/prismjs@1.29.0/prism.min.js"
  "prism.css|https://cdn.jsdelivr.net/npm/prismjs@1.29.0/themes/prism.min.css"
  "purify.js|https://cdn.jsdelivr.net/npm/dompurify@3.4.14/dist/purify.min.js"
)

# ─── 在库文件的期望 SHA256 ────────────────────────────────────────────────────
# 这些是**当前入库副本**的实测哈希，不是从上游文档抄来的。
# 注意 prism.js/prism.css：jsDelivr 对没有预压缩产物的包做**实时 Terser 压缩**
# （见文件头 "Minified by jsDelivr ... Do NOT use SRI with dynamically generated files"），
# 换一个 Terser 版本字节就会变，因此 download 模式下这两项出现不匹配属预期，需人工看 diff。
SHA256=(
  "markedjs.js|6462db804935e438614c5ba188449f38e03e08ee7205d71af92a23c153e94be6"
  "katexjs.js|dc84b296ec3e884de093158f760fd9d45b6c7abe58b5381557f4e138f46a58ae"
  "katexcss.css|ecd433bebc95088424562392dc63dd2c3c87b66d671ccae6ef1cf13b4f45d79f"
  "katex-auto-render.js|9cb8dacfc086c2966c9ec4ba54f4a2dc43b7cbe2b33cec1a2743d886c7fb47a7"
  "mermaidjs.js|b2fe838387d46a6bdc36a83f5a14d60ed981df097afac87585065b619263a76a"
  "prism.js|b860f960223a2e10de9e6d55316c7c1abbb5fe3981ad34e9b8045c3285e7e471"
  "prism.css|928e23e6b9fcef82c5f1d1f05b6f7fc5a6e187c60195e59fbf16fc9d071ee057"
  "purify.js|c2f26ea4fc0d88141c9aa430eb515ac86fce59418ceebd85fa475b87a8d6c3e6"
)

# KaTeX 字体（fonts/ 下 20 个 woff2）不逐个列哈希，用「排序后逐文件摘要再摘要」的聚合摘要覆盖。
FONTS_DIGEST="c0abb2c7a38945547668c52488219977c0c31d7abe0dea0cc3f010807f0e0684"

# ─── 工具函数 ─────────────────────────────────────────────────────────────────
sha_of() { sha256sum "$1" | cut -d' ' -f1; }

expected_for() {
  local name="$1" row
  for row in "${SHA256[@]}"; do
    [ "${row%%|*}" = "$name" ] && { echo "${row#*|}"; return 0; }
  done
  return 1
}

fonts_digest_of() {
  # 在 fonts/ 内部计算，使摘要与脚本的调用路径无关
  ( cd "$ASSETS_DIR/fonts" && find . -type f | LC_ALL=C sort | xargs sha256sum | sha256sum | cut -d' ' -f1 )
}

# ─── verify：离线校验在库文件 ──────────────────────────────────────────────────
do_verify() {
  local fail=0 row name want got
  echo "校验 $ASSETS_DIR （锁定版本的在库副本）"
  for row in "${SHA256[@]}"; do
    name="${row%%|*}"; want="${row#*|}"
    if [ ! -f "$ASSETS_DIR/$name" ]; then
      printf '  %-24s 缺失\n' "$name"; fail=1; continue
    fi
    got="$(sha_of "$ASSETS_DIR/$name")"
    if [ "$got" = "$want" ]; then
      printf '  %-24s OK\n' "$name"
    else
      printf '  %-24s 不匹配\n    期望 %s\n    实际 %s\n' "$name" "$want" "$got"
      fail=1
    fi
  done

  if [ -d "$ASSETS_DIR/fonts" ]; then
    got="$(fonts_digest_of)"
    if [ "$got" = "$FONTS_DIGEST" ]; then
      printf '  %-24s OK (聚合)\n' "fonts/"
    else
      printf '  %-24s 不匹配\n    期望 %s\n    实际 %s\n' "fonts/" "$FONTS_DIGEST" "$got"
      fail=1
    fi
  else
    printf '  %-24s 缺失\n' "fonts/"; fail=1
  fi

  if [ "$fail" -ne 0 ]; then
    echo
    echo "校验失败：在库第三方库与锁定哈希不一致。"
    echo "这可能是有意升级（请同步更新本脚本的 SHA256），也可能是被篡改（请立刻核查 git diff）。"
    return 1
  fi
  echo "全部通过。"
}

# ─── download：按锁定版本下载到暂存目录并比对 ─────────────────────────────────
do_download() {
  local force="$1" tmp row name url want got mismatch=0
  command -v curl >/dev/null 2>&1 || { echo "缺少 curl"; return 1; }
  mkdir -p "$ASSETS_DIR"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT

  for row in "${PINS[@]}"; do
    name="${row%%|*}"; url="${row#*|}"
    printf '下载 %-24s ' "$name"
    if ! curl -fsSL --proto '=https' --tlsv1.2 -o "$tmp/$name" "$url"; then
      echo "失败: $url"; return 1
    fi
    got="$(sha_of "$tmp/$name")"
    want="$(expected_for "$name" || true)"
    if [ "$got" = "$want" ]; then
      echo "OK"
    else
      echo "哈希不匹配"
      printf '    期望 %s\n    实际 %s\n' "${want:-<未记录>}" "$got"
      mismatch=1
    fi
  done

  if [ "$mismatch" -ne 0 ] && [ "$force" != "1" ]; then
    echo
    echo "存在不匹配项，未覆盖任何在库文件。下载结果留在：$tmp"
    echo "请人工 diff 后再决定；确认无误后用 download --force 覆盖，并更新本脚本的 SHA256。"
    trap - EXIT
    return 1
  fi

  cp -f "$tmp"/* "$ASSETS_DIR"/
  echo "已覆盖 $ASSETS_DIR 下的第三方库。"
  echo "提醒：fonts/ 不在下载范围内（随 KaTeX 发行包手工放置），其聚合摘要仍由 verify 覆盖。"
}

# ─── 入口 ─────────────────────────────────────────────────────────────────────
cmd="${1:-verify}"
force=0
[ "${2:-}" = "--force" ] && force=1

case "$cmd" in
  verify)   do_verify ;;
  download) do_download "$force" ;;
  *)        echo "用法: $0 [verify|download [--force]]"; exit 2 ;;
esac

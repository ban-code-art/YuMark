/*
 * YuMark 预览渲染器（外置脚本）
 *
 * 安全约束（改动前必读）：
 *  1. 本文件必须保持为「外置脚本」。renderer.html 的 CSP 未开启 'unsafe-inline'，
 *     任何内联 <script> 或 on* 事件属性都不会执行。把逻辑搬回 HTML 会静默失效。
 *  2. 所有由文档内容生成的 HTML 必须经 sanitizeHtml() 后才能写入 DOM。
 *     净化点位于 marked.parse() 之后、数学占位符回填之前——占位符此时是纯文本，
 *     可安全穿过 DOMPurify；KaTeX 输出在净化之后生成，属可信内容不应被剥离。
 *  3. DOMPurify 缺失时降级为纯文本渲染（fail-closed），绝不输出未净化 HTML。
 */
(function () {
'use strict';

/* ==================== 1. HTML 净化层（适配共享模块） ==================== */
// 净化配置集中在 raw/ym-sanitize.js，本文件只做适配。
// 不要在这里另起一套 DOMPurify 配置——两份配置必然漂移，其中一份会变成漏洞。
var SANI = window.YuMarkSanitize || null;
var HAS_SANITIZER = !!(SANI && SANI.available);

function escapeHtml(s) {
    if (SANI) return SANI.escapeHtml(s);
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// 唯一净化入口。净化器缺失时抛错，由调用方降级为纯文本（fail-closed）
function sanitizeHtml(html) {
    if (!HAS_SANITIZER) throw new Error('YM_NO_SANITIZER');
    return SANI.sanitize(html);
}

/* ==================== 2. 第三方库初始化 ==================== */

if (typeof marked !== 'undefined') {
    marked.setOptions({ breaks: true, gfm: true });
}

var mermaidInited = false;
function initMermaid() {
    if (mermaidInited || typeof mermaid === 'undefined') return;
    try {
        // securityLevel:'strict' 显式声明：图表标签走 mermaid 内置净化，禁用 click/JS 交互
        mermaid.initialize({ startOnLoad: false, securityLevel: 'strict' });
        mermaidInited = true;
    } catch (e) {
        console.log('[YuMark] mermaid init failed: ' + e.message);
    }
}

// mermaid 延迟加载完成后，补渲染已存在的图表
window.__mermaidLoaded = function () {
    try {
        initMermaid();
        var contentEl = document.getElementById('content');
        if (contentEl && typeof mermaid !== 'undefined') {
            mermaid.run({ nodes: contentEl.querySelectorAll('.language-mermaid') });
        }
    } catch (e) { console.log('mermaid late run: ' + e.message); }
};

// CSP 禁止内联 on* 属性，改为脚本注册（本文件在 head 中位于 mermaid 标签之后，元素已存在）
(function bindMermaidLoad() {
    var el = document.getElementById('mermaid-script');
    if (el) {
        el.addEventListener('load', function () { window.__mermaidLoaded(); });
        el.addEventListener('error', function () {
            console.log('[YuMark] mermaid asset failed to load');
        });
    }
    // 兜底：若 load 事件因任何原因丢失，DOM 就绪后再补一次
    window.addEventListener('DOMContentLoaded', function () {
        if (!mermaidInited && typeof mermaid !== 'undefined') window.__mermaidLoaded();
    });
})();
/* ==================== 3. 相对路径图片解析 ==================== */
// Android 侧通过 setImageResolver 提供文档所在目录的基址：
//  - 导入库文档: prefix=file:///...import_assets/ 的 URL, base=库内目录链, encodeAll=false（逐段编码）
//  - 外部工作区文档: prefix=content://.../document/ 前缀, base=父目录 documentId, encodeAll=true（整体编码）
//  - 另有 appPrefix: 工具栏「从相册选择」存进应用私有 images/ 的图，与文档在哪个目录无关
window.__imageResolver = null;

// 应用自管图片的引用形态：images/<UUID>.<ext>，由 ImageRepositoryImpl 生成。
// 为什么要卡到 UUID 这么细，而不是只看 "images/" 开头：Markdown 工程里 images/ 是最常见的
// 资源目录名，导入库文档正文里的 images/pic.png 指的是它自己那份被镜像到 import_assets/
// 下的资源，若被 appPrefix 抢走就会从「能显示」变成「显示不出来」。UUID 形态是本应用自己
// 生成的，撞上等于用户手写了一个 36 位 UUID 当文件名，可以忽略；万一真撞上，落回文档相对
// 解析，与从前行为一致。
var APP_IMAGE_RE =
    /^images\/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.[A-Za-z0-9]+$/;

// 把相对引用解析到 base 目录下；".." 不会越过根（防路径穿越）
function joinImagePath(base, rel) {
    rel = rel.replace(/\\/g, '/');
    var rootPrefix = '';
    var m = base.match(/^([^/]*:)([\s\S]*)$/);
    if (m) { rootPrefix = m[1]; base = m[2]; }
    var segs = base ? base.split('/').filter(function (s) { return !!s; }) : [];
    if (rel.charAt(0) === '/') segs = [];
    var parts = rel.split('/');
    for (var i = 0; i < parts.length; i++) {
        var p = parts[i];
        if (!p || p === '.') continue;
        if (p === '..') { if (segs.length) segs.pop(); continue; }
        segs.push(p);
    }
    return rootPrefix + segs.join('/');
}

// 逐段编码：路径分隔符要留着，段内的空格/中文/# 要转义
function encodePathSegments(p) {
    return p.split('/').map(encodeURIComponent).join('/');
}

function resolveImages() {
    var cfg = window.__imageResolver;
    var contentEl = document.getElementById('content');
    if (!cfg || !contentEl) return;
    var imgs = contentEl.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
        var img = imgs[i];
        // 原始引用只记录一次，重渲染/换基址后仍可重新解析
        var raw = img.getAttribute('data-yumark-src');
        if (raw === null) {
            raw = img.getAttribute('src') || '';
            img.setAttribute('data-yumark-src', raw);
        }
        if (!raw || /^(https?:|data:|file:|content:|blob:)/i.test(raw)) continue;
        var rel;
        try { rel = decodeURIComponent(raw); } catch (e) { rel = raw; }
        // 应用自管图片先试：普通库文档根本没有 prefix/base（文档正文存在数据库里，
        // 没有"所在目录"这回事），只有这一条路能解析出来。
        var appRel = joinImagePath('', rel);
        if (cfg.appPrefix && APP_IMAGE_RE.test(appRel)) {
            img.setAttribute('src', cfg.appPrefix + encodePathSegments(appRel));
            continue;
        }
        if (!cfg.prefix) continue;
        var joined = joinImagePath(cfg.base || '', rel);
        var src = cfg.encodeAll
            ? cfg.prefix + encodeURIComponent(joined)
            : cfg.prefix + encodePathSegments(joined);
        img.setAttribute('src', src);
    }
}

window.setImageResolver = function (cfg) {
    window.__imageResolver = cfg;
    resolveImages();
};
/* ==================== 4. 主渲染入口 ==================== */

// 占位符带随机后缀：防止文档正文里恰好写出占位符字面量，被当成公式回填
function makeTag(prefix) {
    var n = Math.random().toString(36).slice(2, 10).toUpperCase().replace(/[^A-Z0-9]/g, '');
    return prefix + (n || 'X') + 'Q';
}

// 渲染失败/无净化器时的纯文本降级：只用 DOM API，不拼 HTML
function renderPlainFallback(contentEl, notice, text) {
    while (contentEl.firstChild) contentEl.removeChild(contentEl.firstChild);
    if (notice) {
        var warn = document.createElement('div');
        warn.className = 'yumark-render-error';
        warn.textContent = notice;
        contentEl.appendChild(warn);
    }
    if (text != null) {
        var pre = document.createElement('pre');
        pre.textContent = String(text);
        contentEl.appendChild(pre);
    }
}

window.renderMarkdown = function (markdownText) {
    function log(msg) {
        console.log(msg);
    }

    var contentElOuter = document.getElementById('content');

    // 净化器缺失 → fail-closed：只渲染纯文本，绝不输出未净化 HTML
    if (!HAS_SANITIZER) {
        log('ERROR: DOMPurify missing, falling back to plain text');
        if (contentElOuter) {
            renderPlainFallback(contentElOuter,
                '渲染器安全组件缺失，已降级为纯文本显示（raw/purify.js 未加载）', markdownText);
        }
        return false;
    }

    try {
        if (typeof marked === 'undefined') {
            throw new Error('marked is undefined');
        }

        var CODE_TAG = makeTag('YMCODE');
        var MATH_TAG = makeTag('YMMATH');
        var IMG_TAG = makeTag('YMIMG');
        // 步骤1: 先保护代码区域，避免代码里的 $ 被当成公式
        const codeStore = [];
        let processedText = markdownText.replace(/```[\s\S]*?```|~~~[\s\S]*?~~~|``[^\n]*?``|`[^`\n]*`/g, function (match) {
            codeStore.push(match);
            return CODE_TAG + (codeStore.length - 1) + 'E';
        });

        // 步骤1.5: 暂存图片引用（含目标归一化）。
        // 必须在数学 stash 之前：图片的 alt/目标/标题是字面文本，不是公式；若让 $...$ 先落进
        // ![]() 里，占位符会随 marked 进 <img> 属性，步骤4 在整段 HTML 上回填时把 KaTeX 输出
        // （含引号与标签）直接注入 src/alt/title——标签当场碎掉，且发生在净化之后、绕过净化。
        // 归一化（Typora/Windows 风格反斜杠、未编码空格）也在此步：CommonMark 规定目标含未编码
        // 空格时整段不解析为图片，必须先转成合法 URL；目标允许一层成对括号（如 image (1).png）；
        // 此时代码区域仍在保护中，不会误改代码示例。
        const imgStore = [];
        processedText = processedText.replace(/(!\[[^\]\n]*\]\()((?:[^()\n]|\([^()\n]*\))+)(\))/g, function (m, pre, dest, post) {
            dest = dest.trim();
            if (dest.charAt(0) === '<') return m;
            var title = '';
            var tm = dest.match(/\s+("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')$/);
            if (tm) { title = ' ' + tm[1]; dest = dest.slice(0, tm.index); }
            dest = dest.replace(/\\/g, '/').replace(/ /g, '%20').replace(/\(/g, '%28').replace(/\)/g, '%29');
            imgStore.push(pre + dest + title + post);
            return IMG_TAG + (imgStore.length - 1) + 'E';
        });
        // 占位符安全性：IMG_TAG 带随机后缀（同 CODE_TAG/MATH_TAG 哲学），文档正文里写不出
        // 这种串，不会被误当占位符回放。

        // 步骤2: 保护数学公式（用占位符替换，避免 \\、_、& 等被Markdown解析）
        const mathBlocks = [];
        function stash(type, formula) {
            mathBlocks.push({ type: type, formula: formula });
            return MATH_TAG + (mathBlocks.length - 1) + 'E';
        }

        // 块级公式 $$...$$（可跨行）
        processedText = processedText.replace(/\$\$([\s\S]+?)\$\$/g, function (m, f) {
            return stash('block', f);
        });
        // 块级公式 \[...\]
        processedText = processedText.replace(/\\\[([\s\S]+?)\\\]/g, function (m, f) {
            return stash('block', f);
        });
        // 行内公式 \(...\)
        processedText = processedText.replace(/\\\(([\s\S]+?)\\\)/g, function (m, f) {
            return stash('inline', f);
        });
        // 裸环境（不带 $$ 直接写 \begin{equation}...\end{equation} 等）
        processedText = processedText.replace(/\\begin\{(equation\*?|align\*?|alignat\*?|gather\*?|CD)\}[\s\S]+?\\end\{\1\}/g, function (m) {
            return stash('block', m);
        });
        // 行内公式 $...$（不跨行；首尾不能是空白；结尾 $ 后不能紧跟数字，避免 "$20 和 $30" 被误判）
        processedText = processedText.replace(/(^|[^\\$])\$((?:\\[^\n]|[^\\$\n])+?)\$(?!\d)/g, function (match, prefix, formula) {
            if (/^\s/.test(formula) || /\s$/.test(formula)) return match;
            return prefix + stash('inline', formula);
        });

        // 步骤2.5 的图片目标归一化已并入步骤1.5（暂存时完成，理由见彼处注释）。

        // 恢复图片引用（含已归一化的目标）：在 marked 之前回放，图片节点由 marked 正常生成。
        // 必须先于代码恢复：图片的 alt 可含行内代码（如 ![run `x` steps](a.png)），其代码占位符
        // 是在图片暂存之前产生的、藏在 imgStore 里；先回放图片，占位符才回到文本中供下一步展开。
        processedText = processedText.replace(new RegExp(IMG_TAG + '(\\d+)E', 'g'), function (m, i) {
            return imgStore[+i];
        });
        // 恢复代码区域，交给 marked 正常渲染
        processedText = processedText.replace(new RegExp(CODE_TAG + '(\\d+)E', 'g'), function (m, i) {
            return codeStore[+i];
        });

        log('Protected ' + mathBlocks.length + ' formulas');

        // 步骤3: Markdown渲染
        let html = marked.parse(processedText);

        // 步骤3.5【安全关键】净化 marked 输出。
        // 必须在数学占位符回填之前：占位符此刻是纯文本，可无损穿过 DOMPurify；
        // 而 KaTeX 生成的 HTML 属可信内容，若在净化之后注入则不会被误剥离。
        html = sanitizeHtml(html);

        // 步骤4: 用 KaTeX 直接把占位符渲染成HTML
        // 注意必须用替换函数，不能用替换字符串——公式里的 $ & 等在替换字符串中有特殊含义
        function looksDoubleEscaped(f) {
            // 反斜杠被双写的文档（常见于AI生成/JSON复制）：存在 \\命令，且不存在任何单反斜杠命令
            const hasDouble = /\\\\[a-zA-Z]/.test(f);
            const hasSingle = /(^|[^\\])\\(?!\\)[a-zA-Z]/.test(f);
            return hasDouble && !hasSingle;
        }
        function renderFormula(formula, display) {
            // trust:false 显式声明：禁止 \href \url \includegraphics 等可注入 URL 的命令
            const opts = { displayMode: display, strict: 'ignore', output: 'html', trust: false };
            // 双写反斜杠的公式先归一化：\\theta -> \theta，\\\\ -> \\
            if (looksDoubleEscaped(formula)) {
                formula = formula.replace(/\\\\/g, '\\');
            }
            try {
                return katex.renderToString(formula, Object.assign({ throwOnError: true }, opts));
            } catch (e1) {
                // 解析失败再试一次反斜杠减半（兜底混合写法）
                const halved = formula.replace(/\\\\/g, '\\');
                if (halved !== formula) {
                    try {
                        return katex.renderToString(halved, Object.assign({ throwOnError: true }, opts));
                    } catch (e2) { /* 继续走错误展示 */ }
                }
                // 最终以红色源码展示原公式
                return katex.renderToString(formula, Object.assign({ throwOnError: false }, opts));
            }
        }
        let failCount = 0;
        html = html.replace(new RegExp(MATH_TAG + '(\\d+)E', 'g'), function (match, i) {
            const item = mathBlocks[+i];
            if (!item) return match;
            try {
                // displayMode 必须正确传递：align/gather 等环境只能在块级模式渲染
                return renderFormula(item.formula, item.type === 'block');
            } catch (e) {
                failCount++;
                // title 与正文都必须转义：异常消息可能含公式片段，此处已在净化之后
                return '<code class="math-error" title="' + escapeHtml(e.message || 'KaTeX error')
                    + '">' + escapeHtml(item.formula) + '</code>';
            }
        });

        log('Rendered ' + mathBlocks.length + ' formulas, ' + failCount + ' failed');
        const contentEl = document.getElementById('content');
        if (contentEl) {
            // html 已经过 sanitizeHtml()，后续追加的 KaTeX 片段为本地可信生成
            contentEl.innerHTML = html;
            log('HTML set');

            // 相对路径图片解析（基址可能先于/晚于首次渲染到达，两侧都触发）
            try { resolveImages(); } catch (e) { log('Image resolve error: ' + e.message); }

            // 兜底：扫描漏网的公式（如不常见的定界符写法），不含单个 $，避免误伤普通文本
            if (typeof renderMathInElement !== 'undefined') {
                try {
                    renderMathInElement(contentEl, {
                        delimiters: [
                            { left: '$$', right: '$$', display: true },
                            { left: '\\[', right: '\\]', display: true },
                            { left: '\\(', right: '\\)', display: false }
                        ],
                        throwOnError: false,
                        strict: 'ignore',
                        trust: false
                    });
                } catch (e) {
                    log('KaTeX fallback error: ' + e.message);
                }
            }

            // Prism代码高亮
            if (typeof Prism !== 'undefined') {
                Prism.highlightAllUnder(contentEl);
            }

            // Mermaid图表
            if (typeof mermaid !== 'undefined') {
                initMermaid();
                mermaid.run({ nodes: contentEl.querySelectorAll('.language-mermaid') });
            }

            // 大纲收集：给标题分配锚点 id 并回传 Android
            try {
                var headings = contentEl.querySelectorAll('h1,h2,h3,h4,h5,h6');
                var outline = [];
                for (var hi = 0; hi < headings.length; hi++) {
                    var h = headings[hi];
                    var hid = 'yumark-h-' + hi;
                    h.id = hid;
                    outline.push({
                        level: parseInt(h.tagName.substring(1), 10),
                        text: (h.textContent || '').trim(),
                        id: hid
                    });
                }
                if (window.Android && Android.onOutline) {
                    Android.onOutline(JSON.stringify(outline));
                }
            } catch (e) {
                log('Outline error: ' + e.message);
            }
        }
        // 启用代码块横向滚动优化
        enableCodeBlockScroll();

        return true;
    } catch (e) {
        log('ERROR: ' + e.message);
        console.error('Full error:', e);
        const contentEl = document.getElementById('content');
        if (contentEl) {
            // 不再拼接 innerHTML：异常消息可能带有文档内容，走 textContent 渲染
            renderPlainFallback(contentEl, '渲染失败：' + (e && e.message ? e.message : 'unknown'), null);
        }
        return false;
    }
};

window.scrollToHeading = function (id) {
    var el = document.getElementById(id);
    if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
};

/**
 * 启用代码块横向滚动优化
 * 解决父容器纵向滚动拦截代码块横向滑动的问题
 */
function enableCodeBlockScroll() {
    const codeBlocks = document.querySelectorAll('pre code');
    codeBlocks.forEach(function (block) {
        const pre = block.parentElement;
        var startX = 0, startY = 0;

        pre.addEventListener('touchstart', function (e) {
            startX = e.touches[0].clientX;
            startY = e.touches[0].clientY;
        }, { passive: true });

        pre.addEventListener('touchmove', function (e) {
            var deltaX = Math.abs(e.touches[0].clientX - startX);
            var deltaY = Math.abs(e.touches[0].clientY - startY);

            // 横向滑动距离大于纵向，且超过阈值（10px）
            if (deltaX > deltaY && deltaX > 10) {
                // 通知Android禁用父容器滚动
                if (typeof AndroidTouch !== 'undefined') {
                    AndroidTouch.requestDisallowInterceptTouchEvent(true);
                }
            }
        }, { passive: true });

        pre.addEventListener('touchend', function () {
            // 恢复父容器滚动
            if (typeof AndroidTouch !== 'undefined') {
                AndroidTouch.requestDisallowInterceptTouchEvent(false);
            }
        }, { passive: true });
    });
}
/**
 * 双击恢复原始缩放（排除可交互元素）
 */
window.addEventListener('DOMContentLoaded', function () {
    var lastTapTime = 0;

    document.addEventListener('touchend', function (e) {
        // 排除链接、按钮等可交互元素
        if (e.target.tagName === 'A' ||
            e.target.tagName === 'BUTTON' ||
            e.target.tagName === 'INPUT' ||
            e.target.tagName === 'PRE' ||
            e.target.tagName === 'CODE') {
            return;
        }

        var currentTime = Date.now();
        var tapGap = currentTime - lastTapTime;

        // 双击判定：300ms内两次点击
        if (tapGap > 0 && tapGap < 300) {
            // 通知Android恢复缩放
            if (typeof Android !== 'undefined' && Android.resetZoom) {
                Android.resetZoom();
            }
            // 防止连续触发
            lastTapTime = 0;
        } else {
            lastTapTime = currentTime;
        }
    });
});

// ===== 编辑/预览滚动同步 =====
// 全部在 JS 内用一致的 CSS px 计算比例（0~1），避免 Kotlin 侧把 contentHeight(CSS px)
// 与 View 的 height/scrollY(物理 px) 混算导致定位错乱（高密度屏会算成 0 跳顶）。
window.getScrollRatio = function () {
    var max = document.documentElement.scrollHeight - window.innerHeight;
    if (max <= 0) return 0;
    var r = (window.scrollY || document.documentElement.scrollTop || 0) / max;
    return r < 0 ? 0 : (r > 1 ? 1 : r);
};
window.scrollToRatio = function (r) {
    var max = document.documentElement.scrollHeight - window.innerHeight;
    if (max < 0) max = 0;
    r = r < 0 ? 0 : (r > 1 ? 1 : r);
    window.scrollTo(0, Math.round(max * r));
};

// 供设备端排查：确认净化器是否真的挂上（Kotlin 的 onConsoleMessage 会转发到 logcat）
console.log('[YuMark] renderer.js ready, sanitizer='
    + (HAS_SANITIZER ? ('DOMPurify/' + SANI.version) : 'MISSING'));

})();

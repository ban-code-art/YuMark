// YuMark renderer.js 图片/数学占位管线的 node 验证（不需要 gradle，也不依赖浏览器）。
//
// 存在的理由：renderer.js 在 assets/raw/ 下，无任何自动化测试覆盖。本脚本加载**真实的**
// renderer.js 与真实的 markedjs.js（UMD，node 可直接 require），只 stub window/document/
// 净化器/katex 这些 node 环境里不存在的东西。防护的是一次真实回归：
//
//   旧缺陷（已修）：图片引用的暂存发生在数学 stash 之后，含 $...$ 的 alt/目标/标题会把
//   数学占位符带进 <img> 的属性值，步骤4 在整段 HTML 上回填时把 KaTeX 输出直接注入
//   src/alt/title，标签当场碎掉，且注入发生在 sanitizeHtml 之后、绕过净化。
//
// 用法：node scripts/verify_renderer_pipeline.js
// 退出码 0 = 全部通过；1 = 有断言失败；2 = 环境错误（文件缺失等）。

'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.dirname(path.dirname(__filename));
const RAW = path.join(ROOT, 'app', 'src', 'main', 'assets', 'raw');

function fail(msg) {
    console.error('FAIL: ' + msg);
    process.exitCode = 1;
}

let passed = 0;
function check(name, cond, detail) {
    if (cond) { passed++; return true; }
    fail(name + (detail ? ' | ' + detail : ''));
    return false;
}

/* ---------- 最小 DOM stub：只实现 renderer.js 与 marked 会碰到的面 ---------- */

function makeAttrMap() {
    // namedNodeMap 的极小子集：getNamedItem/setNamedItem
    const map = new Map();
    return {
        get length() { return map.size; },
        getNamedItem(n) { return map.has(n) ? { name: n, value: map.get(n) } : null; },
        setNamedItem(attr) { map.set(attr.name, attr.value); },
        _raw: map
    };
}

let idCounter = 0;
function makeElement(tag) {
    const attrs = makeAttrMap();
    const children = [];
    const el = {
        tagName: String(tag).toUpperCase(),
        nodeType: 1,
        id: '',
        className: '',
        style: {},
        childNodes: children,
        children: children,
        attributes: attrs,
        getAttribute(n) { return attrs._raw.has(n) ? attrs._raw.get(n) : null; },
        setAttribute(n, v) { attrs._raw.set(n, String(v)); },
        removeAttribute(n) { attrs._raw.delete(n); },
        appendChild(c) { children.push(c); return c; },
        removeChild(c) { const i = children.indexOf(c); if (i >= 0) children.splice(i, 1); return c; },
        addEventListener() {},
        querySelectorAll() { return []; },
        querySelector() { return null; },
        firstChild: null,
        get textContent() {
            // marked 输出是纯 HTML 字符串，这里不会真的走 DOM 构建；textContent 仅作兜底
            return '';
        }
    };
    el._id = 'stub-' + (idCounter++);
    return el;
}

const contentElement = makeElement('div');
contentElement.id = 'content';

const documentStub = {
    nodeType: 9,
    getElementById(id) {
        if (id === 'content') return contentElement;
        return null;
    },
    createElement: makeElement,
    createTextNode(t) { return { nodeType: 3, textContent: String(t) }; },
    querySelectorAll() { return []; },
    addEventListener() {},
    documentElement: { scrollHeight: 0, scrollTop: 0 }
};

// 记录 innerHTML 写入，供断言
const innerHtmlWrites = [];
Object.defineProperty(contentElement, 'innerHTML', {
    get() { return innerHtmlWrites.length ? innerHtmlWrites[innerHtmlWrites.length - 1] : ''; },
    set(v) { innerHtmlWrites.push(String(v)); }
});

/* ---------- window stub：注入假净化器（恒等）与假 katex ---------- */

const windowStub = {
    document: documentStub,
    addEventListener() {},
    // 恒等净化器：node 下没有 DOMPurify。这里要测的是占位符管线而不是净化本身，
    // 恒等放行让 KaTeX 回填路径完整执行，注入 src 的缺陷才会显形。
    YuMarkSanitize: {
        available: true,
        version: 'stub',
        sanitize: (html) => html,
        escapeHtml: (s) => String(s)
    },
    katex: {
        // 假 katex：输出一个可辨识的标记串（含引号与标签，模拟真实 KaTeX 输出的形状）
        renderToString: (formula, opts) =>
            '<span class="katex-stub" data-display="' + (opts && opts.displayMode ? 1 : 0)
            + '">' + formula + '</span>'
    }
};
globalThis.window = windowStub;
globalThis.document = documentStub;
globalThis.katex = windowStub.katex;
// navigator/console 等 renderer.js 顶层只读 console —— node 自带

/* ---------- 加载真实 renderer.js 与 marked ---------- */

let rendererSource;
try {
    rendererSource = fs.readFileSync(path.join(RAW, 'renderer.js'), 'utf8');
} catch (e) {
    console.error('cannot read renderer.js: ' + e.message);
    process.exit(2);
}

// marked：UMD。renderer.js 以全局 marked 为判据，先挂到全局再加载。
let markedModule;
try {
    markedModule = require(path.join(RAW, 'markedjs.js'));
} catch (e) {
    console.error('cannot load markedjs.js: ' + e.message);
    process.exit(2);
}
globalThis.marked = markedModule;

// 在全局上下文里执行 renderer.js 源文本（它是 IIFE，直接 eval 等价于浏览器加载）
try {
    // eslint-disable-next-line no-eval
    (0, eval)(rendererSource);
} catch (e) {
    console.error('renderer.js threw on load: ' + e.message);
    process.exit(2);
}

if (typeof windowStub.renderMarkdown !== 'function') {
    console.error('renderMarkdown not exported on window after load');
    process.exit(2);
}

/* ---------- 用例 ---------- */

function render(md) {
    innerHtmlWrites.length = 0;
    const ok = windowStub.renderMarkdown(md);
    return { ok: ok, html: innerHtmlWrites.length ? innerHtmlWrites[0] : '' };
}

// —— 用例 1（旧缺陷本体）：图片 alt 含行内公式 ——
{
    const r = render('![公式 $x^2$ 图](img.png)');
    check('math-in-alt renders ok', r.ok === true);
    // img 标签必须只出现一次，src 必须原样
    const imgCount = (r.html.match(/<img\b/g) || []).length;
    check('one img tag', imgCount === 1, 'img count=' + imgCount + ' html=' + r.html);
    const srcOk = /<img[^>]*src="img\.png"/.test(r.html);
    check('img src not polluted by KaTeX', srcOk, r.html);
    // CommonMark：alt 是字面内容。图片引用被整体暂存后，其中的 $...$ 从未进数学管线，
    // alt 必须原样保留 $x^2$（缺陷版会在此处回填出 katex-stub，属性当场碎掉）
    const altOk = /alt="公式 \$x\^2\$ 图"/.test(r.html);
    check('alt stays literal, math in alt never stashed', altOk, r.html);
    const katexInSrc = /src="[^"]*katex/.test(r.html);
    check('no katex in any src attribute', !katexInSrc, r.html);
}

// —— 用例 2：图片 title 含公式 ——
{
    const r = render('![a](img.png "标题 $y$ 公式")');
    check('math-in-title renders ok', r.ok === true);
    const srcOk = /<img[^>]*src="img\.png"/.test(r.html);
    check('img src survives title math', srcOk, r.html);
}

// —— 用例 3：图片目标本身含 $（文件名起这么叫不常见，但旧缺陷正中此处）——
{
    const r = render('![a](cost-$5-notes.png)');
    check('dollar-in-filename renders ok', r.ok === true);
    const srcOk = /<img[^>]*src="cost-\$5-notes\.png"/.test(r.html)
        || /<img[^>]*src="[^"]*5-notes\.png"/.test(r.html);
    check('dollar filename not eaten by math stash', srcOk, r.html);
}

// —— 用例 4：普通正文公式仍正常回填（修复不能误伤主线）——
{
    const r = render('行内 $x^2+1$ 与块级\n\n$$e=mc^2$$');
    check('normal math still renders', r.ok === true);
    const stubCount = (r.html.match(/katex-stub/g) || []).length;
    check('both formulas rendered', stubCount === 2, 'stub count=' + stubCount + ' html=' + r.html);
}

// —— 用例 5：代码区域里的 $ 仍受保护（回归守护）——
{
    const r = render('`$x^2$ 不会被渲染`\n\n```\n$not math$\n```\n');
    check('code dollar protected', r.ok === true);
    const stubCount = (r.html.match(/katex-stub/g) || []).length;
    check('no math rendered inside code', stubCount === 0, r.html);
}

// —— 用例 6：目标归一化仍然生效（Windows 反斜杠、空格、括号）——
{
    const r = render('![a](folder\\image (1).png)');
    check('normalized image renders ok', r.ok === true);
    const srcOk = /src="folder\/image%20%281%29\.png"/.test(r.html);
    check('backslash/space/paren normalized', srcOk, r.html);
}

// —— 用例 7：alt 含行内代码的图片（恢复次序守护：先 img 后 code）——
{
    const r = render('![run `npm x` steps](img.png)');
    check('code-in-alt image renders ok', r.ok === true);
    // CommonMark 规定图片 alt 是字面内容，不解析行内标记——反引号原样保留。
    // 断言的真正意图：代码占位符必须已恢复为原文，alt 里不能残留 YMCODE…E 字面量。
    const codeOk = /alt="run `npm x` steps"/.test(r.html) && !/YMCODE/i.test(r.html);
    const srcOk = /<img[^>]*src="img\.png"/.test(r.html);
    check('inline code in alt restored to literal text', codeOk, r.html);
    check('img src intact with code-in-alt', srcOk, r.html);
}

/* ---------- 汇总 ---------- */

console.log(passed + ' checks passed, '
    + (process.exitCode ? 'with failures' : '0 failures'));
if (process.exitCode) process.exit(1);

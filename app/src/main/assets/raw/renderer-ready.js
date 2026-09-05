// 渲染管线就绪握手（body 末尾同步执行）。
// marked/katex/prism/purify 已同步加载；mermaid 为 defer，渲染函数内有 typeof 守卫。
// 独立成文件是因为 renderer.html 的 CSP 不含 'unsafe-inline'，内联脚本不会执行。
(function () {
    'use strict';
    if (typeof window.renderMarkdown !== 'function') {
        console.log('[YuMark] FATAL: renderMarkdown missing at ready-handshake');
    }
    if (window.Android && Android.onReady) { Android.onReady(); }
})();

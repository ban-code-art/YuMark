/*
 * AI 对话气泡的 Markdown 渲染（外置脚本）
 *
 * 气泡内容来自模型输出与工具返回，属不可信内容，必须经 ym-sanitize.js 净化后才能入 DOM。
 * 宿主页面的 CSP 不含 'unsafe-inline'，本逻辑不能内联回 MessageBubble.kt 的 HTML 模板，
 * 否则会静默不执行。
 */
(function () {
'use strict';

var SANI = window.YuMarkSanitize || null;

function contentEl() {
    return document.getElementById('content');
}

// 纯文本降级：只用 DOM API，不拼 HTML
function setPlain(el, text) {
    while (el.firstChild) el.removeChild(el.firstChild);
    var pre = document.createElement('pre');
    pre.textContent = String(text == null ? '' : text);
    el.appendChild(pre);
}

// 全局更新函数，供 Android 调用
window.updateContent = function (base64Markdown) {
    var el = contentEl();
    if (!el) return;
    try {
        var binaryStr = atob(base64Markdown);
        var markdown = decodeURIComponent(escape(binaryStr));

        if (typeof marked === 'undefined') {
            el.textContent = 'marked.js 未加载';
            return;
        }
        // 净化器缺失 → fail-closed：降级纯文本，绝不输出未净化 HTML
        if (!SANI || !SANI.available) {
            console.log('[YuMark] bubble sanitizer MISSING, plain text fallback');
            setPlain(el, markdown);
            return;
        }

        marked.setOptions({ breaks: true, gfm: true });
        el.innerHTML = SANI.sanitize(marked.parse(markdown));
    } catch (e) {
        console.error('Render error:', e);
    }
};

// 通知 Android WebView 已就绪
window.onload = function () {
    if (window.Android && window.Android.onReady) {
        window.Android.onReady();
    }
};

})();

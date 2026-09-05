/*
 * 静态 Markdown 渲染（外置脚本）
 *
 * 适用于「内容一次给定、无需增量更新」的场景（当前：设置页的更新日志）。
 * 内容来自 GitHub Release body，属远端不可信输入，必须经 ym-sanitize.js 净化后才能入 DOM。
 * 宿主页面的 CSP 不含 'unsafe-inline'，本逻辑不能内联回 Kotlin 的 HTML 模板，否则静默不执行。
 *
 * 约定：宿主模板把 base64(UTF-8) 编码后的 Markdown 放在 #content 的 data-ym-md 属性上。
 * base64 字母表（A-Za-z0-9+/=）不含引号与尖括号，塞进 HTML 属性不产生注入面。
 */
(function () {
'use strict';

var SANI = window.YuMarkSanitize || null;

// 纯文本降级：只用 DOM API，不拼 HTML
function setPlain(el, text) {
    while (el.firstChild) el.removeChild(el.firstChild);
    var pre = document.createElement('pre');
    pre.textContent = String(text == null ? '' : text);
    el.appendChild(pre);
}

function render() {
    var el = document.getElementById('content');
    if (!el) return;

    var markdown;
    try {
        markdown = decodeURIComponent(escape(atob(el.getAttribute('data-ym-md') || '')));
    } catch (e) {
        el.textContent = 'Markdown 解码失败';
        return;
    }

    if (typeof marked === 'undefined') {
        el.textContent = 'Markdown 渲染失败';
        return;
    }
    // 净化器缺失 → fail-closed：降级纯文本，绝不输出未净化 HTML
    if (!SANI || !SANI.available) {
        console.log('[YuMark] static-md sanitizer MISSING, plain text fallback');
        setPlain(el, markdown);
        return;
    }

    try {
        marked.setOptions({ breaks: true, gfm: true });
        el.innerHTML = SANI.sanitize(marked.parse(markdown));
    } catch (e) {
        console.error('Render error:', e);
        setPlain(el, markdown);
    }
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', render);
} else {
    render();
}

})();

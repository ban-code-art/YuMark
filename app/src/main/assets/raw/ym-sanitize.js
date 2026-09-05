/*
 * YuMark 统一 HTML 净化层
 *
 * 所有把「文档内容 / 模型生成内容」写入 DOM 的渲染器都必须经过这里，
 * 不要在各渲染器里各自配置一套 DOMPurify——那样迟早有一份会漂移成漏洞。
 *
 * 依赖 raw/purify.js，必须在本文件之前加载。
 * 用法：
 *   var S = window.YuMarkSanitize;
 *   if (!S.available) { 降级为纯文本 }         // fail-closed
 *   el.innerHTML = S.sanitize(untrustedHtml);
 */
(function () {
'use strict';

// mermaid 打包副本会覆盖 window.DOMPurify，这里在其（defer）执行前抓取实例
var PURIFY = (typeof DOMPurify !== 'undefined' && DOMPurify && DOMPurify.sanitize)
    ? DOMPurify : null;

var CONFIG = {
    USE_PROFILES: { html: true, svg: true, mathMl: true },
    FORBID_TAGS: [
        'script', 'style', 'link', 'meta', 'base', 'title',
        'iframe', 'frame', 'frameset', 'object', 'embed', 'applet', 'portal',
        'form', 'button', 'textarea', 'select', 'optgroup', 'fieldset', 'legend',
        'noscript', 'template', 'slot',
        'foreignObject', 'use', 'animate', 'animateTransform', 'set', 'handler', 'listener'
    ],
    FORBID_ATTR: [
        'srcdoc', 'sandbox', 'formaction', 'form', 'ping', 'http-equiv',
        'autofocus', 'target', 'xlink:href', 'onbegin', 'onend', 'onrepeat'
    ],
    ALLOW_DATA_ATTR: true,
    ALLOW_ARIA_ATTR: true,
    SANITIZE_DOM: true,
    KEEP_CONTENT: true,
    RETURN_DOM: false,
    RETURN_DOM_FRAGMENT: false,
    RETURN_TRUSTED_TYPE: false
};

// 取 URI 方案；先剥掉控制字符与各类空白（含 NBSP/LS/PS/BOM），
// 防 "java<TAB>script:" / "java&#10;script:" 一类方案伪装绕过。
// 只用码点比较，避免源码里出现字面控制字符。
function schemeOf(v) {
    var s = String(v == null ? '' : v);
    var out = '';
    for (var i = 0; i < s.length; i++) {
        var c = s.charCodeAt(i);
        if (c > 32 && c !== 160 && c !== 0x2028 && c !== 0x2029 && c !== 0xFEFF) {
            out += s.charAt(i);
        }
    }
    var m = out.match(/^([a-zA-Z][a-zA-Z0-9+.\-]*):/);
    return { clean: out, scheme: m ? m[1].toLowerCase() : '' };
}
// 链接可用方案：仅网络 / 邮件 / 电话；相对路径与 #锚点（无方案）放行
var LINK_SCHEMES = ['http', 'https', 'mailto', 'tel'];
// 媒体可用方案：应用自身会把相对图片解析成 file:/content:，需保留
var MEDIA_SCHEMES = ['http', 'https', 'file', 'content', 'blob'];
// data: 只放行 base64 位图；data:image/svg+xml 可携带脚本，一律拒绝
var DATA_IMAGE_RE = /^data:image\/(?:png|jpeg|jpg|gif|webp|bmp|avif);base64,[A-Za-z0-9+/=]+$/i;

if (PURIFY && PURIFY.addHook) {
    PURIFY.addHook('afterSanitizeElements', function (node) {
        // GFM 任务列表依赖 <input type=checkbox>，保留但强制只读；其余控件移除
        if (node.nodeName === 'INPUT') {
            var t = ((node.getAttribute && node.getAttribute('type')) || '').toLowerCase();
            if (t !== 'checkbox' && t !== 'radio') {
                if (node.parentNode) node.parentNode.removeChild(node);
                return;
            }
            node.setAttribute('disabled', 'disabled');
            node.removeAttribute('name');
            node.removeAttribute('value');
        }
    });
    PURIFY.addHook('afterSanitizeAttributes', function (node) {
        if (!node.getAttribute || !node.hasAttribute) return;
        if (node.hasAttribute('href')) {
            var h = schemeOf(node.getAttribute('href'));
            if (h.scheme && LINK_SCHEMES.indexOf(h.scheme) < 0) node.removeAttribute('href');
        }
        if (node.hasAttribute('src')) {
            var s = schemeOf(node.getAttribute('src'));
            if (s.scheme === 'data') {
                if (!DATA_IMAGE_RE.test(s.clean)) node.removeAttribute('src');
            } else if (s.scheme && MEDIA_SCHEMES.indexOf(s.scheme) < 0) {
                node.removeAttribute('src');
            }
        }
        if (node.nodeName === 'A') node.setAttribute('rel', 'noopener noreferrer');
    });
}

function escapeHtml(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

window.YuMarkSanitize = {
    available: !!PURIFY,
    version: PURIFY ? (PURIFY.version || '?') : null,
    // 唯一净化入口。净化器缺失时抛错，调用方必须降级为纯文本（fail-closed）
    sanitize: function (html) {
        if (!PURIFY) throw new Error('YM_NO_SANITIZER');
        return PURIFY.sanitize(String(html == null ? '' : html), CONFIG);
    },
    escapeHtml: escapeHtml
};

})();

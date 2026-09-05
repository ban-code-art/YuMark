package com.yumark.app.core.export

/**
 * `renderer.js` 里 `resolveImages()` 那段相对图片引用解析的 Kotlin 孪生实现（纯逻辑，可 JVM 单测）。
 *
 * 为什么必须存在两份：PDF / 长图 / 富 HTML 三种导出走的是离屏 WebView，正文里的相对引用由
 * `renderer.js` 在页面里就地改写 `<img src>`；而**纯 HTML 与 Word 导出根本不开 WebView**
 * （HtmlExporter 直接用 commonmark 渲染，DocxExporter 直接拼 OOXML），那条 JS 永远不会执行。
 * 从前这两条路只是把 `![](images/x.jpg)` 原样写进产物，于是工具栏「从相册选择」插进来的图，
 * 导出成 .html 或 .docx 后必然是裂的。
 *
 * 两份实现必须同步改：JS 侧在 `app/src/main/assets/raw/renderer.js` 的「3. 相对路径图片解析」
 * 一节。[ExportImagePathsTest] 里的用例是照着那段 JS 的行为写的，改一边不改另一边会让
 * 同一篇文档在 PDF 里有图、在 .html 里没图（或反过来），而两条路都不报错。
 */

/**
 * 应用自管图片的引用形态：`images/<UUID>.<ext>`，由 `ImageRepositoryImpl` 生成。
 *
 * 卡到 UUID 这么细而不是只看 `images/` 开头的理由与 JS 侧完全一致：`images/` 是 Markdown
 * 工程里最常见的资源目录名，导入库文档正文里的 `images/pic.png` 指的是它自己那份被镜像到
 * `import_assets/` 下的资源，被 appPrefix 抢走就会从「能显示」变成「显示不出来」。
 */
private val APP_IMAGE_RE = Regex(
    "^images/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.[A-Za-z0-9]+$"
)

/** 已经是绝对地址的引用不需要解析。与 JS 侧 `/^(https?:|data:|file:|content:|blob:)/i` 同一张名单。 */
private val ABSOLUTE_URL_RE = Regex("^(https?:|data:|file:|content:|blob:)", RegexOption.IGNORE_CASE)

/**
 * 把正文里的一个图片引用解析成可读取的绝对 URL；解析不出来返回 null（调用方保留原值）。
 *
 * 返回 null 的三种情况，都必须是「保留原样」而不是「拼一个可能无效的 URL」：
 * - 引用本身已是绝对地址（http/data/file/content/blob）——已经能用，动它只会弄坏；
 * - 引用为空；
 * - 既不匹配应用自管图片形态，[ExportImageResolver.prefix] 又是空串（普通库文档没有
 *   「所在目录」这回事）——此时没有任何基址可拼。
 */
internal fun resolveExportImageSrc(raw: String, resolver: ExportImageResolver?): String? {
    if (resolver == null || raw.isEmpty()) return null
    if (ABSOLUTE_URL_RE.containsMatchIn(raw)) return null

    // 正文里的引用可能是编码过的（`images/我的图.jpg` 被写成 `%E6%88%91...`）。先解码回真实
    // 路径再判形态与拼接，最后统一重新编码——否则会出现二次编码（`%25E6%2588...`），
    // 拿去读文件必然 404。解码失败（半个 % 转义）就按原样处理，与 JS 的 try/catch 一致。
    val rel = decodeUriComponentOrNull(raw) ?: raw

    val appRel = joinImagePath("", rel)
    val appPrefix = resolver.appPrefix
    // 空串与 null 等价（JS 侧 `if (cfg.appPrefix && …)` 真值判断，renderer.js 的 resolveImages）：
    // EditorViewModel.appImagesPrefix 在拿不到前缀时就是退成空串。若把空串当「有前缀」，
    // 会拼出无 scheme 的裸相对路径 `images/uuid.png`——消费方当本地文件路径读（LocalImageBytes
    // 对 scheme==null 走文件读）、或原样写进导出件 src（HtmlExporter），两条都错。
    if (!appPrefix.isNullOrEmpty() && APP_IMAGE_RE.matches(appRel)) {
        return appPrefix + encodePathSegments(appRel)
    }

    if (resolver.prefix.isEmpty()) return null
    val joined = joinImagePath(resolver.base, rel)
    return resolver.prefix + if (resolver.encodeAll) {
        encodeUriComponent(joined)
    } else {
        encodePathSegments(joined)
    }
}

/**
 * 把相对引用拼到 [base] 目录下，`..` 不会越过根。
 *
 * 防穿越靠的是「segs 空了就不再 pop」而不是事后校验拼出来的字符串：`a/../../b` 这种
 * 中途才越界的写法，事后比较前缀是看不出来的。
 *
 * [base] 允许带 `root:` 这种 SAF documentId 前缀（`primary:Docs/notes`）：冒号前那一段
 * 是 root 标识，不是路径段，参与 split 会被 `..` 吃掉，于是拼出一个没有 root 的非法 docId。
 */
internal fun joinImagePath(base: String, rel: String): String {
    val normalizedRel = rel.replace('\\', '/')
    var body = base
    var rootPrefix = ""
    val m = Regex("^([^/]*:)([\\s\\S]*)$").find(base)
    if (m != null) {
        rootPrefix = m.groupValues[1]
        body = m.groupValues[2]
    }
    val segs = if (body.isEmpty()) mutableListOf() else body.split('/').filter { it.isNotEmpty() }.toMutableList()
    // 引用以 / 开头 = 相对文档根，丢掉 base 的目录链（与 JS 侧一致）
    if (normalizedRel.startsWith("/")) segs.clear()
    for (p in normalizedRel.split('/')) {
        when {
            p.isEmpty() || p == "." -> continue
            p == ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.size - 1)
            else -> segs.add(p)
        }
    }
    return rootPrefix + segs.joinToString("/")
}

/** 逐段编码：路径分隔符留着，段内的空格/中文/`#` 要转义。 */
internal fun encodePathSegments(path: String): String =
    path.split('/').joinToString("/") { encodeUriComponent(it) }

/**
 * JS `encodeURIComponent` 的等价实现。
 *
 * 不能用 [java.net.URLEncoder]：它按 `application/x-www-form-urlencoded` 编码，空格变 `+`
 * 而不是 `%20`，`~` 和 `*` 的处理也与 encodeURIComponent 不同。用在 `file://` URL 上，
 * 一个带空格的文件名会被解析成带字面 `+` 的路径，读不到文件且完全没有报错。
 *
 * 不转义集合取自 ECMA-262 的 `unescapedURIComponentSet`：`A-Za-z0-9` 加 `-_.!~*'()`。
 */
internal fun encodeUriComponent(s: String): String {
    val out = StringBuilder(s.length)
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt().toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.!~*'()") {
            out.append(c)
        } else {
            out.append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
    }
    return out.toString()
}

/**
 * JS `decodeURIComponent` 的等价实现；转义串非法时返回 null（JS 那边是抛 URIError）。
 *
 * 同样不能用 [java.net.URLDecoder]：它会把 `+` 解成空格。文件名里的 `+` 是合法字符，
 * 解错之后拼出的路径指向一个不存在的文件。
 */
internal fun decodeUriComponentOrNull(s: String): String? {
    if ('%' !in s) return s
    val bytes = java.io.ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%') {
            if (i + 2 >= s.length) return null
            val hi = Character.digit(s[i + 1], 16)
            val lo = Character.digit(s[i + 2], 16)
            if (hi < 0 || lo < 0) return null
            bytes.write((hi shl 4) or lo)
            i += 3
        } else {
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    val decoded = bytes.toByteArray().toString(Charsets.UTF_8)
    // UTF-8 解码不会抛异常，非法字节序列会变成 U+FFFD。JS 的 decodeURIComponent 对同样的
    // 输入是抛 URIError，这里用替换字符的出现来还原「解码失败」——否则会拿一串 � 去拼路径。
    return if ('�' in decoded && '�' !in s) null else decoded
}

private const val HEX = "0123456789ABCDEF"

// ---- 图片目标归一化（renderer.js「步骤2.5」的孪生实现）----

/**
 * 代码区域保护。与 `renderer.js` 步骤1 的那条正则**逐字符一致**（围栏块、双反引号、单反引号）。
 *
 * 存在的理由：下面的目标归一化是在 **Markdown 源文本**上做正则替换，不区分上下文。
 * 正文里演示 Markdown 语法的代码块（`` `![图](my pic.png)` ``）会被一起改写成 `%20` 形态，
 * 那是把用户想展示的原文改掉了 —— 代码块里的内容必须原样呈现。
 */
private val CODE_REGION_RE = Regex("```[\\s\\S]*?```|~~~[\\s\\S]*?~~~|``[^\\n]*?``|`[^`\\n]*`")

/** `![alt](dest)`；目标允许一层成对括号，正是为了 `image (1).png` 这种文件名。 */
private val IMAGE_TARGET_RE = Regex("""(!\[[^\]\n]*]\()((?:[^()\n]|\([^()\n]*\))+)(\))""")

/** 目标末尾的 title（`![](a.png "标题")`）：归一化只能动路径，不能动标题里的空格。 */
private val IMAGE_TITLE_RE = Regex("""\s+("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')$""")

/**
 * 归一化 Markdown 里的图片目标：反斜杠→`/`、空格→`%20`、括号→`%28`/`%29`。
 *
 * ### 为什么不做这一步图片会**整段变成字面文字**
 * CommonMark 规定：不带 `<>` 的链接目标里出现未编码空格时，整个 `![…](…)` **不构成图片**。
 * 于是 Typora / Windows 风格的引用（`![图](images\my pic.png)`）在 commonmark 眼里就是普通文本，
 * AST 里连 `Image` 节点都没有 —— [HtmlExporter] 与 [DocxExporter] 那套「解析相对路径、嵌入图片」
 * 的逻辑全部无从触发，`.html` / `.docx` 里出现的是一行字面的 `![图](images\my pic.png)`。
 *
 * PDF / 长图 / 富 HTML 走离屏 WebView，`renderer.js` 在解析前就地改写了源文本，所以有图。
 * 同一篇文档，五种格式里三种有图两种是文字，且两条路都不报错。
 *
 * 两份实现必须同步改：JS 侧在 `renderer.js` 的「步骤2.5」。
 */
internal fun normalizeImageTargets(markdown: String): String {
    if ("![" !in markdown) return markdown
    val out = StringBuilder(markdown.length)
    var last = 0
    for (code in CODE_REGION_RE.findAll(markdown)) {
        out.append(normalizeOutsideCode(markdown.substring(last, code.range.first)))
        out.append(code.value) // 代码区域原样搬运
        last = code.range.last + 1
    }
    out.append(normalizeOutsideCode(markdown.substring(last)))
    return out.toString()
}

private fun normalizeOutsideCode(text: String): String =
    IMAGE_TARGET_RE.replace(text) { m ->
        var dest = m.groupValues[2].trim()
        // `<...>` 形式的目标本来就允许含空格，CommonMark 自己会解 —— 动它反而弄坏
        if (dest.startsWith("<")) return@replace m.value
        var title = ""
        IMAGE_TITLE_RE.find(dest)?.let { tm ->
            title = " " + tm.groupValues[1]
            dest = dest.substring(0, tm.range.first)
        }
        dest = dest.replace('\\', '/')
            .replace(" ", "%20")
            .replace("(", "%28")
            .replace(")", "%29")
        m.groupValues[1] + dest + title + m.groupValues[3]
    }

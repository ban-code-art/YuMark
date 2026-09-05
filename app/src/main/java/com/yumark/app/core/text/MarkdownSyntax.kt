package com.yumark.app.core.text

/**
 * 工具栏能插入的 Markdown 语法。行为全在 [applyTo]。
 *
 * 从前工具栏直接往回调里塞语法字面量（`"****"`、`"[](url)"`、整段表格模板），EditorScreen
 * 再拿同一批字符串查三张表：`isLineSyntax` 集合、`wrap` 前后缀表、`cursorOffset` 表。
 * 加一个按钮要同时改三处，只改两处照样编译通过、运行时才错；那段逻辑还长在 Compose
 * lambda 里，写不了单测。
 *
 * 现在按钮只传枚举，每种语法的形态与光标落点都在本文件一处定义，且是纯函数。放在 core
 * 而不是 presentation：core 不依赖 Compose（[TextSnapshot] 就是为此存在的无框架表示），
 * JVM 单测因此不需要任何 Android / Compose 运行时。
 */
enum class MarkdownAction {
    HEADING, BOLD, ITALIC, STRIKETHROUGH, LINK, IMAGE, CODE, CODE_BLOCK,
    BULLET_LIST, NUMBERED_LIST, QUOTE, TABLE, HORIZONTAL_RULE
}

/**
 * 表格模板。刻意不进 strings.xml：这是插进用户**文档正文**的内容，不是界面文案。
 * 一旦跟着语言翻译，同一篇文档在不同语言环境下表头就会变，属于功能损坏。
 */
internal const val TABLE_TEMPLATE = "| 列1 | 列2 |\n|-----|-----|\n| 内容 | 内容 |"

/** 三种插入形态；每个 [MarkdownAction] 归到其中一种（见 [form]）。 */
private sealed interface Form {

    /** 行首前缀（标题 / 列表 / 引用）：跨行选区时每一行都加。 */
    data class LinePrefix(val prefix: String) : Form

    /**
     * 行内包裹。[placeholder] 指 [right] 里的占位串（链接与图片的 `url`）：有选区时把它选中，
     * 下一步就是直接打地址。只在 [right] 里找，不在用户选中的原文里找——原文里恰好含
     * "url" 三个字母时，全串搜索会选错位置。
     */
    data class Wrap(val left: String, val right: String, val placeholder: String? = null) : Form

    /**
     * 独占成块，前后按需补空行（见 [blockPadBefore] / [blockPadAfter]）。
     *
     * [right] 为空 = 纯插入（表格 / 分隔线）：选区原样保留，块落在选区**之后**，
     * 此时 [placeholder] 指 [left] 里的占位串（表格的 `列1`）。
     * [right] 非空 = 围起选区（代码块），此时 [placeholder] 不适用。
     */
    data class Block(val left: String, val right: String = "", val placeholder: String? = null) : Form
}

/**
 * 语法到形态的唯一映射表。
 *
 * 加粗用 `**`、斜体用单 `*`：斜体本可以用 `_`，但中文正文里下划线两侧没有空格时
 * CommonMark 不当强调符（`intraword emphasis` 只对 `*` 生效），所以统一用星号。
 */
private val MarkdownAction.form: Form
    get() = when (this) {
        MarkdownAction.HEADING -> Form.LinePrefix("# ")
        MarkdownAction.BULLET_LIST -> Form.LinePrefix("- ")
        // 每行都写 "1."：CommonMark 按首项序号起算、其余自动递增，渲染出来照样是 1./2./3.
        MarkdownAction.NUMBERED_LIST -> Form.LinePrefix("1. ")
        MarkdownAction.QUOTE -> Form.LinePrefix("> ")
        MarkdownAction.BOLD -> Form.Wrap("**", "**")
        MarkdownAction.ITALIC -> Form.Wrap("*", "*")
        MarkdownAction.STRIKETHROUGH -> Form.Wrap("~~", "~~")
        MarkdownAction.CODE -> Form.Wrap("`", "`")
        MarkdownAction.LINK -> Form.Wrap("[", "](url)", placeholder = "url")
        MarkdownAction.IMAGE -> Form.Wrap("![", "](url)", placeholder = "url")
        // 开栏栅栏不带语言标记：留空比猜一个语言好，用户要高亮就自己在栅栏行补
        MarkdownAction.CODE_BLOCK -> Form.Block("```\n", "\n```")
        MarkdownAction.TABLE -> Form.Block(TABLE_TEMPLATE, placeholder = "列1")
        MarkdownAction.HORIZONTAL_RULE -> Form.Block("---")
    }

/**
 * 把本语法插入 [snapshot]，返回插入后的新快照（纯函数，不碰任何框架类型）。
 *
 * 选区一律先规整成 `[start, end)` 再用：TextFieldValue 的 selection 是可以反向的
 * （从后往前拖选时 start > end），直接拿去 substring 会抛 StringIndexOutOfBounds。
 * 越界坐标也在这里夹住——外部传进来的快照不保证与 text 同步。
 */
fun MarkdownAction.applyTo(snapshot: TextSnapshot): TextSnapshot {
    val text = snapshot.text
    val start = minOf(snapshot.selectionStart, snapshot.selectionEnd).coerceIn(0, text.length)
    val end = maxOf(snapshot.selectionStart, snapshot.selectionEnd).coerceIn(0, text.length)
    return when (val f = form) {
        is Form.LinePrefix -> applyLinePrefix(text, start, end, f.prefix)
        is Form.Wrap -> applyWrap(text, start, end, f)
        is Form.Block ->
            // right 为空 = 纯插入，落点用 end：这样选中的原文原样留着，不被块顶掉
            if (f.right.isEmpty()) insertBlock(text, end, f) else fenceBlock(text, start, end, f)
    }
}

/**
 * 插入一张**已落盘图片**的引用：`![选中的字](path)`，无选区时 `![|](path)`。
 *
 * 与 [MarkdownAction.IMAGE] 的分工：那个插的是 `![](url)` 语法壳子，`url` 三个字母被选中等着
 * 用户手打地址；这个是「从相册选图 → 存进 images/ → 把真实相对路径写回正文」这条链路的最后
 * 一步，地址已经确定，光标要落在 **alt 文本**上（有选区就直接拿选中的字当 alt）。
 *
 * 复用 [applyWrap] 而不是自己拼串：包裹语义、反向选区规整、越界坐标夹取三件事只该有一份实现。
 * 不传 placeholder——右半边里没有任何占位符，路径是真地址，选中它没有意义。
 *
 * @param path 相对路径（形如 `images/<uuid>.jpg`，来自 `ImageRepository.saveImage` 的
 *   `Image.filePath`）或任何绝对 URL；转义见 [markdownDestination]。
 */
fun insertImageRef(snapshot: TextSnapshot, path: String): TextSnapshot {
    val text = snapshot.text
    val start = minOf(snapshot.selectionStart, snapshot.selectionEnd).coerceIn(0, text.length)
    val end = maxOf(snapshot.selectionStart, snapshot.selectionEnd).coerceIn(0, text.length)
    return applyWrap(text, start, end, Form.Wrap("![", "](" + markdownDestination(path) + ")"))
}

/**
 * 把路径包装成合法的 Markdown 链接目标。
 *
 * 本应用自己生成的路径（`images/<uuid>.<ext>`）永远走 raw 分支，一个字符都不动。带尖括号的
 * 分支是给「日后有别的写入方递进来带空格或括号的路径」留的：CommonMark 的裸目标里空格直接
 * 截断链接（`![](my photo.jpg)` 渲染成字面量），不成对的括号也会截断——两种情况都不会报错，
 * 只是正文里多一行乱码般的字面量，属于最难查的一类损坏。`<...>` 形态里空格与括号都合法，
 * 只需转义反斜杠与尖括号本身；换行在任何形态里都不合法，压成空格。
 */
internal fun markdownDestination(path: String): String {
    val needsAngle = path.isEmpty() || path.any { it.isWhitespace() || it == '(' || it == ')' }
    if (!needsAngle) return path
    val escaped = path
        .replace("\\", "\\\\")
        .replace("<", "\\<")
        .replace(">", "\\>")
        .replace('\n', ' ')
        .replace('\r', ' ')
    return "<$escaped>"
}

/**
 * 给选区覆盖到的每一行加行首前缀。
 *
 * 跨行是刻意支持的：选中一段再点「列表」，整段一起变列表，这是所有正经编辑器的行为。
 * 从前只处理当前行，且插完把选区塌成一个光标。
 *
 * 选区末尾正好落在行首（`end` 前一个字符是换行）时不算下一行：拖选到行尾松手常常会
 * 多带一个换行，把下一行也加上前缀属于用户没要求的改动。
 */
private fun applyLinePrefix(text: String, start: Int, end: Int, prefix: String): TextSnapshot {
    val lineStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
    val effectiveEnd = if (end > start && end > lineStart && text[end - 1] == '\n') end - 1 else end
    val regionEnd = text.indexOf('\n', effectiveEnd).let { if (it < 0) text.length else it }
    val prefixed = text.substring(lineStart, regionEnd).split('\n').joinToString("\n") { prefix + it }
    val newText = text.substring(0, lineStart) + prefixed + text.substring(regionEnd)
    // end 之前一共插进了几段前缀 = 它跨过的行数；只算到 effectiveEnd，与上面加前缀的范围一致
    val linesBeforeEnd = 1 + text.substring(lineStart, effectiveEnd).count { it == '\n' }
    return TextSnapshot(
        text = newText,
        selectionStart = start + prefix.length,
        selectionEnd = end + prefix.length * linesBeforeEnd
    )
}

/**
 * 行内包裹。
 *
 * 无选区：前后两半都插下去，光标落在中间（`**|**`），接着打字就在标记里面。
 * 有选区：两半各就各位把原文夹住。从前这里只看 `selection.start`，把 `"****"` 整串插到
 * 选区**前面**，选中的字留在标记外头——选中「重要」点加粗得到 `****重要`，预览里是四个
 * 字面星号。
 *
 * 选区落点：链接与图片落在 `url` 占位符上（下一步就是填地址）；对称包裹保持选中原文，
 * 于是可以连点「加粗」再点「斜体」。
 */
private fun applyWrap(text: String, start: Int, end: Int, form: Form.Wrap): TextSnapshot {
    val left = form.left
    if (start == end) {
        val caret = start + left.length
        return TextSnapshot(
            text = text.substring(0, start) + left + form.right + text.substring(start),
            selectionStart = caret,
            selectionEnd = caret
        )
    }
    val newText = text.substring(0, start) + left + text.substring(start, end) +
        form.right + text.substring(end)
    val rightStart = end + left.length
    val placeholder = form.placeholder
    val placeholderAt = if (placeholder == null) -1 else form.right.indexOf(placeholder)
    return if (placeholder != null && placeholderAt >= 0) {
        TextSnapshot(
            text = newText,
            selectionStart = rightStart + placeholderAt,
            selectionEnd = rightStart + placeholderAt + placeholder.length
        )
    } else {
        TextSnapshot(newText, start + left.length, end + left.length)
    }
}

/**
 * 在 [at] 处插入一整块（表格 / 分隔线），前后按需补空行。
 *
 * 补空行不是排版洁癖：GFM 的表格**不能打断段落**，紧贴在正文行下一行的表格会被当成那一段
 * 的普通文字渲染出来（从前的表格模板只带一个前导 `\n`，正好踩这个）。`---` 更糟——紧贴在
 * 正文行下面时它是 setext 二级标题的下划线，会把上一行整行变成标题。
 *
 * 光标落点：有占位符（表格的 `列1`）就选中它，直接打字就是列名；没有就落在块尾。
 */
private fun insertBlock(text: String, at: Int, form: Form.Block): TextSnapshot {
    val lead = blockPadBefore(text, at)
    val newText = text.substring(0, at) + lead + form.left + blockPadAfter(text, at) + text.substring(at)
    val bodyStart = at + lead.length
    val placeholder = form.placeholder
    val placeholderAt = if (placeholder == null) -1 else form.left.indexOf(placeholder)
    return if (placeholder != null && placeholderAt >= 0) {
        TextSnapshot(
            text = newText,
            selectionStart = bodyStart + placeholderAt,
            selectionEnd = bodyStart + placeholderAt + placeholder.length
        )
    } else {
        val caret = bodyStart + form.left.length
        TextSnapshot(newText, caret, caret)
    }
}

/**
 * 用块级标记围住选区（代码块），前后按需补空行。
 *
 * 选区留在原处（栅栏内），所以选中一段代码点「代码块」就是把它围起来；无选区时首尾坐标
 * 相等，光标停在两条栅栏之间的空行上。
 */
private fun fenceBlock(text: String, start: Int, end: Int, form: Form.Block): TextSnapshot {
    val lead = blockPadBefore(text, start)
    val inner = text.substring(start, end)
    val newText = text.substring(0, start) + lead + form.left + inner + form.right +
        blockPadAfter(text, end) + text.substring(end)
    val innerStart = start + lead.length + form.left.length
    return TextSnapshot(newText, innerStart, innerStart + inner.length)
}

/**
 * [at] 之前要补几个换行，才能让块前面隔着一个空行。
 *
 * 文首（含「文首恰好是一个换行」）不补：那里本来就没有会被粘上的段落。
 */
private fun blockPadBefore(text: String, at: Int): String = when {
    at == 0 -> ""
    text[at - 1] != '\n' -> "\n\n"   // 插在行中间：先断行，再空一行
    at == 1 -> ""                    // 上面只有一个空行，够了
    text[at - 2] != '\n' -> "\n"     // 已在行首，上一行有内容 → 再加一个换行凑出空行
    else -> ""                       // 上面已经是空行
}

/**
 * [at] 之后要补几个换行。与 [blockPadBefore] 对称，只是文末补一个换行收尾而不是不补：
 * 块尾留个换行，用户接着敲字就落在块外面的新行上。
 */
private fun blockPadAfter(text: String, at: Int): String = when {
    at >= text.length -> "\n"
    text[at] != '\n' -> "\n\n"
    at + 1 >= text.length -> ""
    text[at + 1] != '\n' -> "\n"
    else -> ""
}

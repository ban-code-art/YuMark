package com.yumark.app.core.security

import java.io.File

/**
 * 外部 `ACTION_VIEW` intent 携带 URI 的准入判定。
 *
 * 背景：AndroidManifest 为兼容老式文件管理器保留了 `<data android:scheme="file"/>`。
 * `ContentResolver` 处理 `file://` 时不经任何提供器，直接走文件系统、用的是**本应用自己的 UID**；
 * 而编辑器的保存路径最终会 `openOutputStream(uri, "wt")` 截断写入
 * （`WorkspaceRepositoryImpl.writeDocument`）。于是第三方应用只要能发出一个指向 YuMark 私有目录的
 * `file://` VIEW intent，用户点开并保存，就等于借本应用的权限改写它自己的数据库/配置/文档
 * —— confused deputy。SAF 的 `content://` 不在此列：那条路由由系统按授权记账。
 *
 * 判定逻辑拆成纯函数放这里，便于 JVM 单测覆盖「路径前缀边界」这类最容易写错的地方；
 * 私有目录清单由 Android 侧（`MainActivity`）注入，本类不依赖 Android API。
 */
object IncomingUriGuard {

    /**
     * 放行的 scheme 白名单。
     *
     * `content` 是 SAF 正路；`file` 需再过 [isInsidePrivateRoots] 才算通过。
     * 其余（`intent`、`javascript`、`data`、`android_asset` 等）一律拒绝——外部 intent 是不可信输入，
     * 没有任何理由让它决定本应用去读写哪种资源。
     */
    private val ALLOWED_SCHEMES = setOf("content", "file")

    /** 需要额外做路径归属校验的 scheme。 */
    const val SCHEME_FILE = "file"

    /** [scheme] 是否在白名单内。调用方须先转小写（URI scheme 大小写不敏感）。 */
    fun isAllowedScheme(scheme: String?): Boolean = scheme != null && scheme in ALLOWED_SCHEMES

    /**
     * [path] 是否落在 [privateRoots] 任一棵目录树内（含等于根本身）。
     *
     * 两侧都必须是**已规范化**的绝对路径（见 [canonicalOrNull]），否则 `..` 能绕过前缀比较。
     * 比较带分隔符边界，故 `/data/user/0/com.yumark.app` 不会把
     * `/data/user/0/com.yumark.appx/note.md` 误判成私有。
     *
     * 空路径返回 `true`（视为私有 → 调用方拒绝）：拿不到路径就无法证明它安全。
     */
    fun isInsidePrivateRoots(path: String, privateRoots: List<String>): Boolean {
        if (path.isEmpty()) return true
        return privateRoots.any { root ->
            val r = root.trimEnd(SEPARATOR)
            r.isNotEmpty() && (path == r || path.startsWith(r + SEPARATOR))
        }
    }

    /**
     * 取 [rawPath] 的规范路径（消解 `.`、`..` 与符号链接），失败返回 `null`。
     *
     * `canonicalPath` 会触发文件系统访问并可能抛 [java.io.IOException]；拿不到规范形式时
     * 调用方应当**保守拒绝**，而不是退回未规范化的原串——那正是路径穿越的入口。
     */
    fun canonicalOrNull(rawPath: String?): String? {
        if (rawPath.isNullOrEmpty()) return null
        return runCatching { File(rawPath).canonicalPath }.getOrNull()
    }

    /**
     * Android 文件路径分隔符恒为 `/`。
     *
     * 不用 [File.separatorChar]：单测跑在 Windows JVM 上时它是 `\`，会让所有 `/data/...` 断言失配。
     */
    private const val SEPARATOR = '/'
}

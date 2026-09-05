package com.yumark.app.core.util

import java.io.File

/**
 * 路径包含校验的唯一实现。
 *
 * 抽出来的直接原因：`FileManager` 与 `ExportDocumentUseCase` 各自抄了一份，两份都写成
 * `canonicalFile.path.startsWith(canonicalDir.path)`——比的是**字符串前缀**，于是同级目录里
 * 名字以允许目录名开头的路径会被误判为在目录内：allowedDir 为 `…/files/documents` 时，
 * `…/files/documents-backup/x.md` 的字符串前缀匹配成立，校验直接放行，而它其实在目录外。
 * 两份拷贝还各自长出了不同的注释，改一处漏一处只是时间问题——安全校验有两份实现，
 * 实际强度就等于其中更弱的那一份。
 */
object PathSafety {

    /**
     * [file] 不在 [dir] 之内就抛 [SecurityException]。
     *
     * 判定用 [java.nio.file.Path.startsWith]：它按**路径段**逐段比较，`documents-backup`
     * 与 `documents` 是两个不同的段，不会像字符串前缀那样误判。
     * （`java.nio.file` 需要 API 26+，本模块 minSdk 26，可直接用。）
     *
     * 比较前两侧都取 canonicalFile：段比较本身挡不住符号链接——`dir/link` 指向 `/sdcard`
     * 时它的路径段仍然以 dir 开头。canonical 化会把符号链接和 `..` 都解析掉，
     * 之后 Path 只负责做段级包含判断。
     *
     * 一处平台差异需要知道（已实测 JDK 17）：Windows 上 `File.getCanonicalPath` **不**解析
     * 符号链接，只有 `Path.toRealPath` 会。生产环境是 Android/Linux，那里走 `realpath(3)`
     * 会解析，所以这里保持 canonicalFile；换成部分 `toRealPath` 才能跨平台一致，但
     * `toRealPath` 对不存在的路径直接抛 NoSuchFileException，而本校验的调用点大多是
     * 「还没创建的目标文件」（新建文档、导出新文件），代价不划算。单元测试里那条符号链接
     * 用例因此按平台能力探测后跳过，在 CI 的 Linux runner 上才真正断言。
     *
     * canonicalFile 自身可能抛 IOException（路径不可达、上层目录无权限）：不在这里吞掉，
     * 由调用方决定是当失败上报还是放行——校验器沉默地返回「通过」是最坏的选择。
     *
     * 抛 [SecurityException] 而不是 IllegalArgumentException 是有意的：`ErrorHandler.classify`
     * 把前者归为 `AppError.Permission`（「路径不合法或权限不足」），后者会落进
     * 「出现未知问题，请重试」。message 里保留绝对路径——它只进崩溃日志（导出时还会再脱敏），
     * 不带 [UserFacingMessage] 标记就不会被透到界面上。
     *
     * @param label 报错前缀，用来区分是哪一层校验拦下的（如 `"Output path"`）。
     */
    fun requireInside(file: File, dir: File, label: String = "File path") {
        val canonicalFile = file.canonicalFile
        val canonicalDir = dir.canonicalFile

        if (!canonicalFile.toPath().startsWith(canonicalDir.toPath())) {
            throw SecurityException(
                "$label ${canonicalFile.path} is outside allowed directory ${canonicalDir.path}"
            )
        }
    }
}

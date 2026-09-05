package com.yumark.app.core.validation

import com.yumark.app.R
import com.yumark.app.core.util.UiMessage

/**
 * 文件名验证器
 * 提供完整的文件名验证，包括：
 * - Windows 保留名称检查
 * - 非法字符检查
 * - 路径遍历检查
 * - 长度限制检查
 * - 空格和点号检查
 *
 * 文案一律以 [UiMessage] 形式返回：本文件在 `core`，拿不到 Context，解析留给界面层
 * （见 `presentation.common.resolve`）。也正因为只持有资源 id，本文件仍能在 JVM 测试里跑。
 */
object FileNameValidator {

    /**
     * Windows 系统保留名称
     * 这些名称在 Windows 上不能作为文件名或文件夹名
     */
    private val WINDOWS_RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /**
     * 非法字符正则表达式
     * 包括：/ \ : * ? " < > |
     */
    private val INVALID_CHARS_REGEX = Regex("[/\\\\:*?\"<>|]")

    /** 单个路径分量的长度上限：ext4 / APFS / NTFS 都是 255，取最小公分母。 */
    const val MAX_NAME_LENGTH = 255

    /**
     * [sanitize] 的截断阈值：比 [MAX_NAME_LENGTH] 留出扩展名与冲突后缀的余量
     * （远端同名文档会在主体后拼 `-<id前6位>`，见 `core.export.UniqueFileNames`）。
     */
    const val MAX_SANITIZED_LENGTH = 200

    /**
     * 验证文件名
     * @param name 要验证的文件名
     * @return ValidationResult.Success 如果验证通过，否则返回包含错误信息的 ValidationResult.Error
     */
    fun validate(name: String): ValidationResult {
        return when {
            // 检查是否为空
            name.isBlank() ->
                ValidationResult.Error(UiMessage.Res(R.string.validation_name_empty))

            // 检查长度限制
            name.length > MAX_NAME_LENGTH ->
                ValidationResult.Error(
                    UiMessage.of(R.string.validation_name_too_long, MAX_NAME_LENGTH)
                )

            // 检查开头或结尾是否有空格
            name.trim() != name ->
                ValidationResult.Error(UiMessage.Res(R.string.validation_name_edge_space))

            // 检查是否以点号开头
            name.startsWith(".") ->
                ValidationResult.Error(UiMessage.Res(R.string.validation_name_leading_dot))

            // 检查是否以点号结尾
            name.endsWith(".") ->
                ValidationResult.Error(UiMessage.Res(R.string.validation_name_trailing_dot))

            // 检查路径遍历攻击
            name.contains("..") ->
                ValidationResult.Error(UiMessage.Res(R.string.validation_name_dot_dot))

            // 检查非法字符
            INVALID_CHARS_REGEX.containsMatchIn(name) ->
                ValidationResult.Error(UiMessage.Res(R.string.validation_name_invalid_chars))

            // 检查 Windows 保留名称（不区分大小写）
            name.uppercase() in WINDOWS_RESERVED_NAMES ->
                ValidationResult.Error(UiMessage.of(R.string.validation_name_reserved, name))

            // 检查带扩展名的保留名称（例如 CON.txt 也是非法的）
            name.substringBefore(".").uppercase() in WINDOWS_RESERVED_NAMES ->
                ValidationResult.Error(
                    UiMessage.of(R.string.validation_name_reserved, name.substringBefore("."))
                )

            // 所有检查通过
            else -> ValidationResult.Success
        }
    }

    /**
     * 快速验证（仅返回布尔值）
     * @param name 要验证的文件名
     * @return true 如果验证通过，否则返回 false
     */
    fun isValid(name: String): Boolean {
        return validate(name) is ValidationResult.Success
    }

    /**
     * 清理文件名用于安全落盘：替换非法字符与路径片段；空结果回退 "document"
     * 与 validate 不同，sanitize 永远返回可用的文件名（用于导出等不应失败的场景）
     *
     * 截断**按码点**而不是按 Char：emoji 与部分生僻汉字在 UTF-16 里占两个 Char（代理对），
     * 裸 `substring(0, 200)` 若正好切在对中间，留下的是半个字符（孤立代理）——
     * 以 UTF-8 编码落盘时它无法映射，会变成 `?` 或乱码字节，拼进 WebDAV 的 PUT 还可能直接 400。
     */
    fun sanitize(name: String): String {
        val cleaned = name
            .replace(INVALID_CHARS_REGEX, "_")
            .replace("..", "_")
            .trim()

        if (cleaned.isBlank()) return "document"
        if (cleaned.length <= MAX_SANITIZED_LENGTH) return cleaned

        val cut = cleaned.substring(0, MAX_SANITIZED_LENGTH)
        // 末位是高位代理 → 它的低位被切掉了，整对一起丢（少一个字符，不留半个）
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }
}

/**
 * 验证结果密封类
 */
sealed class ValidationResult {
    /**
     * 验证成功
     */
    object Success : ValidationResult()

    /**
     * 验证失败
     * @param message 错误信息。是 [UiMessage] 而不是 String：产出侧在 `core`，拿不到 Context，
     *   由界面层解析成当前语区的文案。
     */
    data class Error(val message: UiMessage) : ValidationResult()
}

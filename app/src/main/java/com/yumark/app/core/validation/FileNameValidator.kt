package com.yumark.app.core.validation

/**
 * 文件名验证器
 * 提供完整的文件名验证，包括：
 * - Windows 保留名称检查
 * - 非法字符检查
 * - 路径遍历检查
 * - 长度限制检查
 * - 空格和点号检查
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

    /**
     * 验证文件名
     * @param name 要验证的文件名
     * @return ValidationResult.Success 如果验证通过，否则返回包含错误信息的 ValidationResult.Error
     */
    fun validate(name: String): ValidationResult {
        return when {
            // 检查是否为空
            name.isBlank() ->
                ValidationResult.Error("文件名不能为空")

            // 检查长度限制
            name.length > 255 ->
                ValidationResult.Error("文件名过长（最多 255 字符）")

            // 检查���头或结尾是否有空格
            name.trim() != name ->
                ValidationResult.Error("文件名开头或结尾不能有空格")

            // 检查是否以点号开头
            name.startsWith(".") ->
                ValidationResult.Error("文件名不能以点号开头")

            // 检查是否以点号结尾
            name.endsWith(".") ->
                ValidationResult.Error("文件名不能以点号结尾")

            // 检查路径遍历攻击
            name.contains("..") ->
                ValidationResult.Error("文件名不能包含 '..'")

            // 检查非法字符
            INVALID_CHARS_REGEX.containsMatchIn(name) ->
                ValidationResult.Error("文件名包含非法字符: / \\ : * ? \" < > |")

            // 检查 Windows 保留名称（不区分大小写）
            name.uppercase() in WINDOWS_RESERVED_NAMES ->
                ValidationResult.Error("'$name' 是系统保留名称，不能使用")

            // 检查带扩展名的保留名称（例如 CON.txt 也是非法的）
            name.substringBefore(".").uppercase() in WINDOWS_RESERVED_NAMES ->
                ValidationResult.Error("'${name.substringBefore(".")}' 是系统保留名称，不能使用")

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
     * 获取验证错误信息
     * @param name 要验证的文件名
     * @return 错误信息字符串，如果验证通过则返回 null
     */
    fun getErrorMessage(name: String): String? {
        return when (val result = validate(name)) {
            is ValidationResult.Success -> null
            is ValidationResult.Error -> result.message
        }
    }

    /**
     * 清理文件名用于安全落盘：替换非法字符与路径片段；空结果回退 "document"
     * 与 validate 不同，sanitize 永远返回可用的文件名（用于导出等不应失败的场景）
     */
    fun sanitize(name: String): String {
        val cleaned = name
            .replace(INVALID_CHARS_REGEX, "_")
            .replace("..", "_")
            .trim()

        if (cleaned.isBlank()) return "document"

        return if (cleaned.length > 200) cleaned.substring(0, 200) else cleaned
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
     * @param message 错误信息
     */
    data class Error(val message: String) : ValidationResult()
}

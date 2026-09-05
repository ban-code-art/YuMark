package com.yumark.app.core.validation

import com.google.common.truth.Truth.assertThat
import com.yumark.app.R
import com.yumark.app.core.util.UiMessage
import org.junit.jupiter.api.Test

/**
 * 断言的是**判定与资源 id**，不是文案字样：文案随语区变，资源 id 是编译期常量。
 *
 * 分支顺序也一并钉住。九个分支是 `when` 的顺序判定，一个名字同时踩两条规则时先报哪条
 * 决定了用户看到的提示——「. ./a」先说「不能以点号开头」还是先说「不能包含 '..'」，
 * 只有前者能让用户一次改对。
 */
class FileNameValidatorTest {

    private fun errorOf(name: String): UiMessage =
        (FileNameValidator.validate(name) as ValidationResult.Error).message

    // ---- 九个失败分支 ----

    @Test
    fun `空名与纯空白都被拒绝`() {
        assertThat(errorOf("")).isEqualTo(UiMessage.Res(R.string.validation_name_empty))
        // isBlank 而不是 isEmpty：全空格的名字落盘后在列表里是一行看不见的条目
        assertThat(errorOf("   ")).isEqualTo(UiMessage.Res(R.string.validation_name_empty))
        assertThat(errorOf("\t\n")).isEqualTo(UiMessage.Res(R.string.validation_name_empty))
    }

    @Test
    fun `超过上限的名字被拒绝且上限值作为实参带走`() {
        val tooLong = "a".repeat(FileNameValidator.MAX_NAME_LENGTH + 1)

        assertThat(errorOf(tooLong))
            .isEqualTo(UiMessage.of(R.string.validation_name_too_long, FileNameValidator.MAX_NAME_LENGTH))
    }

    @Test
    fun `正好等于上限的名字通过`() {
        // 边界是 `> MAX` 而不是 `>= MAX`：255 本身是各文件系统允许的
        val exact = "a".repeat(FileNameValidator.MAX_NAME_LENGTH)

        assertThat(FileNameValidator.validate(exact)).isEqualTo(ValidationResult.Success)
    }

    @Test
    fun `首尾空格被拒绝`() {
        assertThat(errorOf(" note")).isEqualTo(UiMessage.Res(R.string.validation_name_edge_space))
        assertThat(errorOf("note ")).isEqualTo(UiMessage.Res(R.string.validation_name_edge_space))
    }

    @Test
    fun `点号开头与点号结尾分别被拒绝`() {
        assertThat(errorOf(".hidden"))
            .isEqualTo(UiMessage.Res(R.string.validation_name_leading_dot))
        assertThat(errorOf("note."))
            .isEqualTo(UiMessage.Res(R.string.validation_name_trailing_dot))
    }

    @Test
    fun `路径遍历片段被拒绝`() {
        assertThat(errorOf("a..b")).isEqualTo(UiMessage.Res(R.string.validation_name_dot_dot))
    }

    @Test
    fun `九个非法字符逐个被拒绝`() {
        listOf("/", "\\", ":", "*", "?", "\"", "<", ">", "|").forEach { ch ->
            assertThat(errorOf("no${ch}te"))
                .isEqualTo(UiMessage.Res(R.string.validation_name_invalid_chars))
        }
    }

    @Test
    fun `Windows 保留名被拒绝且把名字回显为实参`() {
        assertThat(errorOf("CON")).isEqualTo(UiMessage.of(R.string.validation_name_reserved, "CON"))
        // 不区分大小写：NTFS 上 con 和 CON 是同一个设备名
        assertThat(errorOf("con")).isEqualTo(UiMessage.of(R.string.validation_name_reserved, "con"))
        assertThat(errorOf("LPT9")).isEqualTo(UiMessage.of(R.string.validation_name_reserved, "LPT9"))
    }

    @Test
    fun `带扩展名的保留名被拒绝且实参只回显主干`() {
        // CON.txt 在 Windows 上同样打不开；实参取主干，用户才知道要改的是 CON 而不是扩展名
        assertThat(errorOf("CON.txt"))
            .isEqualTo(UiMessage.of(R.string.validation_name_reserved, "CON"))
    }

    // ---- 分支顺序 ----

    @Test
    fun `同时踩多条规则时报靠前的那一条`() {
        // 「以点号开头」在「包含 ..」之前：".." 报的是点号开头
        assertThat(errorOf("..")).isEqualTo(UiMessage.Res(R.string.validation_name_leading_dot))
        // 首尾空格在点号之前：" .x" 报的是空格
        assertThat(errorOf(" .x")).isEqualTo(UiMessage.Res(R.string.validation_name_edge_space))
        // 长度在首尾空格之前
        val longWithSpace = " " + "a".repeat(FileNameValidator.MAX_NAME_LENGTH)
        assertThat(errorOf(longWithSpace))
            .isEqualTo(UiMessage.of(R.string.validation_name_too_long, FileNameValidator.MAX_NAME_LENGTH))
    }

    // ---- 通过的名字 ----

    @Test
    fun `常见合法名字通过`() {
        listOf(
            "note",
            "note.md",
            "会议记录 2026-09",
            "v1.2.3 设计稿",
            "CONSOLE",       // 只是以 CON 开头，不是保留名
            "a-b_c(1)[2]",
            "文件名里有.点"
        ).forEach { name ->
            assertThat(FileNameValidator.validate(name)).isEqualTo(ValidationResult.Success)
            assertThat(FileNameValidator.isValid(name)).isTrue()
        }
    }

    @Test
    fun `isValid 与 validate 判定一致`() {
        assertThat(FileNameValidator.isValid("note.md")).isTrue()
        assertThat(FileNameValidator.isValid("a/b")).isFalse()
        assertThat(FileNameValidator.isValid("")).isFalse()
    }

    // ---- sanitize：永不失败，但必须真的清干净 ----

    @Test
    fun `sanitize 清掉非法字符与路径片段`() {
        val cleaned = FileNameValidator.sanitize("../../etc/passwd")

        // 断言的是安全不变量而不是具体替换结果：落盘路径里绝不能再出现分隔符或上跳片段
        assertThat(cleaned).doesNotContain("/")
        assertThat(cleaned).doesNotContain("\\")
        assertThat(cleaned).doesNotContain("..")
        assertThat(FileNameValidator.sanitize("a:b*c?d")).isEqualTo("a_b_c_d")
    }

    @Test
    fun `sanitize 对清空后的名字给出兜底`() {
        // 导出等场景不允许失败，必须有个能用的名字
        assertThat(FileNameValidator.sanitize("   ")).isEqualTo("document")
        assertThat(FileNameValidator.sanitize("")).isEqualTo("document")
    }

    @Test
    fun `sanitize 截断到 200 字符`() {
        assertThat(FileNameValidator.sanitize("a".repeat(500))).hasLength(200)
        assertThat(FileNameValidator.MAX_SANITIZED_LENGTH).isEqualTo(200)
    }

    @Test
    fun `sanitize 按码点截断，不留半个 emoji`() {
        // 下标 199（第 200 个 Char）正好是 emoji 的高位代理：裸 substring(0,200) 会切出孤立代理，
        // 以 UTF-8 落盘时它无法映射，变成 '?' 或乱码字节，拼进 WebDAV 的 PUT 还可能直接 400
        val name = "a".repeat(199) + "😀".repeat(10)
        val cut = FileNameValidator.sanitize(name)

        // 整对一起丢：宁可少一个字符，也不能留半个
        assertThat(cut).isEqualTo("a".repeat(199))
        assertThat(cut).hasLength(FileNameValidator.MAX_SANITIZED_LENGTH - 1)
        // UTF-8 往返：孤立代理字符编码时会被替换成 '?'，往返后就不等于原串了
        assertThat(String(cut.toByteArray(Charsets.UTF_8), Charsets.UTF_8)).isEqualTo(cut)
    }

    @Test
    fun `完整代理对落在边界内不被误砍`() {
        // 下标 198-199 是完整一对（末位是低位代理）：这时候不能少砍也不能多砍
        val name = "a".repeat(198) + "😀".repeat(10)
        val cut = FileNameValidator.sanitize(name)

        assertThat(cut).hasLength(FileNameValidator.MAX_SANITIZED_LENGTH)
        assertThat(cut).endsWith("😀")
        assertThat(String(cut.toByteArray(Charsets.UTF_8), Charsets.UTF_8)).isEqualTo(cut)
    }
}

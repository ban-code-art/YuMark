package com.yumark.app.core.export

import com.google.common.truth.Truth.assertThat
import com.yumark.app.core.export.UniqueFileNames.Entry
import org.junit.jupiter.api.Test

/**
 * 远端文件名分配的单测。
 *
 * 重点不是"好看"，而是三条会真丢数据/搅乱历史的性质：
 * 与输入顺序无关、任何两篇文档不会撞到同一个名字、名字长度不超服务端上限。
 */
class UniqueFileNamesTest {

    @Test
    fun `同名文档的名字与输入顺序无关`() {
        val a = Entry("aaa111", "笔记")
        val b = Entry("bbb222", "笔记")
        val forward = UniqueFileNames.assign(listOf(a, b))
        val backward = UniqueFileNames.assign(listOf(b, a))
        assertThat(forward).isEqualTo(backward)
        // 同名分组里没人"抢到"裸名，两边都带自己的 id 后缀
        assertThat(forward["aaa111"]).isEqualTo("笔记-aaa111.md")
        assertThat(forward["bbb222"]).isEqualTo("笔记-bbb222.md")
    }

    @Test
    fun `不同名文档保持裸名`() {
        val out = UniqueFileNames.assign(listOf(Entry("i1", "笔记"), Entry("i2", "日记")))
        assertThat(out.values).containsExactly("笔记.md", "日记.md")
    }

    @Test
    fun `短 id 前缀相同也不能撞名`() {
        val out = UniqueFileNames.assign(
            listOf(Entry("abcdef-x1", "笔记"), Entry("abcdef-y2", "笔记"))
        )
        assertThat(out).hasSize(2)
        assertThat(out.values.toSet()).hasSize(2)
        // 退到完整 id 才分得开
        assertThat(out["abcdef-y2"]).isEqualTo("笔记-abcdef-y2.md")
    }

    @Test
    fun `只差大小写也算撞名`() {
        val out = UniqueFileNames.assign(listOf(Entry("i1", "Note"), Entry("i2", "note")))
        assertThat(out["i1"]).isEqualTo("Note-i1.md")
        assertThat(out["i2"]).isEqualTo("note-i2.md")
    }

    @Test
    fun `非法字符与路径片段被替换`() {
        val out = UniqueFileNames.assign(listOf(Entry("i1", "a/b:c*d"), Entry("i2", "../etc")))
        assertThat(out["i1"]).isEqualTo("a_b_c_d.md")
        // sanitize 先把 `/` 换成 `_` 得到 `.._etc`，再把 `..` 换成 `_`：不留任何可回溯上级的片段
        assertThat(out["i2"]).isEqualTo("__etc.md")
    }

    @Test
    fun `控制字符被剔除，结尾的点和空格被去掉`() {
        // 粘贴多行文本进标题会带进换行，拼进 URL 会让 PUT 直接 400
        val out = UniqueFileNames.assign(listOf(Entry("i1", "行1\n行2\t"), Entry("i2", "报告. ")))
        assertThat(out["i1"]).isEqualTo("行1行2.md")
        // Windows 会静默丢掉结尾的点和空格，留着就对不上账
        assertThat(out["i2"]).isEqualTo("报告.md")
    }

    @Test
    fun `保留设备名加前缀`() {
        val out = UniqueFileNames.assign(listOf(Entry("i1", "CON"), Entry("i2", "nul")))
        assertThat(out["i1"]).isEqualTo("_CON.md")
        assertThat(out["i2"]).isEqualTo("_nul.md")
    }

    @Test
    fun `空名与纯空白名回退到 document`() {
        val out = UniqueFileNames.assign(listOf(Entry("i1", ""), Entry("i2", "   ")))
        assertThat(out.values.toSet()).hasSize(2)
        assertThat(out.values).containsExactly("document-i1.md", "document-i2.md")
    }

    @Test
    fun `扩展名可换`() {
        val out = UniqueFileNames.assign(listOf(Entry("i1", "笔记")), extension = ".html")
        assertThat(out["i1"]).isEqualTo("笔记.html")
    }

    @Test
    fun `空列表返回空表`() {
        assertThat(UniqueFileNames.assign(emptyList())).isEmpty()
    }

    @Test
    fun `超长名字加了后缀也不超上限`() {
        val long = "a".repeat(300)
        val out = UniqueFileNames.assign(listOf(Entry("aaa111", long), Entry("bbb222", long)))
        assertThat(out.values.toSet()).hasSize(2)
        out.values.forEach {
            // 截的是主体不是后缀：后缀被截掉就失去区分作用，服务端也会因超长拒收
            assertThat(it.removeSuffix(".md").length).isEqualTo(UniqueFileNames.MAX_BASE_LENGTH)
        }
        assertThat(out["aaa111"]).endsWith("-aaa111.md")
        assertThat(out["bbb222"]).endsWith("-bbb222.md")
    }

    @Test
    fun `按码点截断，不留半个 emoji`() {
        // 每个 emoji 占 2 个 char，截到奇数位就会切出孤立代理字符，部分服务端直接 400
        val emoji = "😀".repeat(150)
        val out = UniqueFileNames.assign(listOf(Entry("aaa111", emoji), Entry("bbb222", emoji)))
        assertThat(out).hasSize(2)
        out.values.forEach { name ->
            assertThat(name.removeSuffix(".md").length)
                .isAtMost(UniqueFileNames.MAX_BASE_LENGTH)
            // UTF-8 往返：孤立代理字符编码时会被替换成 '?'，往返后就不等于原串了
            assertThat(String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8)).isEqualTo(name)
        }
    }
}

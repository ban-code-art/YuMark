package com.yumark.app.core.search

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [FtsQueryBuilder] 的注入安全契约。
 *
 * FTS4 的 MATCH 语法没有转义机制，所以这里不做转义而是**字面化**：先过
 * [FtsTextNormalizer]（所有非字母数字字符退化成分隔符），再整体套一对双引号。
 * 输出恒定形如 `"tok1 tok2"`，全串只有首尾两个双引号。
 *
 * 盯住两类事故：
 * - 硬报错：未闭合的 `"`、`NEAR/2` 会让 SQLite 抛 `malformed MATCH expression`，搜索直接失败；
 * - 静默变味：`kot*` 变前缀查询、`a OR b` 变布尔或、`-x` 变排除项，
 *   用户以为在搜字面量，实际执行的是另一条查询。
 */
class FtsQueryBuilderTest {

    /** 恶意/畸形输入池：每一条单独拼进 MATCH 都会报错或改变语义 */
    private val hostileInputs = listOf(
        "\"", "\"\"", "\"unterminated", "a\"b", "*", "kot*", "*kot*",
        "^", "^start", "-", "-exclude", ":", "col:val", "(", ")", "()",
        "a OR b", "a AND b", "NOT a", "foo NEAR bar", "foo NEAR/2 bar",
        "a OR b AND NOT c NEAR/9 d", "a\"b*c^d-e:f OR g NEAR/2 h",
        "\"a\" OR \"b\"", "x*:^-\"", "'; DROP TABLE document_search; --"
    )

    // ---- 正常查询 ----

    @Test
    fun `英文查询编译成双引号短语`() {
        assertThat(FtsQueryBuilder.build("kotlin")).isEqualTo("\"kotlin\"")
    }

    @Test
    fun `中文查询逐字拆开后仍是一条短语`() {
        // 短语查询要求 token 相邻，这才等价于「子串匹配」；拆成 OR 就变成了任意一字命中
        assertThat(FtsQueryBuilder.build("我爱北京")).isEqualTo("\"我 爱 北 京\"")
    }

    @Test
    fun `中英混排查询保持英文词完整`() {
        assertThat(FtsQueryBuilder.build("Kotlin协程")).isEqualTo("\"kotlin 协 程\"")
    }

    @Test
    fun `大小写不影响编译结果`() {
        val expected = "\"kotlin\""
        assertThat(FtsQueryBuilder.build("KOTLIN")).isEqualTo(expected)
        assertThat(FtsQueryBuilder.build("Kotlin")).isEqualTo(expected)
    }

    // ---- 返回 null 的情况 ----

    @Test
    fun `空串返回 null`() {
        // 返回空串会让调用方拿 "" 去 MATCH，那是一条合法但永不命中的查询，比降级更糟
        assertThat(FtsQueryBuilder.build("")).isNull()
    }

    @Test
    fun `纯空白返回 null`() {
        assertThat(FtsQueryBuilder.build("   ")).isNull()
        assertThat(FtsQueryBuilder.build("\t\n")).isNull()
        assertThat(FtsQueryBuilder.build(" 　")).isNull()
    }

    @Test
    fun `纯标点返回 null`() {
        assertThat(FtsQueryBuilder.build("！！！")).isNull()
        assertThat(FtsQueryBuilder.build("***")).isNull()
        assertThat(FtsQueryBuilder.build("---")).isNull()
        assertThat(FtsQueryBuilder.build("\"\"\"")).isNull()
    }

    @Test
    fun `纯 emoji 返回 null`() {
        assertThat(FtsQueryBuilder.build("😀")).isNull()
        assertThat(FtsQueryBuilder.build("😀😃😄")).isNull()
    }

    // ---- token 数上限 ----

    @Test
    fun `恰好达到上限的查询仍然编译`() {
        assertThat(FtsQueryBuilder.build("我".repeat(FtsQueryBuilder.MAX_PHRASE_TOKENS)))
            .isNotNull()
        val ascii = (0 until FtsQueryBuilder.MAX_PHRASE_TOKENS).joinToString(" ") { "w$it" }
        assertThat(FtsQueryBuilder.build(ascii)).isNotNull()
    }

    @Test
    fun `超过上限返回 null 而不是截断`() {
        // 截断会让「粘贴一大段去搜」匹配到只共享前 64 个 token 的无关文档，精度崩掉；
        // 返回 null 让调用方退回精确的子串扫描
        assertThat(FtsQueryBuilder.build("我".repeat(FtsQueryBuilder.MAX_PHRASE_TOKENS + 1)))
            .isNull()
        val ascii = (0..FtsQueryBuilder.MAX_PHRASE_TOKENS).joinToString(" ") { "w$it" }
        assertThat(FtsQueryBuilder.build(ascii)).isNull()
    }

    // ---- 注入与畸形输入 ----

    @Test
    fun `未闭合双引号不会漏出去`() {
        // 这一条直接对应 malformed MATCH expression：漏一个引号搜索就整体失败
        assertThat(FtsQueryBuilder.build("\"unterminated")).isEqualTo("\"unterminated\"")
    }

    @Test
    fun `星号不再是前缀查询`() {
        assertThat(FtsQueryBuilder.build("kot*")).isEqualTo("\"kot\"")
    }

    @Test
    fun `布尔运算符落在引号内成为普通词`() {
        assertThat(FtsQueryBuilder.build("a OR b")).isEqualTo("\"a or b\"")
        assertThat(FtsQueryBuilder.build("NOT a")).isEqualTo("\"not a\"")
    }

    @Test
    fun `NEAR 运算符被拆成词与数字`() {
        assertThat(FtsQueryBuilder.build("foo NEAR/2 bar")).isEqualTo("\"foo near 2 bar\"")
    }

    @Test
    fun `列限定与排除前缀失效`() {
        assertThat(FtsQueryBuilder.build("^start")).isEqualTo("\"start\"")
        assertThat(FtsQueryBuilder.build("-exclude")).isEqualTo("\"exclude\"")
        assertThat(FtsQueryBuilder.build("col:val")).isEqualTo("\"col val\"")
    }

    @Test
    fun `混合运算符轰炸后仍是单条短语`() {
        assertThat(FtsQueryBuilder.build("a\"b*c^d-e:f OR g NEAR/2 h"))
            .isEqualTo("\"a b c d e f or g near 2 h\"")
    }

    @Test
    fun `SQL 注入片段被当作普通词`() {
        // MATCH 的参数是绑定值不会被当 SQL 解析，这里防的是它污染 FTS 表达式本身
        assertThat(FtsQueryBuilder.build("'; DROP TABLE document_search; --"))
            .isEqualTo("\"drop table document search\"")
    }

    // ---- 全量恶意输入的共性 ----

    @Test
    fun `任何恶意输入的输出都只含首尾两个双引号`() {
        hostileInputs.forEach { input ->
            val built = FtsQueryBuilder.build(input) ?: return@forEach
            assertThat(built.count { it == '"' }).isEqualTo(2)
            assertThat(built.first()).isEqualTo('"')
            assertThat(built.last()).isEqualTo('"')
        }
    }

    @Test
    fun `任何恶意输入的引号内都不含 FTS 语法字符`() {
        hostileInputs.forEach { input ->
            val built = FtsQueryBuilder.build(input) ?: return@forEach
            val inner = built.substring(1, built.length - 1)
            listOf('"', '*', '^', '-', ':', '(', ')', '/').forEach { forbidden ->
                assertThat(inner).doesNotContain(forbidden.toString())
            }
        }
    }

    @Test
    fun `任何恶意输入都不会抛异常`() {
        // build 的契约是「要么给出可安全绑定的短语，要么给 null」，没有第三种出口
        hostileInputs.forEach { input ->
            FtsQueryBuilder.build(input)
        }
    }

    @Test
    fun `输出与规范化结果严格对应`() {
        // 索引侧写入的是 normalize 的结果，查询侧必须是同一串文本外加引号，否则两边对不上
        hostileInputs.plus(listOf("kotlin", "我爱北京", "Kotlin协程")).forEach { input ->
            val normalized = FtsTextNormalizer.normalize(input)
            val built = FtsQueryBuilder.build(input)
            if (normalized.isEmpty()) {
                assertThat(built).isNull()
            } else {
                assertThat(built).isEqualTo("\"$normalized\"")
            }
        }
    }
}

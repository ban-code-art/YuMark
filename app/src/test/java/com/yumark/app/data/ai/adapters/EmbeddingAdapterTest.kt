package com.yumark.app.data.ai.adapters

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [OpenAiEmbeddingAdapter.parseEmbeddingsResponse] 的解析与校验分支。
 *
 * 结构缺陷必须抛出而不是静默降级：空向量/被污染的向量会骗过 RagPipeline 只数条数的 check 落库，
 * 在 cosine 里永久判 0f（分块从此向量检索不到），却仍被 knowledge_stats 计成「可向量检索」——
 * 一个没有任何信号的幽灵分块。抛出后 indexDocument 判 job 失败（旧索引不动），embedQuery 诚实
 * 退化为关键词检索。
 */
class EmbeddingAdapterTest {

    @Test
    fun `正常响应按 index 对齐输入序`() {
        // 乱序返回的端点真实存在（并行推理后按完成序拼接）：靠 index 而非出现序对齐。
        val body = """
            {"object":"list","data":[
              {"object":"embedding","index":1,"embedding":[0.0,1.0]},
              {"object":"embedding","index":0,"embedding":[1.0,0.0]}
            ]}
        """.trimIndent()

        val vectors = OpenAiEmbeddingAdapter.parseEmbeddingsResponse(body)

        assertThat(vectors).hasSize(2)
        assertThat(vectors[0].toList()).containsExactly(1.0f, 0.0f).inOrder()
        assertThat(vectors[1].toList()).containsExactly(0.0f, 1.0f).inOrder()
    }

    @Test
    fun `元素缺 embedding 数组时抛出而不是返回空向量`() {
        val body = """
            {"data":[{"object":"embedding","index":0},{"object":"embedding","index":1,"embedding":[1.0]}]}
        """.trimIndent()

        val e = assertThrows<IllegalStateException> {
            OpenAiEmbeddingAdapter.parseEmbeddingsResponse(body)
        }
        assertThat(e.message).contains("缺少 embedding 数组")
        // 报出是第几个元素，排查时才能对上输入序
        assertThat(e.message).contains("1")
    }

    @Test
    fun `embedding 含非数值分量时抛出而不是塞 0f`() {
        // 静默把 "null"/"NaN" 分量塞 0f 得到的是一个维度正确、方向错误的向量——比空向量更隐蔽：
        // 检索照常出结果，名次全是噪声，用户无从察觉。
        val body = """
            {"data":[{"object":"embedding","index":0,"embedding":[1.0,"oops"]}]}
        """.trimIndent()

        val e = assertThrows<IllegalStateException> {
            OpenAiEmbeddingAdapter.parseEmbeddingsResponse(body)
        }
        assertThat(e.message).contains("非数值分量")
    }

    @Test
    fun `缺 data 字段或整体不是 JSON 对象时抛出`() {
        assertThrows<IllegalStateException> {
            OpenAiEmbeddingAdapter.parseEmbeddingsResponse("""{"error":"nope"}""")
        }
        assertThrows<IllegalStateException> {
            OpenAiEmbeddingAdapter.parseEmbeddingsResponse("not json at all")
        }
    }

    @Test
    fun `单元素零维向量是合法的空数组返回`() {
        // 真正合法的空 embedding（provider 明说零维）不该被误杀：它尺寸为 0 但结构完整，
        // 与「字段缺失被顶替出来的空向量」不同。对齐语义（按 index）依旧成立。
        val body = """{"data":[{"object":"embedding","index":0,"embedding":[]}]}"""

        val vectors = OpenAiEmbeddingAdapter.parseEmbeddingsResponse(body)

        assertThat(vectors).hasSize(1)
        assertThat(vectors[0]).isEmpty()
    }
}

package ai.vibecafe.usage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用量明细 CSV 导出回归测试。
 *
 * 要锁的口径：
 * 1. 列顺序与表头固定（下游 Excel / 脚本按位置读）；
 * 2. 行按 bucketStart 升序，导出结果稳定可 diff；
 * 3. RFC 4180 转义：逗号 / 双引号 / 换行不能把表格结构写坏；
 * 4. 空账号只输出表头，不产生半行。
 */
class UsageCsvTest {

    private fun bucket(
        start: String,
        source: String = "claude",
        model: String = "claude-sonnet",
        project: String = "proj",
        hostname: String = "host",
        input: Long = 1,
        output: Long = 2,
        cached: Long = 3,
        reasoning: Long = 4,
        total: Long = 10,
        cost: Double = 0.5
    ) = Bucket(
        source = source,
        model = model,
        project = project,
        hostname = hostname,
        bucketStart = start,
        inputTokens = input,
        outputTokens = output,
        cachedInputTokens = cached,
        reasoningOutputTokens = reasoning,
        totalTokens = total,
        estimatedCost = cost
    )

    @Test
    fun `header and rows are sorted by bucket start`() {
        val data = UsageResponse(
            buckets = listOf(
                bucket("2026-11-02", source = "claude", model = "mo\"del", project = "proj,alpha", hostname = "host\nname"),
                bucket(
                    "2026-11-01",
                    source = "codex",
                    model = "gpt",
                    project = "plain",
                    hostname = "pc",
                    input = 5, output = 6, cached = 7, reasoning = 8, total = 26, cost = 1.25
                )
            ),
            sessions = emptyList(),
            hasAnyData = true
        )

        val expected =
            "date,source,model,project,hostname,input_tokens,output_tokens," +
                "cached_input_tokens,reasoning_output_tokens,total_tokens,cost_usd\n" +
                "2026-11-01,codex,gpt,plain,pc,5,6,7,8,26,1.25\n" +
                "2026-11-02,claude,\"mo\"\"del\",\"proj,alpha\",\"host\nname\",1,2,3,4,10,0.5\n"

        assertEquals(expected, UsageCsv.build(data))
    }

    @Test
    fun `carriage return also triggers quoting`() {
        val data = UsageResponse(
            buckets = listOf(bucket("2026-11-03", project = "win\r\npath")),
            sessions = emptyList(),
            hasAnyData = true
        )
        val csv = UsageCsv.build(data)
        assertTrue(csv.contains("\"win\r\npath\""))
    }

    @Test
    fun `empty account exports header only`() {
        val csv = UsageCsv.build(UsageResponse(emptyList(), emptyList(), false))
        val header = "date,source,model,project,hostname,input_tokens,output_tokens," +
            "cached_input_tokens,reasoning_output_tokens,total_tokens,cost_usd"
        assertEquals(header + "\n", csv)
    }
}

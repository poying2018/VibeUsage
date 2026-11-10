package ai.vibecafe.usage.data

/**
 * 用量明细 CSV 导出（当前账号的全量日粒度 buckets）。
 *
 * 列：日期/来源/模型/项目/设备/各类 Token/费用。字段按 RFC 4180 转义，
 * 模型名或项目名里带逗号、引号、换行都不会把表格结构写坏。
 */
object UsageCsv {

    private const val HEADER =
        "date,source,model,project,hostname,input_tokens,output_tokens," +
            "cached_input_tokens,reasoning_output_tokens,total_tokens,cost_usd"

    fun build(data: UsageResponse): String {
        val sb = StringBuilder(HEADER).append('\n')
        data.buckets
            .sortedBy { it.bucketStart }
            .forEach { b ->
                sb.append(esc(b.bucketStart)).append(',')
                    .append(esc(b.source)).append(',')
                    .append(esc(b.model)).append(',')
                    .append(esc(b.project)).append(',')
                    .append(esc(b.hostname)).append(',')
                    .append(b.inputTokens).append(',')
                    .append(b.outputTokens).append(',')
                    .append(b.cachedInputTokens).append(',')
                    .append(b.reasoningOutputTokens).append(',')
                    .append(b.totalTokens).append(',')
                    .append(b.estimatedCost)
                    .append('\n')
            }
        return sb.toString()
    }

    private fun esc(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}

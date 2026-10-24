package ai.vibecafe.usage.ui

import ai.vibecafe.usage.share.ShareCard
import ai.vibecafe.usage.stats.TimeRange

/**
 * 用量卡载荷：把当前 UI 状态翻译成分享卡的渲染数据。
 *
 * 「分享」和「保存到相册」两个入口画出来的必须是**同一张**图，
 * 所以口径只写在这里一份。原先两个入口各写各的映射，
 * 改动其中一处忘了另一处，两边画出来的卡片就会对不上。
 */
fun buildSharePayload(state: UiState): ShareCard.Payload? {
    val stats = state.stats ?: return null
    return ShareCard.Payload(
        rangeLabel = when (state.selectedTimeRange) {
            TimeRange.CUSTOM -> state.customRange?.let { "总消耗 ${it.from} ~ ${it.to}" }
                ?: "总消耗（自定义范围）"
            else -> "总消耗 · " + mapOf(
                TimeRange.TODAY to "今日",
                TimeRange.HOURS_24 to "近 24 小时",
                TimeRange.DAYS_7 to "近 7 天",
                TimeRange.DAYS_30 to "近 30 天",
                TimeRange.DAYS_90 to "近 90 天",
                TimeRange.ALL to "全部"
            )[state.selectedTimeRange].orEmpty()
        },
        totalCost = stats.totalCost,
        totalTokens = stats.totalTokens,
        toolCount = stats.toolCount,
        modelCount = stats.modelCount,
        sessionCount = stats.sessionCount,
        monthProjected = state.monthProjection?.projected,
        trendPercent = state.trendPercent,
        topTools = state.toolDistribution.take(3).map {
            ShareCard.ShareItem(it.tool, it.cost, it.percentage / 100f)
        },
        topModels = state.modelCosts.take(3).map {
            ShareCard.ShareItem(
                it.model,
                it.cost,
                (if (stats.totalCost > 0.0) it.cost / stats.totalCost else 0.0).toFloat()
            )
        },
        deviceFilter = state.selectedDevices.takeIf { it.isNotEmpty() }
            ?.sorted()?.joinToString("、"),
        inputTokens = stats.totalInputTokens,
        outputTokens = stats.totalOutputTokens
    )
}

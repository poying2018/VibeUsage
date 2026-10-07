package ai.vibecafe.usage.data.quota

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Bar
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Usage
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.compact
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.epochReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.fetchModelIds
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatCountdown
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.gson

// ─── MiniMax（Token Plan / Coding Plan 请求次数额度）───

object MiniMax {
    /**
     * Token Plan / M Plan 额度端点。国内站在前（国内 sk-cp- key 最多），国际站兜底：
     * 同一把 key 在另一个大区会返回 status_code 2049 invalid api key，所以两区都得试。
     * api. / www. 两组 host 都在用——官方文档给 www.，api. 才是控制台实际调用的。
     *
     * 旧端点 /v1/api/openplatform/coding_plan/remains 只能带网页登录 Cookie，
     * 用 API Key 打过去恒返回 1004「cookie is missing, log in again」，已废弃。
     */
    private val HOSTS = listOf(
        "https://api.minimaxi.com",
        "https://www.minimaxi.com",
        "https://api.minimax.io",
        "https://www.minimax.io",
    )

    private const val QUOTA_PATH = "/v1/token_plan/remains"

    /**
     * 单次查询的总耗时预算（毫秒）。4 台 host 顺序兜底，每台最坏 connect 15s + read 20s，
     * 全挂时能拖到 140 秒——额度页会一直转圈。软预算：只在换 host 前检查，
     * 已经发出的请求不打断。超时即按当前最好的错误返回，不再往下试。
     */
    private const val TOTAL_BUDGET_MS = 20_000L

    /** 模型列表对同一把密钥是静态的，进程内缓存，省掉每次刷新多打一次 /v1/models。 */
    @Volatile
    private var modelsCache: Pair<String, List<String>>? = null

    /** model_name 现在是「额度分桶名」不是模型 id，桶名直接透到 UI 太生硬。 */
    private val BUCKET_LABELS = mapOf(
        "general" to "通用（文本/图像/音频）",
        "video" to "视频",
    )

    private fun modelsOf(host: String, apiKey: String): List<String> {
        val cacheKey = "$host|$apiKey"
        modelsCache?.let { (k, v) -> if (k == cacheKey) return v }
        val models = fetchModelIds("$host/v1/models", apiKey, proxyFirst = false, directOnly = true)
        // 只缓存成功结果：空列表可能是网络抖动，缓存下来会把偶发故障固化成"没有模型"
        if (models.isNotEmpty()) modelsCache = cacheKey to models
        return models
    }

    /**
     * 查询额度。拿不到模型列表不影响额度展示，整体失败才抛。
     *
     * 兜底顺序、20s 总预算、鉴权错误优先这三条都在 [withHostFallback] 里，
     * 分支行为由 HostFallbackTest 锁住。
     */
    fun fetchUsage(apiKey: String): Usage =
        withHostFallback(HOSTS, TOTAL_BUDGET_MS, emptyMessage = "MiniMax 查询失败") { host ->
            val resp = QuotaHttp.get(
                "$host$QUOTA_PATH",
                bearer = apiKey,
                proxyFirst = false,
                directOnly = true,
                ua = "MiniMax-Usage-Checker"
            )
            // 模型列表走同一台主机：这把 key 在哪个大区被认下来，就只问那个大区，
            // 否则跨区查 /v1/models 必然 401，模型区永远空着
            parseRemains(resp).copy(models = modelsOf(host, apiKey))
        }

    /**
     * 解析 /v1/token_plan/remains 响应。
     *
     * 现行 Token Plan（sk-cp- 订阅密钥）下发的是**剩余百分比**：
     * current_interval_remaining_percent / current_weekly_remaining_percent（0–100），
     * 而 current_*_total_count / current_*_usage_count 恒为 0 —— 只按 count 换算会一条都渲染不出来。
     * 老形态（count 有效）仍走兜底换算，且 usage_count 实为「剩余次数」。
     */
    internal fun parseRemains(text: String): Usage {
        val obj = gson.fromJson(text, JsonObject::class.java)
            ?: throw QuotaException(0, "MiniMax 返回了空响应")
        val base = obj.optObject("base_resp")
        val code = base?.get("status_code")?.takeIf { it.isJsonPrimitive }?.asInt
        if (code != null && code != 0) {
            throw QuotaException(
                code,
                jstr(base, "status_msg") ?: "MiniMax 错误 $code"
            )
        }
        val remains = obj.optArray("model_remains")
            ?: throw QuotaException(0, "MiniMax 未返回额度数据，请确认填的是 Token Plan 订阅密钥（sk-cp- 开头）")

        val groups = linkedMapOf<String, MutableList<Bar>>()
        for (el in remains) {
            val m = el as? JsonObject ?: continue
            val bucket = jstr(m, "model_name") ?: continue
            val title = BUCKET_LABELS[bucket.lowercase()] ?: bucket
            val interval = windowBar(
                m, title,
                percentKey = "current_interval_remaining_percent",
                totalKey = "current_interval_total_count",
                usageKey = "current_interval_usage_count",
                endKey = "end_time",
                remainsMsKey = "remains_time",
            )
            val weekly = windowBar(
                m, title,
                percentKey = "current_weekly_remaining_percent",
                totalKey = "current_weekly_total_count",
                usageKey = "current_weekly_usage_count",
                endKey = "weekly_end_time",
                remainsMsKey = "weekly_remains_time",
            )
            if (interval != null) groups.getOrPut("5 小时窗口") { mutableListOf() } += interval
            if (weekly != null) groups.getOrPut("每周窗口") { mutableListOf() } += weekly
        }
        if (groups.isEmpty())
            throw QuotaException(0, "MiniMax 没有返回任何额度窗口（当前套餐可能未开通或未使用过）")
        return Usage(null, groups)
    }

    /**
     * 取单个额度窗口的进度条，取不到返回 null。
     *
     * 只认服务端给的 *_remaining_percent 与老形态的 count；current_*_status 的取值语义
     * 没有权威文档，不拿它做过滤（实测 general=1、video=3，而 video 的两个百分比都是 100，
     * 说明 3 不代表「无此窗口」）。服务端没下发窗口时就是没有这两个字段，自然落到 null。
     */
    private fun windowBar(
        m: JsonObject,
        title: String,
        percentKey: String,
        totalKey: String,
        usageKey: String,
        endKey: String,
        remainsMsKey: String
    ): Bar? {
        // 优先服务端预算好的剩余百分比；老形态才回退到 count 换算（usage_count 实为「剩余次数」）
        val pct = jnum(m, percentKey)?.toInt()?.coerceIn(0, 100) ?: run {
            val total = jnum(m, totalKey)?.toInt() ?: return null
            val remain = jnum(m, usageKey)?.toInt() ?: return null
            if (total <= 0) return null
            (remain * 100.0 / total).toInt().coerceIn(0, 100)
        }

        // 次数只在 count 形态下有值，Token Plan 形态恒为 0，不占位
        val total = jnum(m, totalKey)?.toInt() ?: 0
        val remain = jnum(m, usageKey)?.toInt()
        val counts = if (total > 0 && remain != null) "剩 $remain/$total 次" else null

        val endMs = jnum(m, endKey)?.toLong()
        val remainsMs = jnum(m, remainsMsKey)?.toLong()
        val at = if (endMs != null && endMs > 0)
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endMs)) + " 重置"
        else null
        val reset = when {
            at != null && remainsMs != null && remainsMs > 0 -> "$at · ${formatCountdown(remainsMs)}后"
            at != null -> at
            remainsMs != null && remainsMs > 0 -> "重置：${formatCountdown(remainsMs)}后"
            else -> null
        }

        return Bar(
            label = title,
            percentRemaining = displayRemaining(pct),
            usedPercent = 100 - displayRemaining(pct),
            counts = counts,
            reset = reset,
        )
    }

    /**
     * 展示口径：把服务端整数对齐到**网页控制台**的显示。
     *
     * 服务端下发的 `*_remaining_percent` 是整数，几乎可以确定是对真实小数的**向下截断**
     * （实测拿到 99 时，同一把密钥在 platform.minimaxi.com 的 TokenPlan 面板显示满格）。
     * 控制台按最近整数取整，所以真实已用不足 0.5% 时它显示「已用 0% / 剩余 100%」。
     *
     * 整数没法反推小数，这里采用同一套显示约定：**已用 ≤1% 一律按 0% 展示**。
     * 消耗明显的档位不受影响——周窗口 95% 仍然是 95% / 已用 5%，不会跟着漂。
     * 纯展示口径，不参与任何配额判断。
     */
    private fun displayRemaining(apiPercent: Int): Int =
        if (100 - apiPercent <= 1) 100 else apiPercent
}

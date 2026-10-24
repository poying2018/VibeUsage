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

// ─── 智谱 GLM Coding Plan ───

object Glm {
    private val URLS = listOf(
        // 国内站（大陆直连稳定）；国际站 z.ai 大陆直连常超时 → 允许 Worker 中转兜底
        "https://open.bigmodel.cn/api/monitor/usage/quota/limit",
        "https://api.z.ai/api/monitor/usage/quota/limit",
    )

    /**
     * data.limits[]：unit=3 → 小时窗口（number=5 即 5 小时），unit=6 → 周窗口；
     * percentage 为已用百分比。type 有 TOKENS_LIMIT（旧 token 套餐）、
     * CREDIT_LIMIT（新 credit 套餐，见 docs.z.ai/devpack，CodexBar 同源验证）
     * 与 TIME_LIMIT（MCP 用量，unit=5/number=1 为月度标记）。
     */
    fun fetchUsage(apiKey: String): Usage {
        // 与其它供应商一致走 withHostFallback：
        // 统一「鉴权错误优先」的报错语义 + 总耗时预算（原来 2 台 host × 35s 超时上界太长），
        // 且第二台 host 的直连/中转策略与第一台不同，需要按 index 区分。
        var businessErr: QuotaException? = null
        var lastErr: Exception? = null
        for ((index, url) in URLS.withIndex()) {
            try {
                val resp = QuotaHttp.get(
                    url, bearer = apiKey,
                    proxyFirst = index > 0, directOnly = index == 0, ua = "VibeUsage/2.11"
                )
                return parseUsage(resp).copy(
                    models = fetchModelIds(
                        "https://open.bigmodel.cn/api/paas/v4/models", apiKey,
                        proxyFirst = false, directOnly = true, ua = "VibeUsage/2.11"
                    )
                )
            } catch (e: QuotaException) {
                // 鉴权类错误（key 无效）换 host 也救不回来，但比「最后撞上的那个错」更有诊断价值
                if (businessErr == null || e.code == 2049 || e.code == 1004 || e.code == 401) {
                    businessErr = e
                }
                lastErr = e
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw businessErr ?: lastErr ?: QuotaException(0, "智谱查询失败")
    }

    /** 解析 quota/limit 响应（拆出以便单测覆盖新旧套餐结构）。 */
    internal fun parseUsage(resp: String): Usage {
        val obj = gson.fromJson(resp, JsonObject::class.java)
            ?: throw QuotaException(0, "响应为空")
        val code = jnum(obj, "code")?.toInt()
        if (code != null && code != 200) {
            throw QuotaException(code, jstr(obj, "msg") ?: "智谱错误 $code")
        }
        val limits = obj.optObject("data")?.optArray("limits")
            ?: throw QuotaException(0, "响应缺少 limits 数据")
        if (limits.size() == 0) {
            throw QuotaException(0, "limits 为空（确认 Key 已开通 Coding Plan）")
        }
        val groups = linkedMapOf<String, MutableList<Bar>>()
        for (el in limits) {
            val o = el as? JsonObject ?: continue
            val type = jstr(o, "type")
            val barLabel = when (type) {
                "TOKENS_LIMIT" -> "Token 额度"
                "CREDIT_LIMIT" -> "Credit 额度"
                "TIME_LIMIT" -> "MCP 额度"
                else -> continue
            }
            // unit 换算表：3=小时 6=周 1=天 5=分钟（与 CodexBar zai 插件一致）
            val unit = jnum(o, "unit")?.toInt()
            val number = jnum(o, "number")?.toInt() ?: 1
            val group = when {
                unit == 3 -> if (number == 5) "5 小时窗口" else "$number 小时窗口"
                unit == 6 -> if (number == 1) "每周窗口" else "$number 周窗口"
                unit == 1 -> if (number == 1) "每日窗口" else "$number 天窗口"
                type == "TIME_LIMIT" && unit == 5 && number == 1 -> "MCP 月度"
                else -> null
            } ?: continue
            val total = jnum(o, "usage")
            val used = jnum(o, "currentValue")
            val pctUsed = (jnum(o, "percentage")
                ?: (if (total != null && total > 0 && used != null) used * 100.0 / total else null))
                ?: continue
            // 秒/毫秒归一：服务端可能下发秒级时间戳（约 1.7e9），直接当毫秒会落到 1970 年
            val resetMs = jnum(o, "nextResetTime")?.toLong()?.let { normalizeEpochMs(it) }
            groups.getOrPut(group) { mutableListOf() } += Bar(
                label = barLabel,
                percentRemaining = (100 - pctUsed).toInt().coerceIn(0, 100),
                usedPercent = pctUsed.toInt().coerceIn(0, 100),
                counts = if (total != null && used != null) "已用 ${compact(used)}/${compact(total)}" else null,
                reset = resetMs?.takeIf { it > 0 }?.let { epochReset(it) }
            )
        }
        if (groups.isEmpty()) {
            throw QuotaException(0, "未识别的额度窗口类型（套餐结构较新，请反馈更新）")
        }
        return Usage(null, groups)
    }
}

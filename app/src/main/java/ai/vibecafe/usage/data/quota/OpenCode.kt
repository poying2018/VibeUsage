package ai.vibecafe.usage.data.quota

import com.google.gson.JsonObject
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Bar
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Usage
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.fetchModelIds
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.gson
// jnum / jstr / optObject 都是同包顶层函数（ExtraQuotaApi.kt:191+），直接用、无需 import

// ─── OpenCode Go（opencode.ai 订阅套餐；旧称 opencode zen，2026 起改名 Console + Go）───
//
// 端点来自官方 OpenAPI 契约 https://opencode.ai/inference/openapi.json（实测存在：
// 无 Key GET 返回 401 而不是 404）：
//   GET /inference/go/v1/usage   唯一能读额度的端点
//   GET /inference/go/v1/models  OpenAI 风格 data[].id；带 Key 只返回该 workspace 可用模型
//
// 响应（契约里每个对象都是 additionalProperties:false，结构是完整的）：
//   {"usage":{"rolling":{status,percent,resetsAt},"weekly":{…},"monthly":{…}}}
//   · percent 是**已用** 0~100（Console 侧同名 Meter 与 limited 布尔并列，判定为消耗口径），
//     画条时取 100-percent；若真实账号刚买完套餐就显示 100% 剩余为 0，则口径反了，改一行即可。
//   · rolling≈5 小时（月限额 20%）、weekly=50%、monthly=100%，按月额度的固定比例，
//     只有百分比没有美元数，counts 留空。
//   · resetsAt 是 ISO8601 字符串（不是毫秒），走 formatReset 而非 epochReset。
//   · status=="rate-limited" 表示该窗口已触发限流，写进 counts 里，
//     免得只剩一条 0% 的条看不出原因。
//   · 没有 x-ratelimit-* 响应头（官方 spec 搜不到，实测只有 x-opencode-log-id），
//     所以不做 header 解析。
//
// 路由=中转优先：opencode.ai 是 Cloudflare 海外站，大陆直连不稳。
// worker-quota.js 的 ALLOWED_HOSTS 已加 opencode.ai；重新部署中转前，
// 中转第一跳会吃一个白名单 403，QuotaHttp 会按既有规则自动落到直连，不影响可用性。
object OpenCode {

    private const val BASE = "https://opencode.ai/inference"
    private const val UA = "VibeUsage/2.22"

    /** 服务端窗口字段名 → 展示组名（组名带「窗口」二字，总览页趋势会优先选它）。 */
    private val WINDOWS = listOf(
        "rolling" to "5 小时窗口",
        "weekly" to "每周窗口",
        "monthly" to "每月窗口"
    )

    fun fetchUsage(apiKey: String): Usage {
        val key = apiKey.trim().removePrefix("Bearer ").trim()
        if (key.isEmpty()) throw QuotaException(0, "请填写 OpenCode API Key")
        val resp = try {
            QuotaHttp.get("$BASE/go/v1/usage", bearer = key, proxyFirst = true, ua = UA)
        } catch (e: QuotaException) {
            if (e.code == 401) throw QuotaException(401, "OpenCode API Key 无效或已过期，请在 Console 重新创建（权限需含 Usage: Read）")
            throw e
        }
        val obj = gson.fromJson(resp, JsonObject::class.java)
            ?: throw QuotaException(0, "响应为空")
        val groups = buildGroups(obj)
        if (groups.isEmpty()) {
            // 未订 Go 套餐 / Key 权限不含 Usage 时，三档窗口都会缺数据 ——
            // 说清楚是「没有套餐数据」而不是含糊的「查询失败」
            throw QuotaException(0, "未返回 Go 额度数据（需已订阅 OpenCode Go / Go Plus / Go Teams，Key 权限勾选 Usage）")
        }
        return Usage(
            null, groups,
            fetchModelIds("$BASE/go/v1/models", key, proxyFirst = true, ua = UA)
        )
    }

    /**
     * usage.{rolling,weekly,monthly} → 额度分组（纯函数，便于单测）。
     *
     * percent 缺字段就整档跳过：缺省成 0 会被画成「已用 0%、满额度」，
     * 比不画更误导。
     */
    internal fun buildGroups(obj: JsonObject): Map<String, List<Bar>> {
        val usage = obj.optObject("usage") ?: return emptyMap()
        val groups = linkedMapOf<String, MutableList<Bar>>()
        for ((key, groupName) in WINDOWS) {
            val w = usage.optObject(key) ?: continue
            val used = jnum(w, "percent") ?: continue
            val usedPct = used.toInt().coerceIn(0, 100)
            val reset = jstr(w, "resetsAt")?.let { formatReset(it) }
            groups[groupName] = mutableListOf(
                Bar(
                    label = "Token 额度",
                    percentRemaining = 100 - usedPct,
                    usedPercent = usedPct,
                    counts = jstr(w, "status")?.takeIf { it == "rate-limited" }?.let { "已限流" },
                    reset = reset
                )
            )
        }
        return groups
    }
}

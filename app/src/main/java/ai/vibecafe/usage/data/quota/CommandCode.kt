package ai.vibecafe.usage.data.quota

import com.google.gson.JsonObject
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Bar
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Usage
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.gson

// ─── CommandCode（Command Code CLI，AI 编程工作站）───

/**
 * CommandCode（commandcode.ai）的额度与用量查询。
 *
 * 凭据是 CLI 的 API Key（`user_...` 开头），存在本机 `~/.commandcode/auth.json`，
 * 也可在 commandcode.ai/studio 复制。
 *
 * 端点（Bearer <apiKey>，均返回 JSON）：
 * - `GET /alpha/whoami?limits=1`        → 账号身份 + 自己的 spend caps（成员也能调）
 * - `GET /alpha/billing/credits`        → credits（套餐额度）+ windowLimits（5 小时 / 每周窗口）
 * - `GET /alpha/billing/subscriptions`  → 订阅（planId、当前计费周期）
 * - `GET /alpha/usage/summary`          → 本计费周期的用量汇总（tokens / 成本 / credits）
 *
 * 权限差异（官方 harness 的注释明确写了）：
 * whoami 是这组接口里**唯一**普通 org 成员能调的，credits / subscriptions / usage-summary
 * 都需要 org owner/admin，普通成员会 403。所以这三个端点失败时不能当致命错误——
 * 只影响窗口额度与用量明细，不该让整个查询崩掉。
 */
object CommandCode {
    private const val BASE = "https://api.commandcode.ai"
    private const val UA = "command-code-desktop"

    private const val WHOAMI = "/alpha/whoami"
    private const val CREDITS = "/alpha/billing/credits"
    private const val SUBSCRIPTION = "/alpha/billing/subscriptions"
    private const val USAGE_SUMMARY = "/alpha/usage/summary"

    /** 窗口上限的展示上限：服务端可能下发很大的 cap，用它兜住百分比计算。 */
    private fun percentRemaining(used: Double, cap: Double): Int {
        if (cap <= 0.0) return 0
        val pct = ((cap - used) / cap * 100.0).coerceIn(0.0, 100.0)
        return pct.toInt()
    }

    /**
     * 查询额度与用量。
     *
     * [apiKey] 是 CommandCode CLI 的 API Key（`user_...`）。
     */
    fun fetchUsage(apiKey: String): Usage {
        val key = apiKey.trim()
        if (key.isEmpty()) throw QuotaException(0, "请填写 CommandCode API Key")

        // whoami：账号身份 + 自身 spend caps。成员也能调，作为整组接口的「探针」：
        // 它 401 就说明 Key 无效，后面几个端点不用再试。
        val whoami = try {
            getJson("$WHOAMI?limits=1", key)
        } catch (e: QuotaException) {
            if (e.code == 401 || e.code == 403) {
                throw QuotaException(401, "CommandCode API Key 无效或已过期，请重新获取")
            }
            throw e
        }

        val account = jstr(whoami.optObject("user"), "userName")
            ?: jstr(whoami.optObject("user"), "email")
            ?: jstr(whoami.optObject("org"), "login")

        // 以下三个是 owner/admin 门禁的，失败只影响对应分项，不让整体查询失败
        val credits = tryGet { getJson(CREDITS, key) }
        val subscription = tryGet { getJson(SUBSCRIPTION, key) }
        val summary = tryGet { getJson(USAGE_SUMMARY, key) }

        val groups = buildGroups(credits, summary)
        if (groups.isEmpty()) {
            // 全部门禁接口都拿不到（普通成员）——whoami 成功说明 Key 有效，
            // 只是这个账号没有可展示的额度数据
            throw QuotaException(0, "该账号未返回额度数据（组织成员账号的额度接口需要管理员权限）")
        }

        // 账号名带上套餐标识（订阅接口也是 owner/admin 门禁，拿不到就只用用户名）
        val planId = subscription?.optObject("data")?.let { jstr(it, "planId") }
        val displayAccount = if (planId != null && account != null) "$account · $planId" else account

        // 服务端没有「账号可用模型」接口（/alpha/* 下只有 generate/billing/usage 等），
        // 模型目录由 CLI 本地缓存管理，所以这里不展示模型区（与其它拿不到列表的供应商一致）。
        return Usage(displayAccount, groups)
    }

    /**
     * 把 credits / usage-summary 响应组装成额度分组（拆出以便单测覆盖解析口径）。
     *
     * [credits] 为 `/alpha/billing/credits` 响应（可能为 null：需要管理员权限）。
     * [summary] 为 `/alpha/usage/summary` 响应（可能为 null）。
     */
    internal fun buildGroups(
        credits: JsonObject?,
        summary: JsonObject?
    ): Map<String, List<Bar>> {
        val groups = linkedMapOf<String, MutableList<Bar>>()

        // ---- 窗口额度：5 小时 + 每周 ----
        val windowLimits = credits?.optObject("windowLimits")
        val bars = mutableListOf<Bar>()
        windowLimits?.optObject("fiveHour")?.let { w ->
            val cap = jnum(w, "cap") ?: 0.0
            val used = jnum(w, "used") ?: 0.0
            if (cap > 0.0) {
                val remaining = percentRemaining(used, cap)
                bars += Bar(
                    label = "5 小时窗口",
                    percentRemaining = remaining,
                    usedPercent = 100 - remaining,
                    counts = "${ExtraQuotaApi.compact(used)}/${ExtraQuotaApi.compact(cap)}",
                    reset = resetTextOf(w)
                )
            }
        }
        windowLimits?.optObject("weekly")?.let { w ->
            val cap = jnum(w, "cap") ?: 0.0
            val used = jnum(w, "used") ?: 0.0
            if (cap > 0.0) {
                val remaining = percentRemaining(used, cap)
                bars += Bar(
                    label = "每周窗口",
                    percentRemaining = remaining,
                    usedPercent = 100 - remaining,
                    counts = "${ExtraQuotaApi.compact(used)}/${ExtraQuotaApi.compact(cap)}",
                    reset = resetTextOf(w)
                )
            }
        }
        if (bars.isNotEmpty()) groups["额度窗口"] = bars

        // ---- 套餐额度：月度 credits ----
        credits?.optObject("credits")?.let { c ->
            val monthly = jnum(c, "monthlyCredits") ?: 0.0
            val purchased = jnum(c, "purchasedCredits") ?: 0.0
            val free = jnum(c, "freeCredits") ?: 0.0
            val total = monthly + purchased + free
            if (total > 0.0) {
                val used = summary?.let { jnum(it, "totalCredits") } ?: 0.0
                val remaining = percentRemaining(used, total)
                groups["套餐额度"] = mutableListOf(
                    Bar(
                        label = "Credits",
                        percentRemaining = remaining,
                        usedPercent = 100 - remaining,
                        counts = "${ExtraQuotaApi.compact(used)}/${ExtraQuotaApi.compact(total)}"
                    )
                )
            }
        }

        // ---- 本计费周期的用量汇总 ----
        summary?.let { s ->
            val tokens = jnum(s, "totalTokens") ?: 0.0
            val cost = jnum(s, "totalCost") ?: 0.0
            if (tokens > 0.0 || cost > 0.0) {
                groups["本周期用量"] = mutableListOf(
                    Bar(
                        label = "Tokens",
                        percentRemaining = 100,
                        usedPercent = 0,
                        counts = "${ExtraQuotaApi.compact(tokens)} tokens"
                    )
                )
            }
        }

        return groups
    }

    /**
     * GET 一个 JSON 端点。
     *
     * 走「直连优先、中转兜底」（proxyFirst=false）：api.commandcode.ai 在国内直连不稳，
     * 中转线路（worker-quota.js 白名单已含该域名）作为第二条路。
     * 不设 directOnly——中转要能兜底。
     */
    private fun getJson(endpoint: String, apiKey: String): JsonObject {
        val resp = QuotaHttp.get(
            "$BASE$endpoint",
            bearer = apiKey, proxyFirst = false, ua = UA
        )
        return gson.fromJson(resp, JsonObject::class.java)
            ?: throw QuotaException(0, "CommandCode 返回了空响应")
    }

    /**
     * 窗口重置时间文案。
     *
     * 服务端给的是 `resetAt`（毫秒时间戳，0 表示未知）。用 [ExtraQuotaApi.epochReset]
     * 统一格式（北京时间 + 倒计时），与其它供应商的窗口展示一致。
     */
    private fun resetTextOf(window: JsonObject): String? {
        val resetAt = jnum(window, "resetAt")?.toLong() ?: return null
        if (resetAt <= 0L) return null
        return try {
            ExtraQuotaApi.epochReset(resetAt)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 门禁接口的容错取值。
     *
     * credits / subscriptions / usage-summary 需要 org owner/admin，
     * 普通成员调用会 **403** —— 那是「这个分项没有数据」而不是「查询失败」，返回 null 继续。
     * 其它错误（断网 / 5xx / Key 无效）照常抛出：全吞掉的话，
     * 断网也会显示成「账号未返回额度数据」，把网络问题说成权限问题。
     */
    private inline fun tryGet(block: () -> JsonObject): JsonObject? = try {
        block()
    } catch (e: QuotaException) {
        if (e.code == 403) null else throw e
    }
}

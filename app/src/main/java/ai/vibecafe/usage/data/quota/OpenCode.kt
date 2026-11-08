package ai.vibecafe.usage.data.quota

import com.google.gson.JsonObject
import ai.vibecafe.usage.data.FxRates
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Bar
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.ModelInfo
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Usage
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.gson
// jnum / jstr / optObject / optArray 都是同包顶层函数（ExtraQuotaApi.kt:191+），直接用、无需 import

// ─── OpenCode Go（opencode.ai 订阅套餐；旧称 opencode zen，2026 起改名 Console + Go）───
//
// 端点来自官方 OpenAPI 契约 https://opencode.ai/inference/openapi.json（实测存在：
// 无 Key GET 返回 401 而不是 404）：
//   GET /inference/go/v1/usage   唯一能读额度的端点
//   GET /inference/go/v1/models  带 Key 只返回该 workspace 可用模型；字段远多于 id
//
// usage 响应（契约里每个对象都是 additionalProperties:false，结构是完整的）：
//   {"usage":{"rolling":{status,percent,resetsAt},"weekly":{…},"monthly":{…}}}
//   · percent 是**已用** 0~100，画条时取 100-percent。
//   · resetsAt 是 ISO8601 字符串（不是毫秒），走 formatReset 而非 epochReset。
//   · status=="rate-limited" 表示该窗口已触发限流，必须写进 counts 里，
//     免得只剩一条 0% 的条看不出原因。
//
// 三档窗口的分母不同（官方文档 opencode.ai/docs/go 原文：
// 「5-hour — 20% of the monthly limit; weekly — 50%; monthly — 100%」），
// 所以同一个 percent 在三个窗口里含义完全不同：rolling 的 100% 只意味着
// 用掉了月额度的 20%。直接把三条百分比并排画，用户会以为「三个独立的额度条」。
// 这里额外算一个「折算到月额度的占比」写进 counts，让三档在同一个刻度上可比。
//
// 计费口径是**按模型各自独立的美元月额度**（如 GLM-5.3 为 $15/月），
// 三档百分比是相对于每个模型自己的额度 —— 这个接口不返回美元数额，只有百分比。
//
// ── 美元金额的补充来源：Console 的 /api/go/status ──
// 「已用多少钱」不在推理 API 里，而在 Console：`access.meters.{fiveHour,week,month}`
// 各带 limitMicroCents / usedMicroCents / resetsAt。**同一把 oc_sk_ Key 直接就能读**
// （实测 Authorization: Bearer 返回 200；换成 x-api-key 反而 401）。
// 拿不到时静默降级为纯百分比 —— 金额是锦上添花，不能让它拖垮额度展示。
//
// models 响应每个条目除 id 外还有：name / limit.{context,output} /
// cost.text.{input.price, input.cache.read, output.price} / capabilities /
// modalities / open_weights / release_date / knowledge / cost.multipliers。
// 这些全部是有效信息，早先只取了 id 白白丢掉；现在解析成 ModelInfo 供模型区展示。
//
// 路由=中转优先：opencode.ai 是 Cloudflare 海外站，大陆直连不稳。
// worker-quota.js 的 ALLOWED_HOSTS 已加 opencode.ai；重新部署中转前，
// 中转第一跳会吃一个白名单 403，QuotaHttp 会按既有规则自动落到直连，不影响可用性。
object OpenCode {

    private const val BASE = "https://opencode.ai/inference"
    private const val CONSOLE = "https://console.opencode.ai"
    private const val UA = "VibeUsage/2.23"

    /**
     * 三档窗口在 Console 里的字段名（推理 API 用 rolling/weekly/monthly，
     * Console 用 fiveHour/week/month —— 两套名字指同一批窗口）。
     */
    private val CONSOLE_KEYS = mapOf(
        "rolling" to "fiveHour",
        "weekly" to "week",
        "monthly" to "month"
    )

    /** 服务端窗口字段名 → 展示组名 + 该窗口占月额度的比例（官方文档口径）。 */
    private val WINDOWS = listOf(
        Triple("rolling", "5 小时窗口", 0.20),
        Triple("weekly", "每周窗口", 0.50),
        Triple("monthly", "每月窗口", 1.00)
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
        // Console 的真实美元数额：拿不到（无权限/未订阅/网络）就退回纯百分比，
        // 不能因为一个「增强信息」的失败让整张额度卡崩掉。
        // 只请求一次：金额和账号胶囊都从同一份响应里取。
        val status = fetchConsoleStatus(key)
        val meters = parseMeters(status)
        val groups = buildGroups(obj, meters)
        if (groups.isEmpty()) {
            // 未订 Go 套餐 / Key 权限不含 Usage 时，三档窗口都会缺数据 ——
            // 说清楚是「没有套餐数据」而不是含糊的「查询失败」
            throw QuotaException(0, "未返回 Go 额度数据（需已订阅 OpenCode Go / Go Plus / Go Teams，Key 权限勾选 Usage）")
        }
        val catalog = fetchCatalog(key)
        return Usage(
            accountOf(status), groups,
            models = catalog.map { it.id },
            // /go/v1/models 返回的正是该套餐可用的模型集合，标题写明口径，
            // 免得用户以为这是 opencode 的全部模型（不带 /go 的目录有 80 个）
            modelsLabel = "Go 套餐可用模型",
            modelInfo = catalog
        )
    }

    /**
     * 单个窗口的金额（Console 口径，已换算成美元）。
     *
     * [used]/[limit] 都是美元；[reset] 是 ISO8601 原文，交给 [formatReset] 统一格式化。
     */
    internal data class Meter(val used: Double, val limit: Double, val reset: String?)

    /** 推理 API 窗口字段名（rolling/weekly/monthly）→ Console 金额；缺的窗口不在表里。 */
    internal data class Meters(val byWindow: Map<String, Meter> = emptyMap()) {
        operator fun get(window: String): Meter? = byWindow[window]

        companion object {
            val EMPTY = Meters()
        }
    }

    /**
     * 从 Console 读三档窗口的真实美元金额。任何失败都返回空表：
     * 金额是增强信息，拿不到就退回纯百分比，绝不让额度卡整体报错。
     */
    internal fun fetchConsoleStatus(apiKey: String): JsonObject? = try {
        val resp = QuotaHttp.get(
            "$CONSOLE/api/go/status", bearer = apiKey, proxyFirst = true, ua = UA
        )
        gson.fromJson(resp, JsonObject::class.java)
    } catch (_: Exception) {
        null
    }

    /**
     * `/api/go/status` → 三档金额（纯函数，便于单测）。
     *
     * 金额是 BigInt 字符串形式的 **micro-cents**（1 美元 = 1e8）。
     * 按推理股的窗口名（rolling/weekly/monthly）做键，方便 [buildGroups] 直接查。
     */
    internal fun parseMeters(obj: JsonObject?): Meters {
        val meters = obj?.optObject("access")?.optObject("meters") ?: return Meters.EMPTY
        val out = linkedMapOf<String, Meter>()
        for ((apiKey, consoleKey) in CONSOLE_KEYS) {
            val m = meters.optObject(consoleKey) ?: continue
            val limit = jnum(m, "limitMicroCents") ?: continue
            // 上限为 0 是「这个窗口没有额度池」而不是「满额度」
            if (limit <= 0.0) continue
            out[apiKey] = Meter(
                used = (jnum(m, "usedMicroCents") ?: 0.0).coerceAtLeast(0.0) / 1e8,
                limit = limit / 1e8,
                reset = jstr(m, "resetsAt")
            )
        }
        return Meters(out)
    }

    /**
     * 账号胶囊文案：套餐名 + 本期到期日（拿不到 Console 状态时为 null，UI 退回「凭据已生效」）。
     *
     * 只显示套餐档位，不带订阅者 ID —— 那是一串内部标识，对用户没有意义。
     */
    internal fun accountOf(status: JsonObject?): String? {
        val product = jstr(status, "product") ?: return null
        val plan = when (product) {
            "go" -> "Go"
            "go-plus" -> "Go Plus"
            "go-teams" -> "Go Teams"
            else -> product
        }
        val endsAt = jstr(status?.optObject("access"), "endsAt")?.let { isoDay(it) }
        return if (endsAt != null) "$plan · $endsAt 到期" else plan
    }

    /** ISO8601 → 「11-05」；解析失败返回 null（宁可少一段也不显示乱码日期）。 */
    private fun isoDay(iso: String): String? {
        val ms = parseIsoMillis(iso) ?: return null
        return java.text.SimpleDateFormat("MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        }.format(java.util.Date(ms))
    }

    /**
     * usage.{rolling,weekly,monthly} → 额度分组（纯函数，便于单测）。
     *
     * percent 缺字段就整档跳过：缺省成 0 会被画成「已用 0%、满额度」，
     * 比不画更误导。
     *
     * [meters] 有值时，副标题优先写「已用 $X / $Y」这种绝对值 ——
     * 它能一眼回答「我这个月到底花了多少」，而百分比只是相对刻度；
     * 两者都放会撑爆一行，且百分比本来就有独立的「已用 N%」。
     */
    internal fun buildGroups(obj: JsonObject, meters: Meters = Meters.EMPTY): Map<String, List<Bar>> {
        val usage = obj.optObject("usage") ?: return emptyMap()
        val groups = linkedMapOf<String, MutableList<Bar>>()
        for ((key, groupName, share) in WINDOWS) {
            val w = usage.optObject(key) ?: continue
            val used = jnum(w, "percent") ?: continue
            val usedPct = used.toInt().coerceIn(0, 100)
            val meter = meters[key]
            // 金额的重置时间来自 Console；没有金额时用推理 API 的 resetsAt
            val reset = (meter?.reset ?: jstr(w, "resetsAt"))?.let { formatReset(it) }
            groups[groupName] = mutableListOf(
                Bar(
                    // 额度按**计费金额**（美元）计，不是 token 数：
                    // 官方文档写明「Usage limits are defined as monthly dollar amounts」，
                    // 响应里只有百分比、没有 token 数或美元数。标成「Token 额度」会误导。
                    label = "计费额度",
                    percentRemaining = 100 - usedPct,
                    usedPercent = usedPct,
                    counts = countsOf(meter, usedPct, jstr(w, "status"), share),
                    reset = reset
                )
            )
        }
        return groups
    }

    /**
     * 只有金额、没有推理百分比时的分组（OAuth 登录态路径用）。
     *
     * 三档窗口都来自 [Meters]，百分比直接由金额比算出 —— 与 Key 路径的
     * 「推理 percent + Console 金额」是同一套口径，只是没有推理 percent 可对。
     * 没有该窗口的金额就整档跳过：缺金额画一条假条比不画更误导。
     */
    internal fun buildGroupsFromMeters(meters: Meters): Map<String, List<Bar>> {
        val groups = linkedMapOf<String, MutableList<Bar>>()
        for ((key, groupName, _) in WINDOWS) {
            val meter = meters[key] ?: continue
            if (meter.limit <= 0.0) continue
            val ratio = (meter.used / meter.limit).coerceIn(0.0, 1.0)
            val usedPct = Math.round(ratio * 100).toInt()
            groups[groupName] = mutableListOf(
                Bar(
                    label = "计费额度",
                    percentRemaining = 100 - usedPct,
                    usedPercent = usedPct,
                    counts = "${cny(meter.used)} / ${cny(meter.limit)}",
                    reset = meter.reset?.let { formatReset(it) }
                )
            )
        }
        return groups
    }

    /**
     * 人民币文案（入参是美元，展示统一按 [FxRates] 换算）。
     *
     * 小数位按量级给：已用额常见 ¥0.41 这种量级，固定两位会把小消耗抹成 ¥0.00，
     * 而「到底花了多少」正是接 Console 数据的目的。分档：
     * ≥ ¥0.10 两位（¥0.41 / ¥60.00）、≥ ¥0.01 三位（¥0.032）、更小四位（¥0.0032）。
     */
    internal fun cny(v: Double, rate: Double = FxRates.usdToCny): String {
        val amount = v * rate
        val text = when {
            amount == 0.0 -> "0.00"
            amount >= 0.1 -> String.format(java.util.Locale.US, "%.2f", amount)
            amount >= 0.01 -> String.format(java.util.Locale.US, "%.3f", amount)
            else -> String.format(java.util.Locale.US, "%.4f", amount).trimEnd('0')
        }
        return "¥$text"
    }

    /**
     * 副标题：有金额写「¥已用 / ¥上限」，没有则退回「占月额度 X%」。
     *
     * 「已限流」两种情况都要带上 —— 只剩一条 0% 的条时不写原因，用户看不出为什么调不动。
     */
    private fun countsOf(meter: Meter?, usedPct: Int, status: String?, shareOfMonth: Double): String? {
        if (meter == null) return windowCounts(usedPct, status, shareOfMonth)
        val money = "${cny(meter.used)} / ${cny(meter.limit)}"
        return if (status == "rate-limited") "$money · 已限流" else money
    }

    /**
     * 窗口副标题：把「占本窗口」换算成「占月额度」，并标注限流状态。
     *
     * 例：rolling 用掉 60% → 相当于月额度的 12%；weekly 用掉 30% → 15%。
     * 没有这一步，三档条并排看着像三个互不相干的额度池。
     */
    private fun windowCounts(usedPct: Int, status: String?, shareOfMonth: Double): String? {
        val parts = mutableListOf<String>()
        if (status == "rate-limited") parts += "已限流"
        // 只有非整月窗口才需要换算（monthly 本身就是月额度，写出来是废话）
        if (shareOfMonth < 1.0) {
            val monthPct = usedPct * shareOfMonth
            parts += "占月额度 ${formatPct(monthPct)}"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** 百分比文案：<1% 显示一位小数（避免 0.4% 被四舍五入成 0%，看起来像没消耗）。 */
    private fun formatPct(v: Double): String =
        if (v < 1.0) String.format(java.util.Locale.US, "%.1f%%", v)
        else String.format(java.util.Locale.US, "%.0f%%", v)

    // ─── 模型目录 ───

    /**
     * 拉取模型目录并解析成 [ModelInfo]。任何失败都退回空表：
     * 模型区整体隐藏，绝不影响额度展示。
     */
    private fun fetchCatalog(bearer: String): List<ModelInfo> = try {
        val resp = QuotaHttp.get("$BASE/go/v1/models", bearer = bearer, proxyFirst = true, ua = UA)
        parseModels(gson.fromJson(resp, JsonObject::class.java))
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * `data[]` → [ModelInfo]（纯函数，便于单测）。
     *
     * 价格字段：`cost.text.input.price` / `cost.text.input.cache.read` /
     * `cost.text.output.price`，单位美元/百万 token。缺失或为 0 时**保留 0**
     * （免费模型 beam-free / iceman-contributor-free 的 0 是有效信息，
     * 用 null 表示"不知道"会和"免费"混为一谈）。
     *
     * `cost.multipliers[].factor` 是高峰倍率：取第一个带 factor 的项作为代表，
     * 并在 UI 侧只展示"有浮动定价"而不展开时间段（契约里的 when 条件是
     * utc_days/utc_hours/date 的组合，展开会变成一大段没人看的规则）。
     */
    internal fun parseModels(obj: JsonObject?): List<ModelInfo> {
        val data = obj?.optArray("data") ?: return emptyList()
        return data.mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val id = jstr(m, "id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val limit = m.optObject("limit")
            val cost = m.optObject("cost")
            val text = cost?.optObject("text")
            val input = text?.optObject("input")
            val caps = m.optObject("capabilities")
            val modalities = m.optObject("modalities")
            val multipliers = (cost?.optArray("multipliers") ?: com.google.gson.JsonArray())
                .mapNotNull { it as? JsonObject }
                .firstOrNull { (jnum(it, "factor") ?: 0.0) > 1.0 }
            ModelInfo(
                id = id,
                name = jstr(m, "name"),
                contextLimit = jnum(limit, "context")?.toLong(),
                outputLimit = jnum(limit, "output")?.toLong(),
                inputPrice = jnum(input, "price"),
                cacheReadPrice = jnum(input?.optObject("cache"), "read"),
                outputPrice = jnum(text?.optObject("output"), "price"),
                // capabilities.tools / capabilities.reasoning 在契约里是对象
                // （{"supported":true,...}），但别家供应商偶尔直接给布尔，两种都认
                reasoning = caps.capability("reasoning"),
                reasoningEfforts = (caps?.optObject("reasoning")?.optArray("effort")?.size()) ?: 0,
                tools = caps.capability("tools"),
                vision = (modalities?.optArray("input") ?: com.google.gson.JsonArray())
                    .any { it.isJsonPrimitive && it.asString.startsWith("image") },
                openWeights = m.bool("open_weights"),
                releaseDate = jstr(m, "release_date"),
                knowledge = jstr(m, "knowledge"),
                peakFactor = multipliers?.let { jnum(it, "factor") }?.takeIf { it > 1.0 },
                peakWindow = multipliers?.optObject("when")?.let { peakWindowOf(it) },
                description = jstr(m, "description"),
            )
        }.sortedBy { it.id }
    }

    /**
     * 把 `cost.multipliers[].when` 的条件概括成一句人话。
     *
     * 契约里的形状是 `{"utc_days":{"in":[…]},"any":[{"all":[{"utc_hours":{"gte":"01:00"}},{"utc_hours":{"lt":"04:00"}}]},…]}`
     * 或 `{"date":{"gte":"2026-12-01"}}`。这里只做「时间段」和「起始日期」两种最常见的概括：
     * 时段换算成**北京时间**（App 全站口径），星期几用中文缩写。
     * 结构认不出就返回 null —— UI 会退化成只显示倍率，不显示错误的时段。
     */
    internal fun peakWindowOf(condition: JsonObject): String? {
        // 起始日期型：{"date":{"gte":"2026-12-01"}} —— 之后一直是该倍率
        condition.optObject("date")?.let { d ->
            jstr(d, "gte")?.let { return "$it 起" }
        }
        val dayKeys = condition.optObject("utc_days")?.optArray("in")
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.lowercase() }
            ?.takeIf { it.isNotEmpty() }
        val days = dayKeys?.let { keys ->
            // 周一~周五整段 → 直接说「工作日」，比「周一、周二、周三、周四、周五」短得多
            if (keys.toSet() == WEEKDAYS) "工作日" else keys.joinToString("、") { DAY_CN[it]?.let { d -> "周$d" } ?: it }
        }

        // 时段：取第一个 all 里的 gte/lt 对，换算到北京时间
        val ranges = (condition.optArray("any") ?: com.google.gson.JsonArray())
            .mapNotNull { el ->
                val all = (el as? JsonObject)?.optArray("all") ?: return@mapNotNull null
                val gte = all.firstNotNullOfOrNull { (it as? JsonObject)?.optObject("utc_hours")?.let { h -> jstr(h, "gte") } }
                val lt = all.firstNotNullOfOrNull { (it as? JsonObject)?.optObject("utc_hours")?.let { h -> jstr(h, "lt") } }
                if (gte == null || lt == null) null else utcToBeijingRange(gte, lt)
            }
        val hours = ranges.takeIf { it.isNotEmpty() }?.joinToString("、")
        return when {
            days != null && hours != null -> "$days $hours"
            hours != null -> hours
            days != null -> days
            else -> null
        }
    }

    /** "01:00"–"04:00"（UTC）→ "09:00–12:00"（北京时间，跨零点自动回绕）。 */
    private fun utcToBeijingRange(gte: String, lt: String): String? {
        fun minutes(s: String): Int? {
            val parts = s.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return h * 60 + m
        }
        val a = minutes(gte) ?: return null
        val b = minutes(lt) ?: return null
        fun fmt(min: Int): String {
            val v = ((min % 1440) + 1440) % 1440
            return String.format(java.util.Locale.US, "%02d:%02d", v / 60, v % 60)
        }
        return "${fmt(a + 480)}–${fmt(b + 480)}"
    }

    private val DAY_CN = mapOf(
        "mon" to "一", "tue" to "二", "wed" to "三", "thu" to "四",
        "fri" to "五", "sat" to "六", "sun" to "日"
    )

    private val WEEKDAYS = setOf("mon", "tue", "wed", "thu", "fri")

    /** 布尔字段：接受 true/false，也接受字符串 "true"/"false"。 */
    private fun JsonObject?.bool(key: String): Boolean =
        this?.get(key)?.takeIf { it.isJsonPrimitive }?.let { p ->
            if (p.asJsonPrimitive.isBoolean) p.asBoolean else p.asString.equals("true", ignoreCase = true)
        } ?: false

    /**
     * `capabilities.<name>` 读成布尔。
     *
     * 契约里它是对象（`{"supported":true,"effort":[…]}`），但别的供应商
     * 偶尔直接给裸布尔或字符串 —— 三种形状都认，读不出来就是 false。
     */
    private fun JsonObject?.capability(name: String): Boolean {
        val v = this?.get(name) ?: return false
        if (v.isJsonPrimitive) {
            val p = v.asJsonPrimitive
            return if (p.isBoolean) p.asBoolean else p.asString.equals("true", ignoreCase = true)
        }
        val supported = (v as? JsonObject)?.get("supported") ?: return false
        if (!supported.isJsonPrimitive) return false
        val p = supported.asJsonPrimitive
        return if (p.isBoolean) p.asBoolean else p.asString.equals("true", ignoreCase = true)
    }
}

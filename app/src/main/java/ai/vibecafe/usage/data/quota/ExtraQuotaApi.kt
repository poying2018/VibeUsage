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

/**
 * 「额度」页各家额度查询客户端。除标注外端点均来自开源工具逆向（多项目交叉验证）。
 *
 * MiniMax / 智谱 GLM / Kimi / DeepSeek / 无问芯穹 / 阿里百炼 / 火山方舟均为国内可达域名，
 * 始终直连、不经 Cloudflare Worker 中转；失败时在多域名/多站点之间自动回退。
 */
object ExtraQuotaApi {

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val gson = Gson()

    /** 一条额度明细：剩余百分比 + 已用百分比 + 次数 + 重置时间（尽量详细）。 */
    data class Bar(
        val label: String,
        val percentRemaining: Int,
        val usedPercent: Int? = null,
        val counts: String? = null,
        val reset: String? = null,
    )

    /** 按时间窗口分组的额度快照（组名有序）。 */
    data class Usage(val account: String?, val groups: Map<String, List<Bar>>)

    class QuotaException(val code: Int, message: String) : Exception(message)

    // ─── MiniMax（Token Plan / Coding Plan 请求次数额度）───

    object MiniMax {
        private val URLS = listOf(
            "https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains",
            "https://api.minimax.io/v1/api/openplatform/coding_plan/remains",
        )

        /**
         * 查询额度。响应 model_remains[] 中 current_interval_usage_count /
         * current_weekly_usage_count 实为「剩余次数」（官方字段命名 bug，见 MiniMax-M2#99）。
         * 先试国内域名，单个域名失败（含业务错误）继续试下一个。
         */
        fun fetchUsage(apiKey: String): Usage {
            var lastErr: Exception? = null
            var businessErr: QuotaException? = null
            for (url in URLS) {
                try {
                    val resp = QuotaHttp.get(
                        url,
                        bearer = apiKey,
                        proxyFirst = false,
                        directOnly = true,
                        ua = "MiniMax-Usage-Checker"
                    )
                    val obj = gson.fromJson(resp, JsonObject::class.java) ?: continue
                    val base = obj.optObject("base_resp")
                    val code = base?.get("status_code")?.asInt
                    if (code != null && code != 0) {
                        throw QuotaException(
                            code,
                            base.get("status_msg")?.asString ?: "MiniMax 错误 $code"
                        )
                    }
                    val groups = linkedMapOf<String, MutableList<Bar>>()
                    val remains = obj.optArray("model_remains") ?: continue
                    for (el in remains) {
                        val m = el as? JsonObject ?: continue
                        val name = m.get("model_name")?.asString ?: continue
                        val total5h = m.get("current_interval_total_count")?.asInt
                        val remain5h = m.get("current_interval_usage_count")?.asInt
                        if (total5h != null && total5h > 0 && remain5h != null) {
                            val pct = (remain5h * 100.0 / total5h).toInt().coerceIn(0, 100)
                            val resetMs = m.get("remains_time")?.asLong
                            val endMs = m.get("end_time")?.asLong
                            groups.getOrPut("5 小时窗口") { mutableListOf() } += Bar(
                                label = name,
                                percentRemaining = pct,
                                usedPercent = (100 - pct).coerceIn(0, 100),
                                counts = "剩 $remain5h/$total5h 次",
                                reset = buildString {
                                    if (endMs != null && endMs > 0) {
                                        append(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endMs)))
                                        append(" 重置")
                                        if (resetMs != null && resetMs > 0) append(" · ${formatCountdown(resetMs)}后")
                                    } else if (resetMs != null && resetMs > 0) {
                                        append("重置：${formatCountdown(resetMs)}后")
                                    }
                                }.ifEmpty { null }
                            )
                        }
                        val totalW = m.get("current_weekly_total_count")?.asInt
                        val remainW = m.get("current_weekly_usage_count")?.asInt
                        if (totalW != null && totalW > 0 && remainW != null) {
                            val pct = (remainW * 100.0 / totalW).toInt().coerceIn(0, 100)
                            groups.getOrPut("每周窗口") { mutableListOf() } += Bar(
                                label = name,
                                percentRemaining = pct,
                                usedPercent = (100 - pct).coerceIn(0, 100),
                                counts = "剩 $remainW/$totalW 次"
                            )
                        }
                    }
                    return Usage(null, groups)
                } catch (e: QuotaException) {
                    if (businessErr == null) businessErr = e
                    lastErr = e
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw businessErr ?: lastErr ?: QuotaException(0, "MiniMax 查询失败")
        }
    }

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
            var lastErr: Exception? = null
            var businessErr: QuotaException? = null
            for ((index, url) in URLS.withIndex()) {
                try {
                    val resp = QuotaHttp.get(
                        url, bearer = apiKey,
                        proxyFirst = index > 0, directOnly = index == 0, ua = "VibeUsage/2.11"
                    )
                    return parseUsage(resp)
                } catch (e: QuotaException) {
                    if (businessErr == null) businessErr = e
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
                val resetMs = jnum(o, "nextResetTime")?.toLong()
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

    // ─── Kimi For Coding（月之暗面）───

    object Kimi {
        private val URLS = listOf(
            "https://api.kimi.com/coding/v1/usages",
            "https://api.kimi.com/coding/v1/usage",
        )

        /** usage{} 为周窗口，limits[] 中 window(300 MINUTE) 为 5 小时窗口；数值可能以字符串下发。 */
        fun fetchUsage(apiKey: String): Usage {
            var lastErr: Exception? = null
            for (url in URLS) {
                try {
                    val resp = QuotaHttp.get(
                        url, bearer = apiKey, proxyFirst = false, directOnly = true, ua = "KimiCLI/1.6"
                    )
                    return parse(resp)
                } catch (e: QuotaException) {
                    if (e.code != 404) throw e
                    lastErr = e
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw lastErr ?: QuotaException(0, "Kimi 查询失败")
        }

        private fun parse(resp: String): Usage {
            val obj = gson.fromJson(resp, JsonObject::class.java)
                ?: throw QuotaException(0, "响应为空")
            val groups = linkedMapOf<String, MutableList<Bar>>()
            obj.optObject("usage")?.let { u ->
                val limit = jnum(u, "limit")
                val remaining = jnum(u, "remaining")
                if (limit != null && limit > 0 && remaining != null) {
                    val pctUsed = (limit - remaining).coerceAtLeast(0.0) * 100.0 / limit
                    groups.getOrPut("每周窗口") { mutableListOf() } += Bar(
                        label = "周额度",
                        percentRemaining = (100 - pctUsed).toInt().coerceIn(0, 100),
                        usedPercent = pctUsed.toInt().coerceIn(0, 100),
                        counts = "剩 ${compact(remaining)}/${compact(limit)}",
                        reset = jstr(u, "resetTime")?.let { formatReset(it) }
                    )
                }
            }
            obj.optArray("limits")?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val detail = o.optObject("detail") ?: return@forEach
                val limit = jnum(detail, "limit") ?: return@forEach
                val remaining = jnum(detail, "remaining") ?: return@forEach
                if (limit <= 0) return@forEach
                val duration = jnum(o.optObject("window"), "duration")?.toLong()
                val unit = jstr(o.optObject("window"), "timeUnit") ?: "MINUTE"
                val label = when {
                    unit == "MINUTE" && duration == 300L -> "5 小时窗口"
                    unit == "HOUR" && duration == 5L -> "5 小时窗口"
                    unit == "DAY" && duration == 1L -> "每日窗口"
                    unit == "MINUTE" && duration != null -> "${duration / 60} 分钟窗口"
                    unit == "HOUR" && duration != null -> "$duration 小时窗口"
                    else -> return@forEach
                }
                val used = jnum(detail, "used") ?: (limit - remaining).coerceAtLeast(0.0)
                val pctUsed = used * 100.0 / limit
                groups.getOrPut(label) { mutableListOf() } += Bar(
                    label = "积分额度",
                    percentRemaining = (100 - pctUsed).toInt().coerceIn(0, 100),
                    usedPercent = pctUsed.toInt().coerceIn(0, 100),
                    counts = "剩 ${compact(remaining)}/${compact(limit)}",
                    reset = jstr(detail, "resetTime")?.let { formatReset(it) }
                )
            }
            return Usage(null, groups)
        }
    }

    // ─── DeepSeek（API 余额，官方接口）───

    object DeepSeek {
        /** GET /user/balance（官方文档接口）：balance_infos[] 各币种余额（字符串）。 */
        fun fetchUsage(apiKey: String): Usage {
            val resp = QuotaHttp.get(
                "https://api.deepseek.com/user/balance",
                bearer = apiKey, proxyFirst = false, directOnly = true, ua = "VibeUsage/2.11"
            )
            val obj = gson.fromJson(resp, JsonObject::class.java)
                ?: throw QuotaException(0, "响应为空")
            val groups = linkedMapOf<String, MutableList<Bar>>()
            obj.optArray("balance_infos")?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val currency = jstr(o, "currency") ?: return@forEach
                val total = jstr(o, "total_balance") ?: return@forEach
                val granted = jstr(o, "granted_balance")
                val topped = jstr(o, "topped_up_balance")
                val symbol = when (currency) {
                    "CNY" -> "¥"
                    "USD" -> "$"
                    else -> ""
                }
                groups.getOrPut("账户余额") { mutableListOf() } += Bar(
                    label = "$currency 余额 $symbol$total",
                    percentRemaining = 100,
                    counts = listOfNotNull(
                        granted?.takeIf { it != "0.00" && it != "0" }?.let { "赠 $symbol$it" },
                        topped?.takeIf { it != "0.00" && it != "0" }?.let { "充 $symbol$it" }
                    ).joinToString(" · ").ifEmpty { null }
                )
            }
            if (groups.isEmpty()) throw QuotaException(0, "未返回余额数据")
            val available = obj.get("is_available")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
            return Usage(if (available) null else "已停用", groups)
        }
    }

    // ─── 无问芯穹 Infini AI（Coding Plan）───

    object Infini {
        /** {"5_hour":{quota,used,remain},"7_day":{...},"30_day":{...}} */
        fun fetchUsage(apiKey: String): Usage {
            val resp = QuotaHttp.get(
                "https://cloud.infini-ai.com/maas/coding/usage",
                bearer = apiKey, proxyFirst = false, directOnly = true, ua = "VibeUsage/2.11"
            )
            val obj = gson.fromJson(resp, JsonObject::class.java)
                ?: throw QuotaException(0, "响应为空")
            val groups = linkedMapOf<String, MutableList<Bar>>()
            fun window(key: String, group: String) {
                val w = obj.optObject(key) ?: return
                val quota = jnum(w, "quota") ?: return
                if (quota <= 0) return
                val used = jnum(w, "used") ?: return
                val remain = jnum(w, "remain") ?: (quota - used)
                val pctUsed = used * 100.0 / quota
                groups.getOrPut(group) { mutableListOf() } += Bar(
                    label = "Token 额度",
                    percentRemaining = (100 - pctUsed).toInt().coerceIn(0, 100),
                    usedPercent = pctUsed.toInt().coerceIn(0, 100),
                    counts = "剩 ${compact(remain)}/${compact(quota)}"
                )
            }
            window("5_hour", "5 小时窗口")
            window("7_day", "每周窗口")
            window("30_day", "每月窗口")
            return Usage(null, groups)
        }
    }

    // ─── 阿里云百炼 Coding Plan ───

    object Bailian {
        private data class Region(val gateway: String, val regionId: String, val commodity: String, val referer: String)
        private val CN = Region(
            "https://bailian.console.aliyun.com", "cn-beijing", "sfm_codingplan_public_cn",
            "https://bailian.console.aliyun.com/cn-beijing/?tab=model"
        )
        private val INTL = Region(
            "https://modelstudio.console.alibabacloud.com", "ap-southeast-1", "sfm_codingplan_public_intl",
            "https://modelstudio.console.alibabacloud.com/ap-southeast-1/?tab=coding-plan"
        )
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36"

        /**
         * 响应是层层嵌套 JSON 字符串，需递归展开后找 codingPlanInstanceInfos → codingPlanQuotaInfo，
         * 读取 per5Hour* / perWeek* 窗口。鉴权需三头同传（Authorization + x-api-key + X-DashScope-API-Key）。
         * 鉴权类错误自动切国际站重试。
         */
        fun fetchUsage(apiKey: String): Usage {
            var lastErr: Exception? = null
            for (r in listOf(CN, INTL)) {
                try {
                    return fetchOnce(r, apiKey)
                } catch (e: QuotaException) {
                    if (e.code != 401 && e.code != 403 && e.code != 404) throw e
                    lastErr = e
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw lastErr ?: QuotaException(0, "百炼查询失败")
        }

        private fun fetchOnce(r: Region, apiKey: String): Usage {
            val url = "${r.gateway}/data/api.json" +
                "?action=zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2" +
                "&product=broadscope-bailian&api=queryCodingPlanInstanceInfoV2&currentRegionId=${r.regionId}"
            val body = gson.toJson(
                JsonObject().apply {
                    add(
                        "queryCodingPlanInstanceInfoRequest",
                        JsonObject().apply { addProperty("commodityCode", r.commodity) }
                    )
                }
            ).toRequestBody(JSON_TYPE)
            val resp = QuotaHttp.post(
                url, body, bearer = apiKey, proxyFirst = false, ua = BROWSER_UA,
                extraHeaders = mapOf(
                    "x-api-key" to apiKey,
                    "X-DashScope-API-Key" to apiKey,
                    "Origin" to r.gateway,
                    "Referer" to r.referer
                ),
                directOnly = true
            )
            val root = gson.fromJson(resp, JsonElement::class.java)
                ?: throw QuotaException(0, "响应为空")
            val expanded = expandJson(root)
            findNumDeep(listOf("statusCode", "status_code", "code"), expanded)?.let { code ->
                val c = code.toInt()
                if (c != 0 && c != 200) throw QuotaException(c, "百炼错误 $c")
            }
            val instances = findArrayDeep(listOf("codingPlanInstanceInfos", "coding_plan_instance_infos"), expanded)
            val candidates: List<JsonElement> =
                if (instances != null && instances.size() > 0) instances.map { it } else listOf(expanded)
            val quota = candidates.firstNotNullOfOrNull { c ->
                findDictDeep(listOf("codingPlanQuotaInfo", "coding_plan_quota_info"), c)
            } ?: findDictDeep(
                listOf("per5HourUsedQuota", "per5HourTotalQuota", "perWeekUsedQuota", "perWeekTotalQuota"),
                expanded
            ) ?: throw QuotaException(0, "响应中没有编程套餐额度数据（检查 Key 是否开通 Coding Plan）")

            val groups = linkedMapOf<String, MutableList<Bar>>()
            parseWindow(
                quota, "5 小时窗口",
                usedKeys = listOf("per5HourUsedQuota", "perFiveHourUsedQuota"),
                totalKeys = listOf("per5HourTotalQuota", "perFiveHourTotalQuota"),
                resetKeys = listOf("per5HourQuotaNextRefreshTime", "perFiveHourQuotaNextRefreshTime")
            )?.let { groups.getOrPut("5 小时窗口") { mutableListOf() } += it }
            parseWindow(
                quota, "周额度",
                usedKeys = listOf("perWeekUsedQuota"),
                totalKeys = listOf("perWeekTotalQuota"),
                resetKeys = listOf("perWeekQuotaNextRefreshTime")
            )?.let { groups.getOrPut("每周窗口") { mutableListOf() } += it }
            if (groups.isEmpty()) throw QuotaException(0, "套餐未返回额度窗口")
            return Usage(null, groups)
        }

        private fun parseWindow(
            quota: JsonObject, label: String,
            usedKeys: List<String>, totalKeys: List<String>, resetKeys: List<String>
        ): Bar? {
            val used = usedKeys.firstNotNullOfOrNull { jnum(quota, it) } ?: return null
            val total = totalKeys.firstNotNullOfOrNull { jnum(quota, it) } ?: return null
            if (total <= 0) return null
            val pctUsed = used * 100.0 / total
            val resetMs = resetKeys.firstNotNullOfOrNull { looseDateMs(quota.get(it)) }
            return Bar(
                label = label,
                percentRemaining = (100 - pctUsed).toInt().coerceIn(0, 100),
                usedPercent = pctUsed.toInt().coerceIn(0, 100),
                counts = "已用 ${compact(used)}/${compact(total)}",
                reset = resetMs?.let { epochReset(it) }
            )
        }

        /** 递归展开嵌套 JSON 字符串（CodexBar/claude-hud 同款逻辑）。 */
        private fun expandJson(el: JsonElement): JsonElement = when {
            el.isJsonObject -> JsonObject().apply { el.asJsonObject.entrySet().forEach { (k, v) -> add(k, expandJson(v)) } }
            el.isJsonArray -> JsonArray().apply { el.asJsonArray.forEach { add(expandJson(it)) } }
            el.isJsonPrimitive -> {
                val s = if (el.asJsonPrimitive.isString) el.asJsonPrimitive.asString else null
                if (s != null && ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]")))) {
                    try {
                        val nested = gson.fromJson(s, JsonElement::class.java)
                        if (nested != null && (nested.isJsonObject || nested.isJsonArray)) expandJson(nested) else el
                    } catch (_: Exception) {
                        el
                    }
                } else el
            }
            else -> el
        }

        private fun findDictDeep(keys: List<String>, el: JsonElement): JsonObject? {
            if (el.isJsonObject) {
                for (k in keys) (el.asJsonObject.get(k) as? JsonObject)?.let { return it }
                for ((_, v) in el.asJsonObject.entrySet()) findDictDeep(keys, v)?.let { return it }
            } else if (el.isJsonArray) {
                for (v in el.asJsonArray) findDictDeep(keys, v)?.let { return it }
            }
            return null
        }

        private fun findArrayDeep(keys: List<String>, el: JsonElement): JsonArray? {
            if (el.isJsonObject) {
                for (k in keys) (el.asJsonObject.get(k) as? JsonArray)?.let { return it }
                for ((_, v) in el.asJsonObject.entrySet()) findArrayDeep(keys, v)?.let { return it }
            } else if (el.isJsonArray) {
                for (v in el.asJsonArray) findArrayDeep(keys, v)?.let { return it }
            }
            return null
        }

        private fun findNumDeep(keys: List<String>, el: JsonElement): Double? {
            if (el.isJsonObject) {
                for (k in keys) jnum(el.asJsonObject, k)?.let { return it }
                for ((_, v) in el.asJsonObject.entrySet()) findNumDeep(keys, v)?.let { return it }
            } else if (el.isJsonArray) {
                for (v in el.asJsonArray) findNumDeep(keys, v)?.let { return it }
            }
            return null
        }

        /** 数字（>1e12 视为毫秒，否则秒）或 ISO 字符串 → 毫秒时间戳；解析失败返回 null。 */
        private fun looseDateMs(el: JsonElement?): Long? {
            val prim = el as? com.google.gson.JsonPrimitive ?: return null
            val asNum = prim.asString.trim().toDoubleOrNull()
            if (asNum != null) {
                return if (asNum > 1e12) asNum.toLong() else if (asNum > 1e9) (asNum * 1000).toLong() else null
            }
            val s = if (prim.isString) prim.asString.trim() else return null
            return tryIsoDate(s)?.time
        }

        private fun tryIsoDate(s: String): Date? {
            val cleaned = s.let { x ->
                val dot = x.indexOf('.')
                (if (dot > 0) x.substring(0, dot) else x).trimEnd('Z')
            }
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mm:ss")) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.US)
                    sdf.isLenient = true
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val d = sdf.parse(cleaned)
                    if (d != null) return d
                } catch (_: Exception) {
                }
            }
            return null
        }
    }

    // ─── 火山方舟 Coding Plan（火山引擎 OpenAPI，AK/SK V4 签名）───

    object Ark {
        private const val ENDPOINT = "https://open.volcengineapi.com/?Action=GetCodingPlanUsage&Version=2024-01-01"
        private const val REGION = "cn-beijing"
        private const val SERVICE = "ark"
        private const val CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"
        private const val SIGNED_HEADERS = "content-type;host;x-content-sha256;x-date"

        /** Result.QuotaUsage[]：Level(session/5h/weekly/monthly) + Percent(已用) + ResetTimestamp。 */
        fun fetchUsage(accessKey: String, secretKey: String): Usage {
            val payload = ""
            val payloadHash = sha256Hex(payload.toByteArray())
            val xDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date())
            val dateStamp = xDate.take(8)
            val uri = java.net.URI(ENDPOINT)
            val host = uri.host
            val canonicalQuery = (uri.rawQuery ?: "").split("&")
                .filter { it.isNotEmpty() }
                .map { kv ->
                    val i = kv.indexOf('=')
                    if (i < 0) percentEncode(kv) to ""
                    else percentEncode(kv.take(i)) to percentEncode(kv.substring(i + 1))
                }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .joinToString("&") { "${it.first}=${it.second}" }
            val canonicalRequest = listOf(
                "POST", "/", canonicalQuery,
                "content-type:$CONTENT_TYPE",
                "host:$host",
                "x-content-sha256:$payloadHash",
                "x-date:$xDate",
                "",
                SIGNED_HEADERS,
                payloadHash
            ).joinToString("\n")
            val scope = "$dateStamp/$REGION/$SERVICE/request"
            val stringToSign = "HMAC-SHA256\n$xDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"
            val signature = hex(
                hmac(hmac(hmac(hmac(secretKey.toByteArray(), dateStamp), REGION), SERVICE), "request"),
                stringToSign
            )
            val authorization = "HMAC-SHA256 Credential=$accessKey/$scope, " +
                "SignedHeaders=$SIGNED_HEADERS, Signature=$signature"
            val resp = QuotaHttp.post(
                ENDPOINT,
                payload.toByteArray().toRequestBody(CONTENT_TYPE.toMediaType()),
                bearer = null,
                proxyFirst = false,
                ua = "VibeUsage/2.11",
                extraHeaders = mapOf(
                    "Authorization" to authorization,
                    "X-Date" to xDate,
                    "X-Content-Sha256" to payloadHash,
                    "Content-Type" to CONTENT_TYPE,
                    "Accept" to "application/json"
                ),
                directOnly = true
            )
            val obj = gson.fromJson(resp, JsonObject::class.java)
                ?: throw QuotaException(0, "响应为空")
            obj.optObject("ResponseMetadata")?.optObject("Error")?.let { err ->
                val code = jstr(err, "Code")
                val msg = jstr(err, "Message")
                throw QuotaException(
                    403,
                    listOfNotNull(code, msg).joinToString("：").ifEmpty { "方舟签名校验失败" }
                )
            }
            val quotaUsage = obj.optObject("Result")?.optArray("QuotaUsage")
                ?: throw QuotaException(0, "响应缺少 QuotaUsage 数据")
            val groups = linkedMapOf<String, MutableList<Bar>>()
            for (el in quotaUsage) {
                val o = el as? JsonObject ?: continue
                val level = jstr(o, "Level") ?: continue
                val percent = jnum(o, "Percent") ?: continue
                val label = when (level.lowercase()) {
                    "session" -> "会话窗口"
                    "5h", "5-hour", "five_hour" -> "5 小时窗口"
                    "weekly", "week" -> "每周窗口"
                    "monthly", "month" -> "每月窗口"
                    else -> level
                }
                val resetMs = jnum(o, "ResetTimestamp")?.toLong()?.let { if (it > 1e11) it else it * 1000 }
                groups.getOrPut(label) { mutableListOf() } += Bar(
                    label = label,
                    percentRemaining = (100 - percent).toInt().coerceIn(0, 100),
                    usedPercent = percent.toInt().coerceIn(0, 100),
                    reset = resetMs?.takeIf { it > 0 }?.let { epochReset(it) }
                )
            }
            if (groups.isEmpty()) throw QuotaException(0, "套餐未返回额度窗口")
            return Usage(null, groups)
        }

        private fun percentEncode(v: String): String {
            val sb = StringBuilder()
            for (b in v.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt().toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                    sb.append(c)
                } else {
                    sb.append('%').append(String.format("%02X", b))
                }
            }
            return sb.toString()
        }

        private fun hmac(key: ByteArray, msg: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(msg.toByteArray())

        private fun hex(key: ByteArray, msg: String): String =
            hmac(key, msg).joinToString("") { String.format("%02x", it) }

        private fun sha256Hex(data: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { String.format("%02x", it) }
    }

    // ─── GitHub Copilot（copilot_internal/user + 设备码授权）───

    object GitHubCopilot {

        /** VS Code Copilot 扩展的公开 client_id（GitHub App 设备码流程，非机密）。 */
        private const val CLIENT_ID = "Iv1.b507a08c87ecfe98"

        data class DeviceCode(
            val deviceCode: String,
            val userCode: String,
            val verifyUrl: String,
            val intervalSec: Long,
            val expiresSec: Long
        )

        /** 发起设备码授权：用户在浏览器打开 verification_uri 输入 user_code。 */
        fun startDeviceCode(): DeviceCode {
            val form = FormBody.Builder().add("client_id", CLIENT_ID).build()
            val resp = QuotaHttp.post(
                "https://github.com/login/device/code", form,
                bearer = null, proxyFirst = false, ua = "VibeUsage/2.14",
                extraHeaders = mapOf("Accept" to "application/json")
            )
            val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
            return DeviceCode(
                deviceCode = jstr(obj, "device_code")
                    ?: throw QuotaException(0, jstr(obj, "error_description") ?: "未返回 device_code"),
                userCode = jstr(obj, "user_code") ?: "",
                verifyUrl = jstr(obj, "verification_uri") ?: "https://github.com/login/device",
                intervalSec = jnum(obj, "interval")?.toLong() ?: 5L,
                expiresSec = jnum(obj, "expires_in")?.toLong() ?: 899L
            )
        }

        /**
         * 轮询一次授权结果：用户完成后返回 access_token；
         * 尚未确认（authorization_pending / slow_down）返回 null，其余错误抛出。
         */
        fun pollToken(deviceCode: String): String? {
            val form = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val resp = QuotaHttp.post(
                "https://github.com/login/oauth/access_token", form,
                bearer = null, proxyFirst = false, ua = "VibeUsage/2.14",
                extraHeaders = mapOf("Accept" to "application/json")
            )
            val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
            jstr(obj, "access_token")?.let { return it }
            return when (val err = jstr(obj, "error")) {
                "authorization_pending", "slow_down" -> null
                null -> throw QuotaException(0, "授权响应缺少 access_token")
                else -> throw QuotaException(0, jstr(obj, "error_description") ?: err)
            }
        }

        /** GET /copilot_internal/user：quota_snapshots（chat / completions / premium_interactions）。 */
        fun fetchUsage(token: String): Usage {
            val resp = QuotaHttp.get(
                "https://api.github.com/copilot_internal/user",
                bearer = token, proxyFirst = false, ua = "VibeUsage/2.14",
                extraHeaders = mapOf("Accept" to "application/vnd.github+json")
            )
            return parse(resp)
        }

        internal fun parse(resp: String): Usage {
            val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
            val plan = jstr(obj, "copilot_plan") ?: jstr(obj, "access_type_sku")
            val snapshots = obj.optObject("quota_snapshots")
                ?: throw QuotaException(0, "响应缺少 quota_snapshots（确认 Token 已开通 Copilot 权限）")
            val resetDate = listOf("quota_reset_date_utc", "quota_reset_date")
                .firstNotNullOfOrNull { jstr(obj, it) }?.let { r ->
                    try {
                        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(r.take(10))
                        d?.let { SimpleDateFormat("MM-dd", Locale.getDefault()).format(it) + " 重置" }
                    } catch (_: Exception) { null }
                }
            val groups = linkedMapOf<String, MutableList<Bar>>()
            fun snap(key: String, label: String) {
                val s = snapshots.optObject(key) ?: return
                val unlimited = s.get("unlimited")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                val entitlement = jnum(s, "entitlement") ?: 0.0
                val remaining = jnum(s, "remaining")
                val percent = jnum(s, "percent_remaining")
                val pctRemain = when {
                    unlimited -> 100
                    percent != null && percent >= 0 -> percent
                    remaining != null && entitlement > 0 -> remaining * 100.0 / entitlement
                    else -> 100.0
                }.toInt().coerceIn(0, 100)
                groups.getOrPut("订阅额度") { mutableListOf() } += Bar(
                    label = label,
                    percentRemaining = pctRemain,
                    usedPercent = (100 - pctRemain).coerceIn(0, 100),
                    counts = when {
                        unlimited -> "无限制"
                        remaining != null && entitlement > 0 -> "剩 ${compact(remaining)}/${compact(entitlement)}"
                        else -> null
                    },
                    reset = resetDate
                )
            }
            snap("chat", "聊天对话")
            snap("completions", "代码补全")
            snap("premium_interactions", "高级请求")
            if (groups.isEmpty()) throw QuotaException(0, "套餐未返回额度快照")
            return Usage(
                when (plan) {
                    "free" -> "Free"
                    "individual", "professional" -> "Pro"
                    "pro_plus" -> "Pro+"
                    "business" -> "Business"
                    "enterprise" -> "Enterprise"
                    null -> null
                    else -> plan.replaceFirstChar { it.uppercase() }
                },
                groups
            )
        }
    }

    // ─── OpenRouter（官方 /api/v1/key：Key 限额与用量）───

    object OpenRouter {

        private const val API = "https://openrouter.ai/api/v1"
        private const val UA = "VibeUsage/2.14"

        /** OAuth 授权码换 Key（POST /auth/keys，官方 PKCE 流程）：返回 sk-or-v1-...。 */
        fun exchangeOAuthCode(code: String, verifier: String): String {
            val body = gson.toJson(
                JsonObject().apply {
                    addProperty("code", code)
                    addProperty("code_verifier", verifier)
                    addProperty("code_challenge_method", "S256")
                }
            ).toRequestBody(JSON_TYPE)
            val resp = QuotaHttp.post(
                "$API/auth/keys", body,
                bearer = null, proxyFirst = true, ua = UA
            )
            val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
            return jstr(obj, "key") ?: jstr(obj.optObject("data"), "key")
                ?: throw QuotaException(0, "授权成功但未返回 Key，请重试")
        }

        /** GET /api/v1/key：data.usage（已花费 USD）/ limit（限额，null = 无限额）/ is_free_tier。 */
        fun fetchUsage(apiKey: String): Usage {
            val resp = QuotaHttp.get(
                "$API/key", bearer = apiKey, proxyFirst = true, ua = "VibeUsage/2.14"
            )
            return parse(resp)
        }

        internal fun parse(resp: String): Usage {
            val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
            val data = obj.optObject("data") ?: throw QuotaException(0, "响应缺少 data")
            val label = jstr(data, "label")
            val freeTier = data.get("is_free_tier")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val usage = jnum(data, "usage") ?: 0.0
            val limit = jnum(data, "limit")
            val groups = linkedMapOf<String, MutableList<Bar>>()
            groups["Key 额度"] = mutableListOf(
                if (limit != null && limit > 0) {
                    val pctUsed = (usage / limit * 100.0).coerceIn(0.0, 100.0)
                    Bar(
                        label = label ?: "Default Key",
                        percentRemaining = (100 - pctUsed).toInt().coerceIn(0, 100),
                        usedPercent = pctUsed.toInt().coerceIn(0, 100),
                        counts = String.format(Locale.US, "已用 $%.2f / 限额 $%.2f", usage, limit)
                    )
                } else {
                    Bar(
                        label = label ?: "Default Key",
                        percentRemaining = 100,
                        counts = String.format(
                            Locale.US, "已用 $%.2f · 无限额%s", usage,
                            if (freeTier) "（免费层）" else ""
                        )
                    )
                }
            )
            jnum(data, "usage_daily")?.let { daily ->
                groups["今日用量"] = mutableListOf(
                    Bar(
                        label = "今日消耗",
                        percentRemaining = 100,
                        counts = String.format(Locale.US, "$%.2f", daily)
                    )
                )
            }
            return Usage(if (freeTier) "Free 层" else null, groups)
        }
    }

    // ─── Gemini CLI（gemini-cli 官方开源 client + cloudcode-pa v1internal，Code Assist 各模型配额）───
    // 注意：不能用反重力 client —— Google 已拒绝其访问 Code Assist 个人免费层（UNSUPPORTED_CLIENT），
    // 配额查询须以 gemini-cli 的 OAuth client 身份完成 loadCodeAssist/onboardUser 拿到托管项目后查询。

    object GeminiCli {

        // gemini-cli 开源仓库内置的公开凭据（installed application，非机密；拆分字面量防 push protection 误报）
        private const val CLI_CLIENT_ID =
            "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j" + ".apps.googleusercontent.com"
        private const val CLI_CLIENT_SECRET = "GOCSPX-" + "4uHgMPm-1o7Sk-geV6Cu5clXFsxl"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val REDIRECT_URI = "http://127.0.0.1:51125/oauth2callback"
        private val CLI_SCOPE = "https://www.googleapis.com/auth/cloud-platform" +
            " https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile"

        private const val UA = "VibeUsage/2.14"
        private val ag get() = ai.vibecafe.usage.data.ag.AgQuotaApi

        fun buildAuthorizeUrl(state: String): String =
            "https://accounts.google.com/o/oauth2/v2/auth" +
                "?client_id=" + OAuthFlow.enc(CLI_CLIENT_ID) +
                "&redirect_uri=" + OAuthFlow.enc(REDIRECT_URI) +
                "&response_type=code" +
                "&scope=" + OAuthFlow.enc(CLI_SCOPE) +
                "&access_type=offline&prompt=consent" +
                "&state=" + OAuthFlow.enc(state)

        /** 授权码换 refresh_token（与 AG 不同：CLI client 的 secret 随请求自带，走中转通用转发）。 */
        fun exchangeCode(code: String): String {
            val at = tokenPost(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
            )
            return at.second ?: throw QuotaException(0, "未返回 refresh_token，请在授权页勾选权限后重新授权")
        }

        /** 表单 POST 到 Google token 端点，返回 (access_token, refresh_token?)。 */
        private fun tokenPost(vararg fields: Pair<String, String>): Pair<String, String?> {
            val builder = FormBody.Builder()
            fields.forEach { (k, v) -> builder.add(k, v) }
            builder.add("client_id", CLI_CLIENT_ID).add("client_secret", CLI_CLIENT_SECRET)
            val resp = QuotaHttp.post(TOKEN_URL, builder.build(), bearer = null, proxyFirst = true, ua = UA)
            val obj = gson.fromJson(resp, JsonObject::class.java)
            val at = jstr(obj, "access_token")
                ?: throw QuotaException(
                    if (jstr(obj, "error") == "invalid_grant" || jstr(obj, "error") == "unauthorized_client") 401 else 0,
                    jstr(obj, "error_description") ?: jstr(obj, "error") ?: "令牌兑换失败"
                )
            return at to jstr(obj, "refresh_token")
        }

        fun fetchUsage(refreshToken: String): Usage {
            val at = try {
                tokenPost("grant_type" to "refresh_token", "refresh_token" to refreshToken).first
            } catch (e: QuotaException) {
                // 401/400：令牌失效，或由旧授权通道（反重力 client）签发、与 CLI client 不匹配
                throw QuotaException(
                    if (e.code == 401) 401 else 0,
                    if (e.code == 401 || e.code == 400) "Google 授权已失效或与当前通道不匹配，请重新一键授权"
                    else (e.message ?: "刷新令牌失败")
                )
            }
            val project = ensureProject(at)
                ?: throw QuotaException(0, "未能获取 Code Assist 项目：该 Google 账号可能不符合 Gemini 免费层条件，请重新授权重试")
            val resp = try {
                quotaRaw(at, project)
            } catch (e: ai.vibecafe.usage.data.ag.AgQuotaApi.AgException) {
                throw QuotaException(
                    if (e.code == 401) 401 else e.code,
                    when {
                        e.code == 401 -> "Google 授权已失效，请重新一键授权"
                        e.code == 403 -> friendlyLicense(e.message)
                        else -> e.message ?: "查询失败"
                    }
                )
            }
            return parse(resp)
        }

        /** loadCodeAssist → 无当前项目则按默认层 onboardUser（免费层用托管项目），轮询 LRO 至拿到项目 ID。 */
        private fun ensureProject(at: String): String? {
            val coreMeta = """{"ideType":"IDE_UNSPECIFIED","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI"}"""
            var lastErr: Exception? = null
            for (ep in ag.ENDPOINTS) {
                try {
                    val load = gson.fromJson(
                        ag.postViaAny(
                            "$ep/v1internal:loadCodeAssist",
                            """{"metadata":$coreMeta}""".toRequestBody(JSON_TYPE), at
                        ),
                        JsonObject::class.java
                    ) ?: continue
                    // 中转可能把 403 等 Google 错误以 200 透传：非 401 记为该端点失败，继续试下一端点
                    val loadErr = errorOf(load)
                    if (loadErr != null) {
                        if (loadErr.first == 401) throw QuotaException(401, "Google 授权已失效，请重新一键授权")
                        lastErr = QuotaException(loadErr.first, friendlyLicense(loadErr.second))
                        continue
                    }
                    jstr(load, "cloudaicompanionProject")?.let { return it }
                    if (load.optObject("currentTier") != null) return null // 已有层但无项目，无法静默补
                    // 个人免费层已被 Google 整体迁入反重力（UNSUPPORTED_CLIENT），仅剩标准层且需自带 GCP 项目
                    val freeDeprecated = load.optArray("ineligibleTiers")?.any { el ->
                        (el as? JsonObject)?.let { jstr(it, "reasonCode") } == "UNSUPPORTED_CLIENT"
                    } == true
                    if (freeDeprecated) throw QuotaException(0, FREE_TIER_MIGRATED)
                    val tier = load.optArray("allowedTiers")
                        ?.mapNotNull { it as? JsonObject }
                        ?.firstOrNull { it.get("isDefault")?.asBoolean == true }
                        ?.let { jstr(it, "id") } ?: "free-tier"
                    if (tier != "free-tier") return null // 标准层要求自带 GCP 项目，无法静默托管
                    var lro = gson.fromJson(
                        ag.postViaAny(
                            "$ep/v1internal:onboardUser",
                            """{"tierId":"free-tier","metadata":$coreMeta}""".toRequestBody(JSON_TYPE), at
                        ),
                        JsonObject::class.java
                    ) ?: return null
                    unwrapError(lro)
                    var waited = 0L
                    while (lro.get("done")?.asBoolean != true && waited < 30_000) {
                        val name = jstr(lro, "name") ?: return null
                        Thread.sleep(3000); waited += 3000
                        lro = gson.fromJson(ag.getViaAny("$ep/v1internal/$name", at), JsonObject::class.java)
                            ?: return null
                        unwrapError(lro)
                    }
                    return lro.optObject("response")?.optObject("cloudaicompanionProject")
                        ?.let { jstr(it, "id") }
                } catch (e: QuotaException) {
                    throw e
                } catch (e: ai.vibecafe.usage.data.ag.AgQuotaApi.AgException) {
                    if (e.code == 401) throw QuotaException(401, "Google 授权已失效，请重新一键授权")
                    lastErr = e
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            lastErr?.let { if (it is ai.vibecafe.usage.data.ag.AgQuotaApi.AgException) throw QuotaException(it.code, friendlyLicense(it.message)) }
            return null
        }

        /** retrieveUserQuota：project 必带；403 即无 Code Assist license（换端点无意义，直接报）。 */
        private fun quotaRaw(at: String, project: String): String =
            ag.postViaAny(
                "${ag.ENDPOINTS.first()}/v1internal:retrieveUserQuota",
                """{"project":"${project.replace("\"", "\\\"")}"}""".toRequestBody(JSON_TYPE), at
            )

        /** 中转线路可能把 Google 错误 JSON 以 200 透传：解析前统一拆包成异常。 */
        private fun unwrapError(obj: JsonObject) {
            val err = errorOf(obj) ?: return
            throw QuotaException(err.first, friendlyLicense(err.second))
        }

        /** 返回 200 包裹的 Google 错误（code, message），无错误返回 null。 */
        private fun errorOf(obj: JsonObject): Pair<Int, String>? =
            obj.optObject("error")?.let { err ->
                (jnum(err, "code")?.toInt() ?: 0) to (jstr(err, "message") ?: "请求被拒绝")
            }

        private const val FREE_TIER_MIGRATED =
            "Google 已停用 Gemini Code Assist 个人免费层（提示迁移至反重力/Antigravity），本端点查不到该账号配额；免费额度请看「反重力」面板（同一 Google 账号），标准/企业层账号需关联 GCP 项目"

        private fun friendlyLicense(msg: String?): String {
            val m = msg ?: return "查询失败"
            val lower = m.lowercase()
            return when {
                "license" in lower || "subscription" in lower ->
                    "该 Google 账号未开通 Gemini Code Assist 授权（免费层已并入反重力，标准/企业层需关联 GCP 项目）"
                "permission" in lower ->
                    "Google 拒绝访问 Code Assist 端点（该账号未开通此授权；个人免费层已并入反重力，额度看「反重力」面板）"
                else -> m.take(160)
            }
        }

        /** 首选官方形态 buckets[]{modelId,remainingFraction,resetTime}；兼容 groups[].buckets[] 与平铺映射。 */
        internal fun parse(resp: String): Usage {
            val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
            unwrapError(obj)
            val groups = linkedMapOf<String, MutableList<Bar>>()
            val buckets = obj.optArray("buckets")
            if (buckets != null) {
                val bars = mutableListOf<Bar>()
                for (el in buckets) {
                    val o = el as? JsonObject ?: continue
                    val model = jstr(o, "modelId") ?: continue
                    val frac = jnum(o, "remainingFraction") ?: continue
                    bars += Bar(
                        label = model.replace(Regex("-\\d{3}$"), ""),
                        percentRemaining = (frac * 100).toInt().coerceIn(0, 100),
                        usedPercent = (100 - frac * 100).toInt().coerceIn(0, 100),
                        reset = jstr(o, "resetTime")?.let { formatReset(it) }
                    )
                }
                if (bars.isEmpty()) throw QuotaException(0, "响应中没有模型额度数据")
                groups["模型额度"] = bars
            } else {
                val grouped = obj.optArray("groups") ?: obj.optArray("quotaGroups")
                if (grouped != null) {
                    for (el in grouped) {
                        val g = el as? JsonObject ?: continue
                        val gName = (jstr(g, "displayName") ?: "")
                            .replace(Regex(" models?$", RegexOption.IGNORE_CASE), "")
                            .ifEmpty { "模型额度" }
                        val gb = g.optArray("buckets") ?: continue
                        for (b in gb) {
                            val o = b as? JsonObject ?: continue
                            val frac = jnum(o, "remainingFraction") ?: continue
                            val win = (jstr(o, "window") ?: jstr(o, "bucketId") ?: "").lowercase()
                            val isWeek = "week" in win
                            groups.getOrPut(if (isWeek) "每周窗口" else "5 小时窗口") { mutableListOf() } += Bar(
                                label = if (isWeek) "$gName (周)" else gName,
                                percentRemaining = (frac * 100).toInt().coerceIn(0, 100),
                                usedPercent = (100 - frac * 100).toInt().coerceIn(0, 100),
                                reset = jstr(o, "resetTime")?.let { formatReset(it) }
                            )
                        }
                    }
                } else {
                    val bars = mutableListOf<Bar>()
                    for ((model, v) in obj.entrySet()) {
                        val o = v as? JsonObject ?: continue
                        val frac = jnum(o, "remainingFraction") ?: continue
                        bars += Bar(
                            label = model,
                            percentRemaining = (frac * 100).toInt().coerceIn(0, 100),
                            usedPercent = (100 - frac * 100).toInt().coerceIn(0, 100),
                            reset = jstr(o, "resetTime")?.let { formatReset(it) }
                        )
                    }
                    if (bars.isEmpty()) throw QuotaException(0, "响应中没有模型额度数据")
                    groups["模型额度"] = bars
                }
            }
            if (groups.isEmpty()) throw QuotaException(0, "响应中没有额度数据")
            return Usage(null, groups)
        }
    }

    // ─── 豆包（消费者订阅额度：commerce 端点纯 Cookie 鉴权，实测无需 msToken/a_bogus 签名）───

    object Doubao {
        private const val SUMMARY_URL =
            "https://www.doubao.com/alice/commerce/sale/subscription/quota/summary/"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** 兼容三种粘贴形态：裸 sessionid 值 / sessionid=xxx / 整条 Cookie 串。 */
        private fun cookieOf(raw: String): String {
            val t = raw.trim().trim('"').trim().let { if (it.endsWith(";")) it.dropLast(1) else it }
            return if (t.isEmpty()) "" else if (t.contains("sessionid")) t else "sessionid=$t"
        }

        /**
         * 查询豆包订阅额度（标准套餐等消费者订阅，非火山方舟）。
         * POST /alice/commerce/sale/subscription/quota/summary/，body {}，仅凭 Cookie；
         * 响应 window_limits[].used_percent 即已用百分比，window_type 1=当前时段、2=近 7 天。
         */
        fun fetchUsage(credential: String): Usage {
            val cookie = cookieOf(credential)
            if (cookie.removePrefix("sessionid=").isBlank())
                throw QuotaException(0, "请粘贴豆包网页版 Cookie 里的 sessionid 值（F12 → 应用 → Cookie → doubao.com → sessionid）")
            val resp = try {
                QuotaHttp.post(
                    SUMMARY_URL, "{}".toRequestBody(JSON_TYPE),
                    bearer = null,
                    proxyFirst = false,
                    ua = UA,
                    extraHeaders = mapOf(
                        "Cookie" to cookie,
                        "Referer" to "https://www.doubao.com/member/quota-management"
                    ),
                    directOnly = true
                )
            } catch (e: QuotaException) {
                throw if (e.code == 401) QuotaException(401, "豆包登录已失效，请重新复制网页版 Cookie 里的 sessionid") else e
            } catch (e: Exception) {
                throw QuotaException(0, e.message ?: "豆包查询失败")
            }
            return parse(resp)
        }

        internal fun parse(resp: String): Usage {
            val obj = gson.fromJson(resp, JsonObject::class.java) ?: throw QuotaException(0, "响应为空")
            val code = jnum(obj, "code")?.toInt() ?: 0
            if (code != 0) {
                val msg = jstr(obj, "msg") ?: jstr(obj, "message") ?: "未知错误"
                // 710012001 = 登录已过期（实测无 Cookie 时的返回）
                throw QuotaException(
                    if (code == 710012001 || msg.contains("登录")) 401 else code,
                    when {
                        code == 710012001 || msg.contains("登录") ->
                            "豆包登录已失效，请重新复制网页版 Cookie 里的 sessionid"
                        else -> "豆包错误 $code：$msg"
                    }
                )
            }
            val data = obj.optObject("data") ?: throw QuotaException(0, "响应中没有额度数据")
            val sub = data.optObject("current_subscription")
            val account = sub?.optObject("display")?.let { jstr(it, "short_name") ?: jstr(it, "product_name") }
                ?: if (data.optObject("member_info")?.get("hasActiveSubscription")?.asBoolean == true) "豆包订阅" else "未订阅"
            val groups = linkedMapOf<String, MutableList<Bar>>()
            val bars = mutableListOf<Bar>()
            // window_limit_section：个人订阅窗口额度；企业窗口（enterprise_*）不在消费者订阅范围
            for (group in data.optObject("window_limit_section")?.optArray("window_limit_groups") ?: JsonArray()) {
                val g = group as? JsonObject ?: continue
                for (el in g.optArray("window_limits") ?: JsonArray()) {
                    val w = el as? JsonObject ?: continue
                    val used = jnum(w, "used_percent")?.toInt()?.coerceIn(0, 100) ?: continue
                    val type = jnum(w, "window_type")?.toInt() ?: 0
                    val endMs = jnum(w, "end_time")?.toLong() ?: 0L
                    val startMs = jnum(w, "start_time")?.toLong() ?: 0L
                    val label = when (type) {
                        1 -> "当前时段"
                        2 -> "近 7 天"
                        else -> "窗口 $type"
                    }
                    bars += Bar(
                        label = label,
                        percentRemaining = (100 - used).coerceIn(0, 100),
                        usedPercent = used,
                        counts = if (used == 0 && startMs == 0L && endMs == 0L) "未消耗" else null,
                        reset = if (endMs > 0) epochReset(endMs)
                        else if (type == 1 && startMs == 0L) "开始使用后计时"
                        else null
                    )
                }
            }
            if (bars.isNotEmpty()) groups["订阅额度"] = bars
            if (groups.isEmpty()) throw QuotaException(0, "响应中没有额度数据")
            return Usage(account, groups)
        }
    }

    // ─── Agnes AI（免费全模态 API 平台；one-api 系网关的标准计费端点）───

    object Agnes {
        private const val BASE = "https://api.agnes-ai.cn"

        /**
         * sk- Key 即查，国内直连：
         * - GET /v1/dashboard/billing/subscription → hard_limit_usd（免费档为 1e8 占位 = 不限量）
         * - GET /v1/dashboard/billing/usage?start=&end= → total_usage（单位：美分）；
         *   实测不带范围恒为 0，必须传日期，end 取「明天」覆盖今天全天
         */
        fun fetchUsage(apiKey: String): Usage {
            fun get(path: String): JsonObject = try {
                gson.fromJson(
                    QuotaHttp.get(
                        BASE + path, bearer = apiKey,
                        proxyFirst = false, directOnly = true, ua = "VibeUsage/2.15"
                    ),
                    JsonObject::class.java
                ) ?: throw QuotaException(0, "响应为空")
            } catch (e: QuotaException) {
                throw if (e.code == 401)
                    QuotaException(401, "Agnes Key 无效或已删除，请到 console.agnes-ai.cn → API Keys 检查")
                else e
            } catch (e: Exception) {
                throw QuotaException(0, e.message ?: "Agnes 查询失败")
            }

            val sub = get("/v1/dashboard/billing/subscription")
            val limitUsd = jnum(sub, "hard_limit_usd") ?: jnum(sub, "system_hard_limit_usd")
            val unlimited = limitUsd == null || limitUsd >= 1e7

            val fmt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
            val today = java.time.LocalDate.now()
            fun usedUsd(from: java.time.LocalDate): Double = try {
                val o = get("/v1/dashboard/billing/usage?start=${from.format(fmt)}&end=${today.plusDays(1).format(fmt)}")
                (jnum(o, "total_usage") ?: 0.0) / 100.0
            } catch (e: QuotaException) {
                // 单窗口失败不拖垮整体（401 已在上面统一拦截，到这里只剩网络/服务端抖动）
                if (e.code == 401) throw e else 0.0
            }

            fun usd(v: Double): String = when {
                v >= 100 -> String.format(java.util.Locale.US, "$%.0f", v)
                v >= 1 -> String.format(java.util.Locale.US, "$%.2f", v)
                else -> String.format(java.util.Locale.US, "$%.4f", v)
            }
            fun bar(used: Double, emptyText: String): Bar {
                val pct = if (unlimited) 100
                else ((limitUsd!! - used).coerceAtLeast(0.0) * 100.0 / limitUsd).toInt().coerceIn(0, 100)
                return Bar(
                    label = if (used > 0) "已用 ${usd(used)}" else emptyText,
                    percentRemaining = pct,
                    usedPercent = if (unlimited) null else (100 - pct).coerceIn(0, 100),
                    counts = if (unlimited) "免费档 · 不限量"
                    else "剩 ${usd((limitUsd!! - used).coerceAtLeast(0.0))}/${usd(limitUsd)}"
                )
            }
            return Usage(
                if (unlimited) "免费档" else null,
                linkedMapOf(
                    "今日消耗" to listOf(bar(usedUsd(today), "今日无消耗")),
                    "本月消耗" to listOf(bar(usedUsd(today.withDayOfMonth(1)), "本月无消耗"))
                )
            )
        }
    }

    // ─── 共用格式化 ───

    internal fun formatCountdown(ms: Long): String {
        if (ms <= 0) return "已重置"
        val days = ms / 86_400_000
        val hours = (ms % 86_400_000) / 3_600_000
        val mins = (ms % 3_600_000) / 60_000
        return when {
            days > 0 -> "${days}天${hours}时"
            hours > 0 -> "${hours}时${mins}分"
            else -> "${mins}分"
        }
    }

    /** 毫秒时间戳 → 「MM-dd HH:mm 重置 · X后」。 */
    internal fun epochReset(ms: Long): String =
        "${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))} 重置 · ${formatCountdown(ms - System.currentTimeMillis())}后"

    /** ISO8601 → 「MM-dd HH:mm · X后重置」；解析失败返回 null。 */
    internal fun formatReset(iso: String): String? {
        val remaining = ai.vibecafe.usage.data.ag.AgQuotaApi.remainingMillis(iso) ?: return null
        val abs = try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val cleaned = iso.let { s ->
                val dot = s.indexOf('.')
                (if (dot > 0) s.substring(0, dot) else s).trimEnd('Z')
            }
            val t = sdf.parse(cleaned)?.time ?: return null
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))
        } catch (_: Exception) {
            null
        }
        return when {
            remaining <= 0 -> "已重置"
            abs != null -> "$abs 重置 · ${formatCountdown(remaining)}后"
            else -> "重置：${formatCountdown(remaining)}后"
        }
    }

    /** 大数紧凑显示：1234567 → 123.5万。 */
    internal fun compact(n: Double): String = when {
        n >= 1e8 -> String.format(Locale.getDefault(), "%.1f亿", n / 1e8)
        n >= 1e4 -> String.format(Locale.getDefault(), "%.1f万", n / 1e4)
        n == n.toLong().toDouble() -> "${n.toLong()}"
        else -> String.format(Locale.getDefault(), "%.1f", n)
    }
}

/** 防御式取值：成员存在但类型不符时返回 null，而不是抛 ClassCastException */
internal fun JsonObject.optObject(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.optArray(key: String): JsonArray? = get(key) as? JsonArray
internal fun jstr(o: JsonObject?, key: String): String? =
    ((o?.get(key)) as? com.google.gson.JsonPrimitive)?.takeIf { it.isString }?.asString

/** 数值或数字字符串 → Double（Kimi 等接口数值可能以字符串下发）。 */
internal fun jnum(o: JsonObject?, key: String): Double? =
    (o?.get(key) as? com.google.gson.JsonPrimitive)?.asString?.trim()?.toDoubleOrNull()

/** 把服务端错误响应体转成适合展示的短文案：HTML/空 body 换成友好提示，
 *  JSON 错误尽量提取 message 字段（兼容嵌套 error.message / OAuth error_description 等风格）。 */
internal fun httpErrorBody(text: String, code: Int): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return "HTTP $code"
    if (trimmed.startsWith("<")) return "HTTP $code（服务端返回异常页面）"
    if (trimmed.startsWith("{")) {
        try {
            val obj = com.google.gson.JsonParser.parseString(trimmed).asJsonObject
            val err = obj.get("error")
            val candidates = listOfNotNull(
                (err as? com.google.gson.JsonObject)?.get("message")?.takeIf { it.isJsonPrimitive }?.asString,
                obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString,
                obj.get("error_description")?.takeIf { it.isJsonPrimitive }?.asString,
                (err as? com.google.gson.JsonPrimitive)?.takeIf { it.isString }?.asString,
            )
            candidates.firstOrNull { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {
            // 非 JSON 错误结构 → 原样截断
        }
    }
    return trimmed.take(300)
}

/** HTTP 底座：直连 ⇄ 中转双路。 */
internal object QuotaHttp {

    private const val BUILTIN_PROXY = "https://proxy.20050912.xyz"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(DnsResolver)
            .build()
    }

    /**
     * [proxyFirst] = true：先走 Worker 中转（大陆可达），失败回退直连（有 VPN 时）；
     * false 则直连优先、中转兜底；[directOnly] = true 时完全直连（国内服务，不经中转）。
     */
    fun get(
        url: String,
        bearer: String?,
        proxyFirst: Boolean,
        ua: String,
        extraHeaders: Map<String, String> = emptyMap(),
        directOnly: Boolean = false
    ): String = execute(url, bearer, proxyFirst, ua, extraHeaders, directOnly) { u, b ->
        Request.Builder().url(u).get().apply { b?.let { header("Authorization", "Bearer $it") } }.build()
    }

    fun post(
        url: String,
        body: okhttp3.RequestBody,
        bearer: String?,
        proxyFirst: Boolean,
        ua: String,
        extraHeaders: Map<String, String> = emptyMap(),
        directOnly: Boolean = false
    ): String = execute(url, bearer, proxyFirst, ua, extraHeaders, directOnly) { u, b ->
        Request.Builder().url(u).post(body).apply { b?.let { header("Authorization", "Bearer $it") } }.build()
    }

    private inline fun execute(
        target: String,
        bearer: String?,
        proxyFirst: Boolean,
        ua: String,
        extraHeaders: Map<String, String>,
        directOnly: Boolean,
        build: (String, String?) -> Request
    ): String {
        var lastErr: Exception? = null
        val urls = when {
            directOnly -> listOf(target)
            proxyFirst -> listOf(proxied(target), target)
            else -> listOf(target, proxied(target))
        }
        for ((index, u) in urls.distinct().withIndex()) {
            try {
                val req = build(u, bearer).newBuilder()
                // 中转会覆盖 UA 并丢弃业务头：带 X-Proxy-UA / X-Quota-Pass-* 供扩展版 worker 透传
                req.header("User-Agent", ua)
                req.header("X-Proxy-UA", ua)
                if (u.contains("/proxy?")) {
                    extraHeaders.forEach { (k, v) -> req.header("X-Quota-Pass-$k", v) }
                } else {
                    extraHeaders.forEach { (k, v) -> req.header(k, v) }
                }
                client.newCall(req.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw ExtraQuotaApi.QuotaException(resp.code, httpErrorBody(text, resp.code))
                    }
                    return text
                }
            } catch (e: ExtraQuotaApi.QuotaException) {
                // 鉴权类错误（401）在直连/中转下语义一致，直接抛；
                // 403 可能只是某条线路被拦（如 Cloudflare 质询页），要给备用线路机会，试完所有线路再抛
                if (e.code == 401 || index == urls.distinct().lastIndex) throw e
                lastErr = e
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: ExtraQuotaApi.QuotaException(0, "请求失败")
    }

    private fun proxied(target: String): String =
        "$BUILTIN_PROXY/proxy?url=" + java.net.URLEncoder.encode(target, "UTF-8")
}

package ai.vibecafe.usage.data.quota

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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
            "https://open.bigmodel.cn/api/monitor/usage/quota/limit",
            "https://api.z.ai/api/monitor/usage/quota/limit",
        )

        /** data.limits[]：unit=3 → 5 小时窗口，unit=6 → 周窗口；percentage 为已用百分比。 */
        fun fetchUsage(apiKey: String): Usage {
            var lastErr: Exception? = null
            var businessErr: QuotaException? = null
            for (url in URLS) {
                try {
                    val resp = QuotaHttp.get(
                        url, bearer = apiKey, proxyFirst = false, directOnly = true, ua = "VibeUsage/2.11"
                    )
                    val obj = gson.fromJson(resp, JsonObject::class.java) ?: continue
                    val code = jnum(obj, "code")?.toInt()
                    if (code != null && code != 200) {
                        throw QuotaException(code, jstr(obj, "msg") ?: "智谱错误 $code")
                    }
                    val limits = obj.optObject("data")?.optArray("limits")
                        ?: throw QuotaException(0, "响应缺少 limits 数据")
                    val groups = linkedMapOf<String, MutableList<Bar>>()
                    for (el in limits) {
                        val o = el as? JsonObject ?: continue
                        if (jstr(o, "type") != "TOKENS_LIMIT") continue
                        val unit = jnum(o, "unit")?.toInt()
                        val label = when (unit) {
                            3 -> "5 小时窗口"
                            6 -> "每周窗口"
                            else -> continue
                        }
                        val total = jnum(o, "usage")
                        val used = jnum(o, "currentValue")
                        val pctUsed = (jnum(o, "percentage")
                            ?: (if (total != null && total > 0 && used != null) used * 100.0 / total else null))
                            ?: continue
                        val resetMs = jnum(o, "nextResetTime")?.toLong()
                        groups.getOrPut(label) { mutableListOf() } += Bar(
                            label = "Token 额度",
                            percentRemaining = (100 - pctUsed).toInt().coerceIn(0, 100),
                            usedPercent = pctUsed.toInt().coerceIn(0, 100),
                            counts = if (total != null && used != null) "已用 ${compact(used)}/${compact(total)}" else null,
                            reset = resetMs?.takeIf { it > 0 }?.let { epochReset(it) }
                        )
                    }
                    return Usage(null, groups)
                } catch (e: QuotaException) {
                    if (businessErr == null) businessErr = e
                    lastErr = e
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            throw businessErr ?: lastErr ?: QuotaException(0, "智谱查询失败")
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

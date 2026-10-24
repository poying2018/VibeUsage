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
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.JSON_TYPE
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Usage
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.compact
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.epochReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.fetchModelIds
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatCountdown
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.gson

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
        findStatusCode(expanded)?.let { code ->
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
        return Usage(
            null, groups,
            fetchModelIds("https://dashscope.aliyuncs.com/compatible-mode/v1/models", apiKey, proxyFirst = false, directOnly = true)
        )
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

    /**
     * 状态码只在**顶层对象**与明确的业务字段路径（data / result）上找。
     *
     * 之前是全树递归找 statusCode/status_code/code，会命中额度明细里的无关数值字段
     * （如窗口配额里的 code），把正常响应误判成错误。现在找不到就认为成功。
     */
    private fun findStatusCode(el: JsonElement): Double? {
        val root = el as? JsonObject ?: return null
        for (k in listOf("statusCode", "status_code", "code")) {
            jnum(root, k)?.let { return it }
        }
        // 明确的业务字段路径：只下钻一层（data / result 这类包装对象），不碰任意子对象。
        // 额度明细（codingPlanQuotaInfo 里的 quota / code 之类）在更深一层，不会被误读
        for (path in listOf("data", "result")) {
            val sub = root.optObject(path) ?: continue
            for (k in listOf("statusCode", "status_code", "code")) {
                jnum(sub, k)?.let { return it }
            }
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

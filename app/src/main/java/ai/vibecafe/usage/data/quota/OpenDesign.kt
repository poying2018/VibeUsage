package ai.vibecafe.usage.data.quota

import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Bar
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Usage
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.compact
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.formatReset
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.math.roundToInt

// ─── OpenDesign Go（go.open-design.ai 的模型订阅；开源控制台 + AMR 网关）───
//
// 凭据：控制台「API keys」页创建的 Cloud Key，单条 Bearer 即可。
// 网关：https://amr-api.open-design.ai/api/v1（官方文档的 media/models 示例也是这个域名）。
//
// 端点（无 Key 实测 401 而非 404，说明路由存在）：
//   GET /api/v1/workspaces/current        → workspaceId（session/Bearer 均可读）
//   GET /api/v1/me                        → 工作空间身份兜底
//   GET /api/v1/billing/summary           → 账号级摘要兜底
//   GET /api/v1/workspaces/{id}/billing/snapshot
//        → Coding Plan 窗口 + 钱包余额 + 套餐档位
//   GET /api/v1/models                    → 该工作空间可用模型（带 Key）
//   GET /api/v1/public/model-prices       → 官方公开模型目录（无需 Key，兜底）
//
// snapshot 的字段口径来自 OpenDesign 开源仓库
// apps/daemon/src/integrations/vela-billing.ts 的 parseWorkspaceBillingSnapshot：
//   {
//     schemaVersion: 1, workspaceId, workspaceMemberId, billingScopeVersion: 2,
//     billing: { billingState, planId },
//     wallet:  { balanceUsd, expiresAt, updatedAt },
//     designPlanWindows: [
//       { kind: "5h"|"7d", remainingCredits: "123", limitCredits: "456",
//         windowStart, resetsAt }   // 两个窗口分别按 5 小时 / 7 天重置
//     ]
//   }
// 注意：remainingCredits/limitCredits 是「模型用量价值」的额度，不是 token 数，
// 也不是 wallet 的美元余额；所以窗口只画百分比，金额单独放到「月度总额度」组。
//
// 月度上限取官方套餐页的常规月付口径（Go $220 / Plus $310 / Pro $780 / Max $1410），
// 仅用于把钱包余额换算成一个可比的百分比；首月优惠、年付、充值余额都不改变这个刻度，
// 文案里也写明是「常规月额度上限」，避免和真实账单混淆。
object OpenDesign {

    private const val API = "https://amr-api.open-design.ai/api/v1"
    private const val UA = "VibeUsage/2.26"

    /** 工作空间身份探测路径，按命中率排序；任何一条拿到 workspaceId 就用。 */
    private val IDENTITY_PATHS = listOf("workspaces/current", "me", "billing/summary")

    /** 模型列表探测路径；前两条带 Key，最后一条是公开目录兜底。 */
    private val MODEL_PATHS = listOf("models", "quick-setup/models?agent=opencode", "public/model-prices")

    internal data class Window(
        val kind: String,
        val remaining: Double,
        val limit: Double,
        val resetsAt: String?
    )

    internal data class Snapshot(
        val workspaceId: String?,
        val workspaceMemberId: String?,
        val planId: String?,
        val billingState: String?,
        val balanceUsd: Double?,
        val windows: List<Window>,
        val allowedModels: Set<String> = emptySet()
    )

    fun fetchUsage(apiKey: String): Usage {
        // 一键授权存的是 od_console: 前缀的令牌包；手工粘贴的 Key 原样使用。
        val key = OpenDesignDevice.decode(apiKey)?.controlKey
            ?: apiKey.trim().removePrefix("Bearer ").trim()
        if (key.isEmpty()) throw QuotaException(0, "请填写 OpenDesign API Key 或使用一键授权")

        val workspaceId = resolveWorkspaceId(key)
            ?: throw QuotaException(
                0,
                "无法从 API Key 识别工作空间：请确认是 go.open-design.ai 控制台创建的 Key（需有额度读取权限）"
            )

        val snapshotText = try {
            QuotaHttp.get(
                "$API/workspaces/${encode(workspaceId)}/billing/snapshot",
                bearer = key,
                proxyFirst = false,
                ua = UA,
                extraHeaders = mapOf("x-vela-workspace-id" to workspaceId)
            )
        } catch (e: QuotaException) {
            if (e.code == 401) {
                throw QuotaException(401, "OpenDesign API Key 无效，或没有该工作空间的额度读取权限")
            }
            throw e
        }
        val obj = gson.fromJson(snapshotText, JsonObject::class.java)
            ?: throw QuotaException(0, "OpenDesign 额度响应为空")
        val snapshot = parseSnapshot(obj)
            ?: throw QuotaException(0, "OpenDesign 额度响应格式无法识别（工作空间 $workspaceId）")

        val groups = buildGroups(snapshot)
        val isFree = snapshot.planId?.equals("free", ignoreCase = true) == true ||
            snapshot.billingState?.equals("free", ignoreCase = true) == true
        if (groups.isEmpty() && !isFree) {
            throw QuotaException(
                0,
                "OpenDesign 未返回额度窗口：请确认已订阅 Go / Plus / Pro / Max，且 Key 属于该工作空间"
            )
        }
        return Usage(
            account = accountOf(snapshot),
            groups = groups,
            models = filterModels(fetchModels(key), snapshot.allowedModels),
            modelsLabel = "OpenDesign 可用模型"
        )
    }

    /** 用同一把 Key 依次试身份端点，拿到 workspaceId 即止；全部失败返回 null。 */
    internal fun resolveWorkspaceId(apiKey: String): String? {
        for (path in IDENTITY_PATHS) {
            val obj = runCatching {
                gson.fromJson(
                    QuotaHttp.get("$API/$path", bearer = apiKey, proxyFirst = false, ua = UA),
                    JsonObject::class.java
                )
            }.getOrNull() ?: continue
            findWorkspaceId(obj)?.let { return it }
        }
        return null
    }

    /**
     * 从身份响应里挖 workspaceId，兼容几种包裹形状。
     * 纯函数，便于单测锁定「不要拿账号 id 当工作空间 id」。
     */
    internal fun findWorkspaceId(obj: JsonObject?): String? {
        if (obj == null) return null
        val candidates = listOfNotNull(
            jstr(obj, "workspaceId"),
            jstr(obj.optObject("workspace"), "workspaceId"),
            jstr(obj.optObject("workspace"), "id"),
            jstr(obj.optObject("data"), "workspaceId"),
            jstr(obj.optObject("currentWorkspace"), "workspaceId"),
            jstr(obj.optObject("context"), "workspaceId")
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    /** snapshot JSON → 强类型；schema/窗口字段不合法就返回 null（宁可不画，也不画错的）。 */
    internal fun parseSnapshot(obj: JsonObject): Snapshot? {
        if (jnum(obj, "schemaVersion") != 1.0) return null
        val billing = obj.optObject("billing") ?: return null
        val wallet = obj.optObject("wallet")
        val windows = mutableListOf<Window>()
        obj.optArray("designPlanWindows")?.forEach { el ->
            val w = el as? JsonObject ?: return@forEach
            val kind = jstr(w, "kind") ?: return@forEach
            if (kind != "5h" && kind != "7d") return@forEach
            val remaining = jnum(w, "remainingCredits")?.takeIf { it >= 0 } ?: return@forEach
            val limit = jnum(w, "limitCredits")?.takeIf { it > 0 } ?: return@forEach
            if (remaining > limit) return@forEach
            windows += Window(kind, remaining, limit, jstr(w, "resetsAt"))
        }
        // 既没有钱包也没有任一合法窗口：这份快照毫无可用信息，直接判无效。
        // 有窗口但钱包缺失是允许的（Go 套餐常见）。
        if (wallet == null && windows.isEmpty()) return null
        val allowed = obj.optObject("modelRestriction")?.optArray("allowedModels")
            ?.mapNotNull { el -> (el as? com.google.gson.JsonPrimitive)?.takeIf { it.isString }?.asString }
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        return Snapshot(
            workspaceId = jstr(obj, "workspaceId")?.takeIf { it.isNotBlank() },
            workspaceMemberId = jstr(obj, "workspaceMemberId")?.takeIf { it.isNotBlank() },
            planId = jstr(billing, "planId")?.takeIf { it.isNotBlank() },
            billingState = jstr(billing, "billingState")?.takeIf { it.isNotBlank() },
            balanceUsd = wallet?.let { jnum(it, "balanceUsd") },
            windows = windows,
            allowedModels = allowed
        )
    }

    /** 5h / 7d 窗口 → 展示分组。百分比是「剩余」，与项目里其它 provider 一致。 */
    internal fun buildGroups(snapshot: Snapshot): Map<String, List<Bar>> {
        val groups = linkedMapOf<String, MutableList<Bar>>()
        val coding = mutableListOf<Bar>()
        for (w in snapshot.windows) {
            val remainingPct = ((w.remaining / w.limit) * 100).roundToInt().coerceIn(0, 100)
            coding += Bar(
                label = if (w.kind == "5h") "5 小时窗口" else "7 天窗口",
                percentRemaining = remainingPct,
                usedPercent = 100 - remainingPct,
                counts = "剩余 ${compact(w.remaining)} / ${compact(w.limit)} 额度",
                reset = w.resetsAt?.let(::formatReset)
            )
        }
        if (coding.isNotEmpty()) groups["Coding Plan 窗口"] = coding

        // 月度总额度：钱包余额 ÷ 套餐常规月额度。planId 认不出来就只留窗口组，
        // 不用 0% 或 100% 冒充一个根本不知道上限的百分比。
        val cap = monthlyCap(snapshot.planId)
        val balance = snapshot.balanceUsd
        // 钱包为 0 不代表没额度：Go 本来就只有 Coding Plan，官方说明「钱包显示 0，为什么还能用」
        // 就是这个原因。此时只展示窗口，不画一条 0% 的月额度条误导用户。
        if (cap != null && balance != null && balance > 0) {
            val remainingPct = ((balance / cap) * 100).roundToInt().coerceIn(0, 100)
            groups["月度总额度"] = mutableListOf(
                Bar(
                    label = "钱包余额 / 常规月额度上限",
                    percentRemaining = remainingPct,
                    usedPercent = 100 - remainingPct,
                    counts = "余额 $${money(balance)} / 上限 $${money(cap)}",
                    reset = null
                )
            )
        }
        return groups
    }

    /** 官方常规月付额度（USD）；planId 形如 go / go_plus / go-plus / team_pro。 */
    internal fun monthlyCap(planId: String?): Double? {
        val raw = planId?.trim()?.lowercase()?.replace('-', '_') ?: return null
        val tier = listOf("go_plus", "go_pro", "go_max", "plus", "pro", "max", "go")
            .firstOrNull { raw.contains(it) } ?: return null
        return when (tier) {
            "go" -> 220.0
            "plus", "go_plus" -> 310.0
            "pro", "go_pro" -> 780.0
            "max", "go_max" -> 1410.0
            else -> null
        }
    }

    /** 账号胶囊：档位 + 钱包余额，都不泄漏内部 id。 */
    internal fun accountOf(snapshot: Snapshot): String? {
        val tier = when (monthlyCap(snapshot.planId)) {
            220.0 -> "Go"
            310.0 -> "Go Plus"
            780.0 -> "Go Pro"
            1410.0 -> "Go Max"
            else -> if (snapshot.planId?.equals("free", true) == true ||
                snapshot.billingState?.equals("free", true) == true
            ) "Free" else null
        }
        val balance = snapshot.balanceUsd?.takeIf { it > 0 }?.let { "余额 $${money(it)}" }
        return listOfNotNull(tier, balance).joinToString(" · ").takeIf { it.isNotBlank() }
    }

    /** /models 是公开全目录；snapshot.modelRestriction 非空时只保留账号可用项。 */
    internal fun filterModels(models: List<String>, allowed: Set<String>): List<String> {
        if (allowed.isEmpty()) return models
        return models.filter { id -> id in allowed || id.removePrefix("public_model_") in allowed }
    }

    /** 可用模型：优先账号维度，失败退回公开目录；任何异常都不拖垮额度展示。 */
    internal fun fetchModels(apiKey: String): List<String> {
        for (path in MODEL_PATHS) {
            val withAuth = path != "public/model-prices"
            val text = runCatching {
                QuotaHttp.get(
                    "$API/$path",
                    bearer = if (withAuth) apiKey else null,
                    proxyFirst = false,
                    ua = UA
                )
            }.getOrNull() ?: continue
            val ids = parseModelIds(text)
            if (ids.isNotEmpty()) return ids
        }
        return emptyList()
    }

    /** 兼容 data[].id / models[].model / 顶层字符串数组三种常见形状。 */
    internal fun parseModelIds(json: String): List<String> {
        val root = runCatching { gson.fromJson(json, JsonElement::class.java) }.getOrNull()
            ?: return emptyList()
        val ids = mutableListOf<String>()
        if (root.isJsonArray) {
            root.asJsonArray.forEach { el -> stringId(el)?.let(ids::add) }
        } else if (root.isJsonObject) {
            val obj = root.asJsonObject
            val arrays = listOfNotNull(
                obj.optArray("data"), obj.optArray("models"), obj.optArray("results")
            )
            arrays.forEach { arr -> arr.forEach { el -> stringId(el)?.let(ids::add) } }
            // public/model-prices 的形状是 {"data":[{"model":"gpt-..."}]}
            obj.optObject("data")?.let { data -> jstr(data, "model")?.let(ids::add) }
        }
        return ids.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    }

    private fun stringId(el: JsonElement?): String? = when {
        el == null || el.isJsonNull -> null
        el.isJsonPrimitive -> (el as com.google.gson.JsonPrimitive).asString.takeIf { it.isNotBlank() }
        el.isJsonObject -> {
            val o = el.asJsonObject
            jstr(o, "id") ?: jstr(o, "model") ?: jstr(o, "name")
        }
        else -> null
    }

    private fun money(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

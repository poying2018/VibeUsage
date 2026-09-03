package ai.vibecafe.usage.ui.ag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ai.vibecafe.usage.data.quota.ExtraQuotaApi

/** 「额度」页下拉可切换的凭据型供应商（反重力为独立面板，不在此列）。 */
enum class ExtraProvider(
    val id: String,
    val title: String,
    val color: Color,
    val icon: ImageVector,
    /** 凭据输入框标签；1 个 = 单 API Key，2 个 = AK/SK 双凭据。 */
    val credLabels: List<String>,
    /** 接入提示（去哪里获取凭据）。 */
    val hint: String
) {
    MINIMAX(
        "minimax", "MiniMax", Color(0xFFF0483E), Icons.Filled.BubbleChart,
        listOf("API Key"),
        "国内：platform.minimaxi.com → 接口密钥；国际：platform.minimax.io → API Keys。粘贴 sk-cp- 开头的 Token Plan 密钥"
    ),
    GLM(
        "glm", "智谱 GLM", Color(0xFF3859FF), Icons.Filled.AutoAwesome,
        listOf("API Key"),
        "bigmodel.cn 控制台 → API Keys（国际站 z.ai 同样支持），粘贴 GLM Coding Plan 密钥"
    ),
    KIMI(
        "kimi", "Kimi", Color(0xFFA8B3C5), Icons.Filled.DarkMode,
        listOf("API Key"),
        "kimi.com/code/console 创建 API Key，粘贴 sk-kimi- 开头的 Kimi For Coding 密钥"
    ),
    DEEPSEEK(
        "deepseek", "DeepSeek", Color(0xFF4D6BFE), Icons.Filled.Paid,
        listOf("API Key"),
        "platform.deepseek.com → API Keys，查询的是 API 账户余额（非订阅套餐）"
    ),
    INFINI(
        "infini", "无问芯穹", Color(0xFF00C2A8), Icons.Filled.AllInclusive,
        listOf("API Key"),
        "cloud.infini-ai.com 控制台 → API 密钥，粘贴 sk-cp- 开头的 Coding Plan 密钥"
    ),
    BAILIAN(
        "bailian", "阿里百炼", Color(0xFFFF7A45), Icons.Filled.Cloud,
        listOf("DashScope Key"),
        "bailian.console.aliyun.com → API-KEY 管理（业务密钥），需已开通 Coding Plan；国内/国际站自动切换"
    ),
    ARK(
        "ark", "火山方舟", Color(0xFF5AB0FF), Icons.Filled.RocketLaunch,
        listOf("AccessKey ID", "Secret AccessKey"),
        "火山引擎控制台 → API 访问密钥，粘贴 AK 与 SK（需已开通方舟 Coding Plan）"
    );

    /** 凭据在 quota_extra prefs 里的存储键（与 credLabels 一一对应）。 */
    val credPrefsKeys: List<String> =
        credLabels.mapIndexed { i, _ -> if (credLabels.size == 1) "${id}_key" else "${id}_cred$i" }

    /** 用已存凭据查询额度（在 IO 线程调用）。 */
    fun fetch(creds: List<String>): ExtraQuotaApi.Usage = when (this) {
        MINIMAX -> ExtraQuotaApi.MiniMax.fetchUsage(creds[0])
        GLM -> ExtraQuotaApi.Glm.fetchUsage(creds[0])
        KIMI -> ExtraQuotaApi.Kimi.fetchUsage(creds[0])
        DEEPSEEK -> ExtraQuotaApi.DeepSeek.fetchUsage(creds[0])
        INFINI -> ExtraQuotaApi.Infini.fetchUsage(creds[0])
        BAILIAN -> ExtraQuotaApi.Bailian.fetchUsage(creds[0])
        ARK -> ExtraQuotaApi.Ark.fetchUsage(creds[0], creds[1])
    }

    companion object {
        fun byId(id: String): ExtraProvider? = entries.firstOrNull { it.id == id }
    }
}

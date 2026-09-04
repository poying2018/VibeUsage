package ai.vibecafe.usage.ui.ag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Waves
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ai.vibecafe.usage.data.quota.ExtraQuotaApi

/** 支持一键授权的登录方式（凭据粘贴始终作为后备）。 */
enum class OAuthKind {
    /** Google OAuth（与反重力同通道，存 refresh_token）。 */
    GOOGLE,

    /** Gemini CLI 官方开源 client 的 Google OAuth（Code Assist 免费/付费层，存 refresh_token）。 */
    GEMINI_CLI_OAUTH,

    /** GitHub 设备码授权（浏览器输入 user_code，存 access_token）。 */
    GITHUB_DEVICE,

    /** OpenRouter 官方 OAuth（localhost 回调换 Key，存 sk-or Key）。 */
    OPENROUTER
}

/** 「额度」页下拉可切换的凭据型供应商（反重力为独立面板，不在此列）。 */
enum class ExtraProvider(
    val id: String,
    val title: String,
    val color: Color,
    val icon: ImageVector,
    /** 凭据输入框标签；1 个 = 单 API Key，2 个 = AK/SK 双凭据。 */
    val credLabels: List<String>,
    /** 接入提示（去哪里获取凭据）。 */
    val hint: String,
    /** 非空 = 页面顶部展示「一键授权登录」按钮。 */
    val oauth: OAuthKind? = null
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
    ),
    GITHUB(
        "github", "GitHub Copilot", Color(0xFFA371F7), Icons.Filled.Code,
        listOf("Personal Access Token (classic)"),
        "推荐一键授权（GitHub 设备码，无需手动建 Token）；手动：GitHub Settings → Developer settings → PAT (classic) 勾选 copilot 权限",
        OAuthKind.GITHUB_DEVICE
    ),
    OPENROUTER(
        "openrouter", "OpenRouter", Color(0xFF00A67E), Icons.Filled.Hub,
        listOf("API Key"),
        "推荐一键授权（自动创建名为 VibeUsage 的 Key）；手动：openrouter.ai/keys 复制 sk-or- 开头的 Key",
        OAuthKind.OPENROUTER
    ),
    GEMINICLI(
        "geminicli", "Gemini CLI", Color(0xFF4285F4), Icons.Filled.Bolt,
        listOf("Google Refresh Token（高级）"),
        "查询 Gemini Code Assist 各模型配额（标准/企业层）；注意：Google 已将个人免费层并入反重力，免费额度请看「反重力」面板",
        OAuthKind.GEMINI_CLI_OAUTH
    ),
    DOUBAO(
        "doubao", "豆包", Color(0xFF4E6EF2), Icons.Filled.SmartToy,
        listOf("sessionid Cookie"),
        "豆包网页版登录后 F12 → 应用/存储 → Cookie → doubao.com，复制 sessionid 的值粘贴（查订阅套餐额度，非火山方舟）；国内直连"
    ),
    AGNES(
        "agnes", "Agnes", Color(0xFFE0559F), Icons.Filled.Waves,
        listOf("API Key"),
        "console.agnes-ai.cn → API Keys 创建后立即复制，粘贴 sk- 开头的密钥（免费不限量平台，查今日/本月消耗；国内直连）"
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
        GITHUB -> ExtraQuotaApi.GitHubCopilot.fetchUsage(creds[0])
        OPENROUTER -> ExtraQuotaApi.OpenRouter.fetchUsage(creds[0])
        GEMINICLI -> ExtraQuotaApi.GeminiCli.fetchUsage(creds[0])
        DOUBAO -> ExtraQuotaApi.Doubao.fetchUsage(creds[0])
        AGNES -> ExtraQuotaApi.Agnes.fetchUsage(creds[0])
    }

    companion object {
        fun byId(id: String): ExtraProvider? = entries.firstOrNull { it.id == id }
    }
}

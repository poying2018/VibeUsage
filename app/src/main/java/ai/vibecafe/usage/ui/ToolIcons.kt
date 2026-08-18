package ai.vibecafe.usage.ui

import ai.vibecafe.usage.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

/** 工具/模型图标：真实品牌 Logo（彩色 PNG drawable 优先），无 Logo 的品牌用主题 Material 图标（vector）兜底。 */
internal data class ToolIcon(
    val label: String,
    val color: Color,
    val drawable: Int? = null,
    val vector: ImageVector? = null,
    val colorful: Boolean = false
)

/** 品牌匹配规则：aliases 为品牌在工具/模型名中的常见写法，模糊打分后取最高分者。 */
private data class IconRule(
    val label: String,
    val aliases: List<String>,
    val color: Color,
    val drawable: Int? = null,
    val vector: ImageVector? = null,
    val colorful: Boolean = false,
    val priority: Int = 0
)

private val iconRules = listOf(
    IconRule("Claude", listOf("claude", "anthropic"), Color(0xFFD97757), drawable = R.drawable.ic_tool_claude, colorful = true, priority = 50),
    IconRule("OpenAI", listOf("openai", "gpt", "chatgpt", "codex", "o1", "o3", "o4"), Color(0xFF10A37F), drawable = R.drawable.ic_tool_openai, colorful = true, priority = 45),
    IconRule("Gemini", listOf("gemini"), Color(0xFF7C6BFF), drawable = R.drawable.ic_tool_gemini, colorful = true, priority = 40),
    IconRule("DeepSeek", listOf("deepseek"), Color(0xFF4D6BFE), drawable = R.drawable.ic_tool_deepseek, colorful = true, priority = 40),
    IconRule("Qwen", listOf("qwen", "tongyi", "dashscope"), Color(0xFF615CED), drawable = R.drawable.ic_tool_qwen, colorful = true, priority = 40),
    IconRule("Kimi", listOf("kimi", "moonshot"), Color(0xFF0ABDFF), drawable = R.drawable.ic_tool_kimi, colorful = true, priority = 35),
    IconRule("Mistral", listOf("mistral", "mixtral", "codestral"), Color(0xFFFA520F), drawable = R.drawable.ic_tool_mistral, colorful = true, priority = 30),
    IconRule("Meta", listOf("llama", "meta"), Color(0xFF0866FF), drawable = R.drawable.ic_tool_meta, colorful = true, priority = 30),
    IconRule("Tencent", listOf("hunyuan", "hy3", "hy", "tencent"), Color(0xFF2D9BF0), drawable = R.drawable.ic_tool_tencent, colorful = true, priority = 30),
    IconRule("Zhipu", listOf("glm", "chatglm", "zhipu", "zcode", "zai"), Color(0xFF4F6EF7), drawable = R.drawable.ic_tool_zhipu, colorful = true, priority = 28),
    IconRule("Grok", listOf("grok", "groq", "xai"), Color(0xFF1F2937), drawable = R.drawable.ic_tool_grok, colorful = true, priority = 25),
    IconRule("Copilot", listOf("copilot"), Color(0xFF2DD4BF), vector = Icons.Filled.Rocket, priority = 25),
    IconRule("Cursor", listOf("cursor"), Color(0xFF3F3F46), drawable = R.drawable.ic_tool_cursor, priority = 25),
    IconRule("Trae", listOf("trae"), Color(0xFF5146F5), drawable = R.drawable.ic_tool_trae, colorful = true, priority = 25),
    IconRule("Cline", listOf("cline"), Color(0xFF14B8A6), drawable = R.drawable.ic_tool_cline, colorful = true, priority = 25),
    IconRule("OpenCode", listOf("opencode"), Color(0xFFF97316), drawable = R.drawable.ic_tool_opencode, colorful = true, priority = 22),
    IconRule("Hermes", listOf("hermes"), Color(0xFF374151), drawable = R.drawable.ic_tool_hermes, priority = 20),
    IconRule("LongCat", listOf("longcat"), Color(0xFF8B5CF6), drawable = R.drawable.ic_tool_longcat, colorful = true, priority = 20),
    IconRule("Xiaomi", listOf("mimo", "xiaomi"), Color(0xFFFF6900), drawable = R.drawable.ic_tool_xiaomi, colorful = true, priority = 20),
    IconRule("Alibaba", listOf("alibaba", "aliyun"), Color(0xFFFF6A00), drawable = R.drawable.ic_tool_alibaba, priority = 20),
    IconRule("Hugging Face", listOf("huggingface", "hf"), Color(0xFFFFD21E), drawable = R.drawable.ic_tool_huggingface, priority = 20),
    IconRule("Image", listOf("dall", "flux", "midjourney", "sora", "stable", "imagen", "ideogram", "seedream"), Color(0xFFF472B6), vector = Icons.Filled.Palette, priority = 20),
    IconRule("Kiro", listOf("kiro"), Color(0xFFFBBF24), vector = Icons.Filled.Edit, priority = 15)
)

/** 模糊匹配：对名称做 token 化，按 精确=100 / 前缀=60 / 包含=50 / 编辑距离≤1=40 打分，最高分者胜（同分取 priority）。 */
internal fun toolIconFor(name: String): ToolIcon {
    val n = name.lowercase(Locale.US).replace('_', ' ').replace('/', ' ').replace('.', ' ')
    val tokens = n.split(Regex("[\\s\\-+.]+")).filter { it.isNotBlank() }
    var best: IconRule? = null
    var bestScore = 0
    for (rule in iconRules) {
        var score = 0
        for (alias in rule.aliases) {
            when {
                tokens.any { it == alias } -> score = maxOf(score, 100)
                tokens.any { it.startsWith(alias) || alias.length >= 3 && alias.startsWith(it) && it.length >= 2 } ->
                    score = maxOf(score, 60)
                alias.length >= 3 && n.contains(alias) -> score = maxOf(score, 50)
                alias.length >= 3 && tokens.any { it.length >= 3 && levenshtein(it, alias) <= 1 } ->
                    score = maxOf(score, 40)
            }
        }
        if (score > 0 && (score > bestScore || score == bestScore && rule.priority > (best?.priority ?: -1))) {
            bestScore = score
            best = rule
        }
    }
    val r = best ?: return ToolIcon("AI", Color(0xFF64748B), vector = Icons.Filled.AutoAwesome)
    return ToolIcon(r.label, r.color, r.drawable, r.vector, r.colorful)
}

private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    val dp = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = dp[0]
        dp[0] = i
        for (j in 1..b.length) {
            val tmp = dp[j]
            dp[j] = minOf(
                dp[j] + 1,
                dp[j - 1] + 1,
                prev + if (a[i - 1] == b[j - 1]) 0 else 1
            )
            prev = tmp
        }
    }
    return dp[b.length]
}

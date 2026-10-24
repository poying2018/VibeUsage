package ai.vibecafe.usage.ui

import ai.vibecafe.usage.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Waves
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

/** 品牌匹配规则：aliases 为品牌在工具/模型名中的常见写法（含中文产品名），模糊打分后取最高分者。 */
private data class IconRule(
    val label: String,
    val aliases: List<String>,
    val color: Color,
    val drawable: Int? = null,
    val vector: ImageVector? = null,
    val colorful: Boolean = false,
    val priority: Int = 0,
    /** true = 别名只做精确匹配（token 完全相等），不参与前缀/子串/编辑距离匹配。 */
    val exactOnly: Boolean = false
)

private val iconRules = listOf(
    // ── 模型/工具品牌 ──
    IconRule("Claude", listOf("claude", "anthropic"), Color(0xFFD97757), drawable = R.drawable.ic_tool_claude, colorful = true, priority = 50),
    IconRule("OpenAI", listOf("openai", "gpt", "chatgpt", "codex", "o1", "o3", "o4"), Color(0xFF10A37F), drawable = R.drawable.ic_tool_openai, colorful = true, priority = 45),
    IconRule("Gemini", listOf("gemini", "antigravity", "反重力"), Color(0xFF7C6BFF), drawable = R.drawable.ic_tool_gemini, colorful = true, priority = 40),
    IconRule("DeepSeek", listOf("deepseek"), Color(0xFF4D6BFE), drawable = R.drawable.ic_tool_deepseek, colorful = true, priority = 40),
    IconRule("Qwen", listOf("qwen", "tongyi", "dashscope", "bailian", "百炼"), Color(0xFF615CED), drawable = R.drawable.ic_tool_qwen, colorful = true, priority = 40),
    IconRule("Kimi", listOf("kimi", "moonshot", "月之暗面"), Color(0xFF0ABDFF), drawable = R.drawable.ic_tool_kimi, colorful = true, priority = 35),
    IconRule("Doubao", listOf("doubao", "bytedance", "volcengine", "seedance", "seedream", "豆包", "火山方舟"), Color(0xFF4E6EF2), vector = Icons.Filled.SmartToy, priority = 30),
    IconRule("MiniMax", listOf("minimax", "minimaxi", "hailuo", "海螺"), Color(0xFFF0483E), vector = Icons.Filled.BubbleChart, priority = 28),
    IconRule("Mistral", listOf("mistral", "mixtral", "codestral"), Color(0xFFFA520F), drawable = R.drawable.ic_tool_mistral, colorful = true, priority = 30),
    IconRule("Meta", listOf("llama", "meta"), Color(0xFF0866FF), drawable = R.drawable.ic_tool_meta, colorful = true, priority = 30),
    IconRule("Tencent", listOf("hunyuan", "hy3", "hy", "tencent", "codebuddy", "workbuddy"), Color(0xFF2D9BF0), drawable = R.drawable.ic_tool_tencent, colorful = true, priority = 30),
    IconRule("Zhipu", listOf("glm", "chatglm", "zhipu", "zcode", "zai", "智谱"), Color(0xFF4F6EF7), drawable = R.drawable.ic_tool_zhipu, colorful = true, priority = 28),
    IconRule("Infini", listOf("infini", "无问芯穹"), Color(0xFF00C2A8), vector = Icons.Filled.AllInclusive, priority = 25),
    IconRule("Grok", listOf("grok", "groq", "xai"), Color(0xFF1F2937), drawable = R.drawable.ic_tool_grok, colorful = true, priority = 25),
    IconRule("Copilot", listOf("copilot"), Color(0xFF2DD4BF), vector = Icons.Filled.Rocket, priority = 25),
    IconRule("Cursor", listOf("cursor"), Color(0xFF3F3F46), drawable = R.drawable.ic_tool_cursor, priority = 25),
    IconRule("Trae", listOf("trae"), Color(0xFF5146F5), drawable = R.drawable.ic_tool_trae, colorful = true, priority = 25),
    IconRule("Cline", listOf("cline"), Color(0xFF14B8A6), drawable = R.drawable.ic_tool_cline, colorful = true, priority = 25),
    IconRule("OpenCode", listOf("opencode"), Color(0xFFF97316), drawable = R.drawable.ic_tool_opencode, colorful = true, priority = 22),
    IconRule("Agnes", listOf("agnes"), Color(0xFFE0559F), vector = Icons.Filled.Waves, priority = 22),
    IconRule("Hermes", listOf("hermes"), Color(0xFF374151), drawable = R.drawable.ic_tool_hermes, priority = 20),
    IconRule("LongCat", listOf("longcat", "龙猫"), Color(0xFF8B5CF6), drawable = R.drawable.ic_tool_longcat, colorful = true, priority = 20),
    IconRule("Xiaomi", listOf("mimo", "xiaomi"), Color(0xFFFF6900), drawable = R.drawable.ic_tool_xiaomi, colorful = true, priority = 20),
    IconRule("Alibaba", listOf("alibaba", "aliyun"), Color(0xFFFF6A00), drawable = R.drawable.ic_tool_alibaba, priority = 20),
    IconRule("Hugging Face", listOf("huggingface", "hf"), Color(0xFFFFD21E), drawable = R.drawable.ic_tool_huggingface, priority = 20),
    IconRule("Qoder", listOf("qoder"), Color(0xFF7C3AED), vector = Icons.Filled.Terminal, priority = 20),
    // CommandCode（AI 编程 CLI / 工作站）：品牌色取其官方深色标识。
    // 别名覆盖 CLI 在用量数据里的来源名。短别名 "cmc" 只做**精确**匹配
    // （3 字母的短别名若参与前缀命中，"cmc-turbo" 之类无关工具会被误配）。
    IconRule("CommandCode", listOf("commandcode", "command-code"), Color(0xFF1F2937), vector = Icons.Filled.Terminal, priority = 20),
    IconRule("CommandCode", listOf("cmc"), Color(0xFF1F2937), vector = Icons.Filled.Terminal, priority = 20, exactOnly = true),
    IconRule("Windsurf", listOf("windsurf", "codeium"), Color(0xFF32B86C), vector = Icons.Filled.AutoAwesome, priority = 18),
    IconRule("Image", listOf("dall", "flux", "midjourney", "sora", "stable", "imagen", "ideogram"), Color(0xFFF472B6), vector = Icons.Filled.Palette, priority = 20),
    IconRule("Kiro", listOf("kiro"), Color(0xFFFBBF24), vector = Icons.Filled.Edit, priority = 15)
)

/**
 * 模糊匹配（对大小写、全半角、分隔符写法、日期/装饰后缀、胶连写法都稳健）：
 * 1. 归一化：小写 + 全角转半角，再按「非字母/非数字」全部切开成 token（CJK 算字母，中文产品名整体成 token）；
 *    日期戳(-20250929)、:free/-preview 之类装饰符切开即成为独立 token，不影响品牌 token 命中。
 * 2. 打分：token==alias 精确=100；一方为另一方前缀=60（两位别名 o1/o3/o4 胶连 o4mini 也靠这条）；
 *    名称包含 alias=50（两字中文别名如 智谱/豆包 也允许）；编辑距离≤1=40。最高分者胜，同分取 priority。
 */
private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")

/**
 * 图标查找结果缓存：仪表盘一次重组要查 30+ 次，而每次都要跑 28 条规则 × 多别名的
 * 模糊匹配（含编辑距离 DP），所以按名字缓存。名字集合有界，另设上限兜底。
 */
private val iconCache = java.util.concurrent.ConcurrentHashMap<String, ToolIcon>()
private const val ICON_CACHE_MAX = 512

internal fun toolIconFor(name: String): ToolIcon {
    iconCache[name]?.let { return it }
    val icon = computeToolIconFor(name)
    if (iconCache.size < ICON_CACHE_MAX) iconCache[name] = icon
    return icon
}

private fun computeToolIconFor(name: String): ToolIcon {
    val lowered = name.lowercase(Locale.US)
    val sb = StringBuilder(lowered.length)
    for (ch in lowered) {
        val c = ch.code
        sb.append(if (c in 0xFF01..0xFF5E) (c - 0xFEE0).toChar() else ch)
    }
    val n = sb.toString()
    val tokens = n.split(TOKEN_SPLIT).filter { it.isNotBlank() }
    // 去掉分隔符的紧凑名：分隔符只是书写差异（command-code / commandcode / command_code
    // 是同一个工具）。不分隔符时，"code" 这种短 token 会同时命中 codex / codebuddy 的
    // 反向前缀规则，把品牌认错，所以紧凑名相也要算作精确命中。
    val compact = n.filter { it.isLetterOrDigit() }
    var best: IconRule? = null
    var bestScore = 0
    for (rule in iconRules) {
        var score = 0
        for (alias in rule.aliases) {
            val cjk = alias.any { it.code > 0x2E80 }
            val hit = when {
                // exactOnly 的别名只认 token 完全相等（紧凑名也认），
                // 不参与前缀/子串/编辑距离匹配——短别名（cmc）走模糊匹配会误伤无关工具。
                rule.exactOnly && (tokens.any { it == alias } || compact == alias) -> 100
                rule.exactOnly -> 0
                tokens.any { it == alias } -> 100
                // 紧凑名相等 = 同一个品牌的不同书写（≥3 位，与其它短别名防护一致）
                alias.length >= 3 && compact == alias -> 100
                // 前缀命中：≥3 位别名任意后缀（gpt53）；数字结尾的 2 位别名（o1/o3/o4）允许胶连
                // 后缀（o4mini/o3pro），字母结尾的 2 位别名（hy/hf）不允许，避免误吃无关词
                tokens.any {
                    it.startsWith(alias) && (alias.length >= 3 || alias.last().isDigit())
                } -> 60
                // 反向前缀：名称里的 token 是别名的前缀（minimax-m2 → 命中 minimax）。
                // 这里必须要求 token 足够长（≥3），否则 2 字 token 会命中大批别名：
                // 「Mi Home」的 token "mi" 会同时命中 minimax 与 mimo，拿到错误的品牌 Logo 和配色。
                alias.length >= 3 && tokens.any { it.length >= 3 && alias.startsWith(it) } -> 60
                alias.length >= 3 && n.contains(alias) -> 50
                cjk && alias.length == 2 && n.contains(alias) -> 50
                alias.length >= 3 && tokens.any { it.length >= 3 && levenshtein(it, alias) <= 1 } -> 40
                else -> 0
            }
            if (hit > score) score = hit
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

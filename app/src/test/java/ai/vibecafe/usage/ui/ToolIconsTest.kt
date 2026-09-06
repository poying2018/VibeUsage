package ai.vibecafe.usage.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 工具/模型图标模糊匹配回归测试：
 * 大小写、全半角、日期/装饰后缀、胶连写法、中文产品名都必须命中正确品牌，绝不落通用兜底。
 */
class ToolIconsTest {

    private fun label(name: String): String = toolIconFor(name).label

    // ── 大小写 / 分隔符变体 ──

    @Test
    fun caseInsensitive() {
        assertEquals("Claude", label("CLAUDE-OPUS-4.8"))
        assertEquals("Claude", label("Claude Sonnet 4.5"))
        assertEquals("DeepSeek", label("DEEPSEEK-chat"))
        assertEquals("Zhipu", label("Glm-5"))
    }

    @Test
    fun separatorVariants() {
        assertEquals("OpenAI", label("GPT_4o_mini"))
        assertEquals("Claude", label("us.anthropic.claude-sonnet-4"))
        assertEquals("Claude", label("claude:opus"))
        assertEquals("Claude", label("claude[1m]"))
        assertEquals("Claude", label("claude（思考）"))
    }

    // ── 日期 / 装饰后缀 ──

    @Test
    fun dateSuffixes() {
        assertEquals("OpenAI", label("gpt-5-2025-08-07"))
        assertEquals("Claude", label("claude-opus-4.1-20250805"))
        assertEquals("OpenAI", label("o3-2025-04-16"))
        assertEquals("OpenAI", label("o4-mini-2025-04-16"))
        assertEquals("Gemini", label("gemini-2.5-pro-preview-06-05"))
    }

    @Test
    fun decorationSuffixes() {
        assertEquals("Qwen", label("qwen/qwen3:free"))
        assertEquals("DeepSeek", label("deepseek-chat-v3.1"))
        assertEquals("Kimi", label("kimi-k2-0905-preview"))
        assertEquals("Gemini", label("gemini-3.7-flash-lite-preview"))
        assertEquals("Grok", label("grok-code-fast-1"))
    }

    // ── 胶连（无分隔符）写法 ──

    @Test
    fun gluedForms() {
        assertEquals("OpenAI", label("gpt53"))
        assertEquals("OpenAI", label("o4mini"))
        assertEquals("OpenAI", label("o3pro"))
        assertEquals("Claude", label("claude3.7sonnet"))
        assertEquals("Gemini", label("gemini3pro"))
    }

    @Test
    fun fullWidthCharacters() {
        assertEquals("OpenAI", label("ＧＰＴ－５"))
        assertEquals("Claude", label("ＣＬＡＵＤＥ"))
    }

    // ── v2.18.1 新增品牌 ──

    @Test
    fun doubaoFamily() {
        assertEquals("Doubao", label("doubao-seed-1.6"))
        assertEquals("Doubao", label("豆包"))
        assertEquals("Doubao", label("火山方舟"))
        assertEquals("Doubao", label("seedance-1.0-pro"))
        assertEquals("Doubao", label("seedream-4.0"))
        assertEquals("Doubao", label("doubao-1.5-pro-32k"))
    }

    @Test
    fun minimaxFamily() {
        assertEquals("MiniMax", label("minimax-m2"))
        assertEquals("MiniMax", label("MiniMax"))
        assertEquals("MiniMax", label("MiniMax-M2-Pro"))
    }

    @Test
    fun agnesFamily() {
        assertEquals("Agnes", label("agnes-2.5-flash"))
        assertEquals("Agnes", label("Agnes-2.5-Pro"))
        assertEquals("Agnes", label("agnes-video-v2.0"))
    }

    @Test
    fun infiniAndChineseToolNames() {
        assertEquals("Infini", label("无问芯穹"))
        assertEquals("Infini", label("infini-ai"))
        assertEquals("Zhipu", label("智谱 GLM"))
        assertEquals("Zhipu", label("智谱清言"))
        assertEquals("Kimi", label("月之暗面 Kimi"))
        assertEquals("Qwen", label("阿里百炼"))
        assertEquals("Gemini", label("反重力"))
        assertEquals("Gemini", label("Antigravity"))
        assertEquals("Qoder", label("Qoder"))
        assertEquals("Windsurf", label("Windsurf"))
    }

    // ── 原有品牌回归 ──

    @Test
    fun existingBrandsStillMatch() {
        assertEquals("Claude", label("claude-fable-5"))
        assertEquals("Claude", label("claude-haiku-4.5"))
        assertEquals("OpenAI", label("gpt-5-codex"))
        assertEquals("OpenAI", label("chatgpt-4o-latest"))
        assertEquals("Copilot", label("GitHub Copilot"))
        assertEquals("Copilot", label("copilot-search-a"))
        assertEquals("Tencent", label("hunyuan-turbo"))
        assertEquals("LongCat", label("longcat-flash"))
        assertEquals("Xiaomi", label("mimo-v2"))
        assertEquals("Meta", label("llama-4-maverick"))
        assertEquals("Mistral", label("codestral-latest"))
        assertEquals("Image", label("sora-2"))
        assertEquals("Kiro", label("kiro-cli"))
        assertEquals("Tencent", label("workbuddy"))
        assertEquals("Tencent", label("codebuddy"))
        assertEquals("Qwen", label("qwen3-coder-480b-a35b-instruct"))
        assertEquals("DeepSeek", label("DeepSeek-V3.2-Exp"))
    }

    // ── 无品牌 → 兜底 ──

    @Test
    fun unknownFallsBackToGeneric() {
        val fallback = toolIconFor("exec-agent-a")
        assertEquals("AI", fallback.label)
        assertNotEquals("Claude", fallback.label)
        assertEquals("AI", toolIconFor("random-thing").label)
        // 字母结尾的两位别名（hy/hf）不允许前缀命中，避免误吃无关词
        assertEquals("AI", toolIconFor("hybrid-model").label)
        assertEquals("AI", toolIconFor("").label)
    }
}

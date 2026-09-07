package ai.vibecafe.usage.share

import ai.vibecafe.usage.R
import ai.vibecafe.usage.ui.formatCost
import ai.vibecafe.usage.ui.formatTokens
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 玻璃风格总结分享卡：1080x1350（3:4，社交平台友好）。
 * 纯 Canvas 绘制，不依赖 Compose/Backdrop，可直接在后台线程生成。
 * 版式自上而下：品牌头 → 范围+趋势 → 总消耗 → Tokens/IO → 指标块 → Top 应用/模型 → 月预测 → 底部标语。
 */
object ShareCard {

    private const val W = 1080
    private const val H = 1350

    // 暗色液态玻璃令牌（与 GlassTokens.DarkGlassPalette 一致）
    private val Page = 0xFF0B1019.toInt()
    private val InkHi = 0xFFE8EDF6.toInt()
    private val InkMid = 0xB3C7D2E6.toInt()
    private val InkLo = 0x59C7D2E6.toInt()
    private val Accent = 0xFF31E0CF.toInt()
    private val Violet = 0xFF6E8CFF.toInt()
    private val Down = 0xFFFF6E9B.toInt()

    /** Top 榜单里的一行：名称 + 消耗 + 占比（0~1）。 */
    data class ShareItem(val name: String, val cost: Double, val share: Float)

    data class Payload(
        val rangeLabel: String,
        val totalCost: Double,
        val totalTokens: Long,
        val toolCount: Int,
        val modelCount: Int,
        val sessionCount: Int,
        val monthProjected: Double?,
        val trendPercent: Float? = null,
        val topTools: List<ShareItem> = emptyList(),
        val topModels: List<ShareItem> = emptyList(),
        val deviceFilter: String? = null,
        val inputTokens: Long = 0L,
        val outputTokens: Long = 0L
    )

    fun generate(context: Context, payload: Payload): File {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val scale = W / 1080f

        // 思源黑体（与 App 内一致）；加载失败回退系统粗体
        val body = typeface(context, R.font.source_han_sans_700)
        val black = typeface(context, R.font.source_han_sans_900)

        // ---- 背景：纯黑底 + 三团径向光斑（呼应 App 内光斑流动背景）----
        c.drawColor(Page)
        drawOrb(c, cx = 160f, cy = 260f, r = 460f, color = 0xFF6E4A8F, alpha = 0.5f)
        drawOrb(c, cx = 980f, cy = 520f, r = 420f, color = 0xFF1B5E6B, alpha = 0.5f)
        drawOrb(c, cx = 520f, cy = 1180f, r = 500f, color = 0xFF3A3F7A, alpha = 0.42f)

        // ---- 品牌头 ----
        val logo = paint(Accent, 54f * scale, black)
        c.drawText("VibeUsage", 72f * scale, 128f * scale, logo)
        val dateStr = SimpleDateFormat("yyyy/M/d", Locale.US).format(Date())
        val date = paint(InkMid, 34f * scale, body)
        c.drawText(dateStr, W - 72f * scale - date.measureText(dateStr), 126f * scale, date)

        val line = Paint().apply { color = 0x26FFFFFF }
        c.drawRoundRect(72f * scale, 176f * scale, W - 72f * scale, 178f * scale, 2f, 2f, line)

        // ---- 范围标签 + 趋势（与仪表盘 TrendChip 同语义：升 teal / 降 rose）----
        val trend = payload.trendPercent?.let { trendText(it) }
        val trendPaint = if (payload.trendPercent != null && payload.trendPercent < 0f) {
            paint(Down, 36f * scale, body)
        } else {
            paint(Accent, 36f * scale, body)
        }
        val trendW = trend?.let { trendPaint.measureText(it) } ?: 0f
        val labelText = payload.deviceFilter?.let { "${payload.rangeLabel} · 设备：$it" } ?: payload.rangeLabel
        val labelPaint = paint(InkMid, 40f * scale, body)
        val labelMax = W - 144f * scale - trendW - (if (trend != null) 48f * scale else 0f)
        c.drawText(ellipsize(labelText, labelPaint, labelMax), 72f * scale, 280f * scale, labelPaint)
        trend?.let { c.drawText(it, W - 72f * scale - trendW, 280f * scale, trendPaint) }

        // ---- 总消耗（大数字）----
        val costText = formatCost(payload.totalCost)
        val hero = paint(InkHi, 148f * scale, black)
        c.drawText(costText, 66f * scale, 440f * scale, hero)
        val usd = paint(InkMid, 46f * scale, body)
        c.drawText("USD", 72f * scale + hero.measureText(costText) + 20f * scale, 440f * scale, usd)

        // ---- Tokens + 输入/输出细分 ----
        val tokens = paint(Accent, 56f * scale, body)
        c.drawText(formatTokens(payload.totalTokens) + " tokens", 72f * scale, 550f * scale, tokens)
        if (payload.inputTokens > 0L || payload.outputTokens > 0L) {
            val io = "In " + formatTokens(payload.inputTokens) + " · Out " + formatTokens(payload.outputTokens)
            val ioPaint = paint(InkMid, 30f * scale, body)
            c.drawText(io, W - 72f * scale - ioPaint.measureText(io), 550f * scale, ioPaint)
        }

        // ---- 月度预测 ----
        payload.monthProjected?.let { proj ->
            val p2 = paint(InkLo, 36f * scale, body)
            c.drawText("按日均预测整月 " + formatCost(proj), 72f * scale, 612f * scale, p2)
        }

        // ---- 指标块（玻璃片：半透明白 + rim 描边）----
        val items = listOf(
            payload.toolCount.toString() to "应用",
            payload.modelCount.toString() to "模型",
            payload.sessionCount.toString() to "会话"
        )
        val cardTop = 660f * scale
        val cardH = 240f * scale
        val gap = 28f * scale
        val cardW = (W - 144f * scale - gap * (items.size - 1)) / items.size
        val glassFill = Paint().apply { color = 0x14FFFFFF }
        val glassRim = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * scale
            color = 0x26FFFFFF
        }
        items.forEachIndexed { i, (num, name) ->
            val left = 72f * scale + i * (cardW + gap)
            c.drawRoundRect(left, cardTop, left + cardW, cardTop + cardH, 36f * scale, 36f * scale, glassFill)
            c.drawRoundRect(left, cardTop, left + cardW, cardTop + cardH, 36f * scale, 36f * scale, glassRim)
            val np = paint(InkHi, 76f * scale, black)
            c.drawText(num, left + cardW / 2 - np.measureText(num) / 2, cardTop + cardH * 0.55f, np)
            val lp = paint(InkMid, 36f * scale, body)
            c.drawText(name, left + cardW / 2 - lp.measureText(name) / 2, cardTop + cardH * 0.85f, lp)
        }

        // ---- Top 应用 / Top 模型 双栏榜单（带占比渐变条）----
        drawTopList(c, scale, "Top 应用", payload.topTools, 72f * scale, payload.topModels.isEmpty(), body, black)
        drawTopList(c, scale, "Top 模型", payload.topModels, 560f * scale, payload.topTools.isEmpty(), body, black)

        // ---- 底部点缀：渐变条 + 标语 ----
        val grad = Paint().apply {
            shader = android.graphics.LinearGradient(
                72f * scale, 0f, W - 72f * scale, 0f,
                Accent, Violet, android.graphics.Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(72f * scale, 1150f * scale, W - 72f * scale, 1158f * scale, 4f, 4f, grad)
        val foot = paint(InkLo, 34f * scale, body)
        val tag = "AI coding usage, in liquid glass"
        c.drawText(tag, W / 2f - foot.measureText(tag) / 2, 1230f * scale, foot)

        // ---- 落盘并返回 ----
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "vibeusage-share-${System.currentTimeMillis()}.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return out
    }

    /** 系统分享（PNG 经 FileProvider 暴露）。 */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享用量卡片"))
    }

    /** 单栏 Top 榜单：标题 + 最多 3 行（名称 / 占比 / 渐变条）。空列表不画。 */
    private fun drawTopList(
        c: Canvas,
        scale: Float,
        title: String,
        list: List<ShareItem>,
        x: Float,
        fullWidth: Boolean,
        body: Typeface?,
        black: Typeface?
    ) {
        if (list.isEmpty()) return
        val colW = if (fullWidth) (W - 144f * scale) else 448f * scale
        val tp = paint(InkLo, 34f * scale, body)
        c.drawText(title, x, 944f * scale, tp)
        val barFill = Paint().apply {
            shader = android.graphics.LinearGradient(
                x, 0f, x + colW, 0f,
                Accent, Violet, android.graphics.Shader.TileMode.CLAMP
            )
        }
        val barTrack = Paint().apply { color = 0x14FFFFFF }
        list.take(3).forEachIndexed { i, item ->
            val y = (984f + i * 52f) * scale
            val np = paint(InkHi, 30f * scale, body)
            val pt = paint(InkMid, 28f * scale, body)
            val pct = String.format(Locale.US, "%.0f%%", (item.share * 100f).coerceIn(0f, 999f))
            val pctW = pt.measureText(pct)
            val name = ellipsize(item.name, np, colW - pctW - 24f * scale)
            c.drawText(name, x, y, np)
            c.drawText(pct, x + colW - pctW, y, pt)
            val barY = y + 14f * scale
            c.drawRoundRect(x, barY, x + colW, barY + 8f * scale, 4f * scale, 4f * scale, barTrack)
            val w = (colW * item.share.coerceIn(0f, 1f)).coerceAtLeast(6f * scale)
            c.drawRoundRect(x, barY, x + w, barY + 8f * scale, 4f * scale, 4f * scale, barFill)
        }
    }

    /** 与 App 内 TrendChip 一致：升 ↗（teal）/ 降 ↘（rose），trend 为小数比例。 */
    internal fun trendText(trend: Float): String = when {
        trend >= 0.005f -> String.format(Locale.US, "↗ %+.0f%%", trend * 100f)
        trend <= -0.005f -> String.format(Locale.US, "↘ %+.0f%%", trend * 100f)
        else -> "→ 持平"
    }

    private fun ellipsize(text: String, p: Paint, max: Float): String =
        TextUtils.ellipsize(text, TextPaint(p), max, TextUtils.TruncateAt.END).toString()

    private fun typeface(context: Context, resId: Int): Typeface? =
        runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull()

    private fun paint(color: Int, size: Float, typeface: Typeface? = null) = Paint().apply {
        isAntiAlias = true
        this.color = color
        textSize = size
        if (typeface != null) {
            this.typeface = typeface
        } else {
            isFakeBoldText = true
        }
    }

    private fun drawOrb(c: Canvas, cx: Float, cy: Float, r: Float, color: Long, alpha: Float) {
        val a8 = (alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24
        val p = Paint().apply {
            shader = android.graphics.RadialGradient(
                cx, cy, r,
                intArrayOf(a8 or (color and 0xFFFFFF).toInt(), 0x00000000),
                floatArrayOf(0f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        c.drawCircle(cx, cy, r, p)
    }
}

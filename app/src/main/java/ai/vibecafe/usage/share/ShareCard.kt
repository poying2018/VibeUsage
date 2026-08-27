package ai.vibecafe.usage.share

import ai.vibecafe.usage.ui.formatCost
import ai.vibecafe.usage.ui.formatTokens
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 玻璃风格总结分享卡：1080x1350（3:4，社交平台友好）。
 * 纯 Canvas 绘制，不依赖 Compose/Backdrop，可直接在后台线程生成。
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

    data class Payload(
        val rangeLabel: String,
        val totalCost: Double,
        val totalTokens: Long,
        val toolCount: Int,
        val modelCount: Int,
        val sessionCount: Int,
        val monthProjected: Double?
    )

    fun generate(context: Context, payload: Payload): File {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // ---- 背景：纯黑底 + 三团径向光斑（呼应 App 内光斑流动背景）----
        c.drawColor(Page)
        drawOrb(c, cx = 160f, cy = 260f, r = 460f, color = 0xFF6E4A8F, alpha = 0.5f)
        drawOrb(c, cx = 980f, cy = 520f, r = 420f, color = 0xFF1B5E6B, alpha = 0.5f)
        drawOrb(c, cx = 520f, cy = 1180f, r = 500f, color = 0xFF3A3F7A, alpha = 0.42f)

        val scale = W / 1080f
        val logo = android.graphics.Paint().apply {
            isAntiAlias = true
            color = Accent
            textSize = 54f * scale
            isFakeBoldText = true
        }
        c.drawText("VibeUsage", 72f * scale, 128f * scale, logo)
        val sub = paint(InkMid, 34f * scale)
        val dateStr = SimpleDateFormat("yyyy/M/d", Locale.US).format(Date())
        c.drawText(dateStr, W - 72f * scale - paint(InkMid, 34f * scale).measureText(dateStr), 126f * scale, sub)

        // 分隔线（玻璃 rim 质感）
        val line = android.graphics.Paint().apply { color = 0x26FFFFFF }
        c.drawRoundRect(72f * scale, 176f * scale, W - 72f * scale, 178f * scale, 2f, 2f, line)

        // ---- 范围标签 ----
        val label = paint(InkMid, 40f * scale)
        c.drawText(payload.rangeLabel, 72f * scale, 280f * scale, label)

        // ---- 总消耗（大数字）----
        val hero = paint(InkHi, 148f * scale)
        c.drawText(formatCost(payload.totalCost), 66f * scale, 440f * scale, hero)
        val usd = paint(InkMid, 46f * scale)
        c.drawText("USD", 72f * scale + hero.measureText(formatCost(payload.totalCost)) + 20f * scale, 440f * scale, usd)

        // ---- Tokens ----
        val tokens = paint(Accent, 56f * scale)
        c.drawText(formatTokens(payload.totalTokens) + " tokens", 72f * scale, 550f * scale, tokens)

        // ---- 指标四宫格（玻璃片：半透明白 + rim 描边）----
        val items = listOf(
            payload.toolCount.toString() to "应用",
            payload.modelCount.toString() to "模型",
            payload.sessionCount.toString() to "会话"
        )
        val cardTop = 640f * scale
        val cardH = 240f * scale
        val gap = 28f * scale
        val cardW = (W - 144f * scale - gap * (items.size - 1)) / items.size
        val glassFill = android.graphics.Paint().apply { color = 0x14FFFFFF }
        val glassRim = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f * scale
            color = 0x26FFFFFF
        }
        items.forEachIndexed { i, (num, name) ->
            val left = 72f * scale + i * (cardW + gap)
            c.drawRoundRect(left, cardTop, left + cardW, cardTop + cardH, 36f * scale, 36f * scale, glassFill)
            c.drawRoundRect(left, cardTop, left + cardW, cardTop + cardH, 36f * scale, 36f * scale, glassRim)
            val np = paint(InkHi, 76f * scale)
            c.drawText(num, left + cardW / 2 - np.measureText(num) / 2, cardTop + cardH * 0.55f, np)
            val lp = paint(InkMid, 36f * scale)
            c.drawText(name, left + cardW / 2 - lp.measureText(name) / 2, cardTop + cardH * 0.85f, lp)
        }

        // ---- 月度预测 ----
        payload.monthProjected?.let { proj ->
            val p2 = paint(InkLo, 38f * scale)
            c.drawText("按日均预测整月 " + formatCost(proj), 72f * scale, 1020f * scale, p2)
        }

        // ---- 底部点缀：渐变条 + 标语 ----
        val grad = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                72f * scale, 0f, W - 72f * scale, 0f,
                Accent, Violet, android.graphics.Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(72f * scale, 1150f * scale, W - 72f * scale, 1158f * scale, 4f, 4f, grad)
        val foot = paint(InkLo, 34f * scale)
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

    private fun paint(color: Int, size: Float) = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = color
        textSize = size
        isFakeBoldText = true
    }

    private fun drawOrb(c: Canvas, cx: Float, cy: Float, r: Float, color: Long, alpha: Float) {
        val a8 = (alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24
        val p = android.graphics.Paint().apply {
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

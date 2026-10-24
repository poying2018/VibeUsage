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

    /**
     * 旧分享图保留多久（毫秒）再删。
     *
     * 系统分享面板弹出来后，目标 App 什么时候把图读完是不确定的；
     * 留 30 分钟足够覆盖「打开面板 → 选目标 → 发送」的整个过程，
     * 同时又不会让 cache 里堆太多图。
     */
    private const val SHARE_KEEP_MS = 30 * 60 * 1000L

    /**
     * 分享图文件名的序号后缀。
     *
     * 只用毫秒时间戳不够：连点两次「分享」可能落在同一毫秒，
     * 两个线程会算出同一个文件名，后写的把先写的覆盖掉，
     * 先发出去的那张图就变了内容。
     */
    private val shareSeq = java.util.concurrent.atomic.AtomicLong(0)

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

        // ---- 范围标签 + 趋势（与仪表盘 TrendChip 同语义：消耗上升走警示色 / 下降走品牌青）----
        val trend = payload.trendPercent?.let { trendText(it) }
        val trendPaint = if (payload.trendPercent != null && payload.trendPercent < 0f) {
            paint(Accent, 36f * scale, body)
        } else {
            paint(Down, 36f * scale, body)
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
        // 模型列字号小一档：模型 id 比应用名长得多（`MiniMax-M3.1-Flash-Preview`），
        // 同字号下尾巴被砍掉一大截看不出是哪个变体。仍按尾部截断——
        // 中间截断会切在词中间（`MiniMax-M…sh-Preview`），比原来还碎。
        //
        // 整栏布局：一列为空时另一列要占满整行，此时**起点也必须回到左边距**，
        // 否则 colW 变宽而 x 不变，右边界会冲出画布（内容被裁掉）。
        val toolsEmpty = payload.topTools.isEmpty()
        val modelsEmpty = payload.topModels.isEmpty()
        drawTopList(
            c, scale, "Top 应用", payload.topTools,
            x = if (toolsEmpty) 560f * scale else 72f * scale,
            fullWidth = modelsEmpty, body, black
        )
        drawTopList(
            c, scale, "Top 模型", payload.topModels,
            x = if (toolsEmpty) 72f * scale else 560f * scale,
            fullWidth = toolsEmpty, body, black,
            nameSize = 25f
        )

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
        // 只清理**过期**的旧分享图，不能每次生成就把上一次的删掉：
        // 系统分享面板打开后目标 App 可能还没读完那张图，
        // 删了对方收到的就是一张裂图；连点两次「保存到相册」也会把上一次的文件删没。
        val cutoff = System.currentTimeMillis() - SHARE_KEEP_MS
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith("vibeusage-share-") }
                ?.forEach { f -> if (f.lastModified() < cutoff) runCatching { f.delete() } }
        }
        val out = File(dir, "vibeusage-share-${System.currentTimeMillis()}-${shareSeq.incrementAndGet()}.png")
        try {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            // 压缩失败（OOM 等）时也要回收，否则 1080x1350 的位图会一直占着内存
            bmp.recycle()
        }
        return out
    }

    /** 系统分享（PNG 经 FileProvider 暴露）。 */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 从 Application 上下文发起时，chooser 必须带 NEW_TASK：
        // 否则系统直接抛 "Calling startActivity() from outside of an Activity context"，
        // 分享卡片永远弹不出来（内层 send intent 加不加这个 flag 都没用，
        // 真正被 startActivity 的是 chooser）。
        val chooser = Intent.createChooser(send, "分享用量卡片")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /**
     * 保存结果：给用户看的绝对路径 + 是否进了系统相册。
     *
     * @param path 用户能自己找到的文件位置（相册里能翻到时给「图库/相册」文案更直观）
     * @param inGallery true = 已写入 MediaStore，系统相册可见
     */
    data class SavedCard(val path: String, val inGallery: Boolean)

    /**
     * 把生成的卡片存进系统相册。
     *
     * Android 10（API 29）起用 MediaStore.Images 插入，**不需要任何权限**，
     * 图片会真的出现在系统相册里。API 26~28 没有 RELATIVE_PATH，
     * 写公共 Pictures 要 WRITE_EXTERNAL_STORAGE 运行时权限 —— 那得改
     * AndroidManifest 并在 UI 里引导授权，不划算；这几台机器退到应用专属
     * 外部目录（同样无需权限），只是相册里看不到，所以把 inGallery 置 false，
     * 让 UI 如实说明「已保存到应用目录，系统相册不可见」。
     */
    fun saveToGallery(context: Context, file: File): SavedCard {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, file)?.let { return SavedCard(it, true) }
            // MediaStore 失败（存储满 / 服务端异常）不该让功能整个失效：
            // 退到应用专属目录，至少图还在。
        }
        val dir = context.getExternalFilesDir("Pictures") ?: File(context.filesDir, "Pictures")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(dir, "vibeusage-card-$stamp.png")
        file.copyTo(target, overwrite = true)
        return SavedCard(target.absolutePath, false)
    }

    /** 写入 MediaStore 的 Pictures/VibeUsage 相册，返回可见路径。失败返回 null。 */
    private fun saveViaMediaStore(context: Context, file: File): String? = runCatching {
        val resolver = context.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "vibeusage-card-$stamp.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VibeUsage")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        } ?: return null
        // IS_PENDING=1 时条目对外不可见，写完必须清掉，否则相册里是个空壳
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri.toString()
    }.getOrNull()

    /** 单栏 Top 榜单：标题 + 最多 3 行（名称 / 占比 / 渐变条）。空列表不画。 */
    private fun drawTopList(
        c: Canvas,
        scale: Float,
        title: String,
        list: List<ShareItem>,
        x: Float,
        fullWidth: Boolean,
        body: Typeface?,
        black: Typeface?,
        nameSize: Float = 30f,
        nameTruncateAt: TextUtils.TruncateAt = TextUtils.TruncateAt.END
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
        // 只画「看得见」的占比：显示成 0% 的行对读者零信息量，却照样占一行高度，
        // 还会在进度条上留下一截 6px 的残条，看着像渲染 bug。
        // list 已按消耗降序，滤掉 0% 后 take(3) 自然由后面的条目补位。
        val rows = list.filter { (it.share * 100f) >= 0.5f }.take(3)
        rows.forEachIndexed { i, item ->
            // 行距 58：52 时上一行的进度条几乎贴到下一行文字（间隔仅 8px），
            // 榜单看起来糊成一团；58 留出 14px 呼吸，末行距底部分隔条仍有 28px
            val y = (984f + i * 58f) * scale
            val np = paint(InkHi, nameSize * scale, body)
            val pt = paint(InkMid, 28f * scale, body)
            val pct = String.format(Locale.US, "%.0f%%", (item.share * 100f).coerceIn(0f, 999f))
            val pctW = pt.measureText(pct)
            val name = ellipsize(item.name, np, colW - pctW - 24f * scale, nameTruncateAt)
            c.drawText(name, x, y, np)
            c.drawText(pct, x + colW - pctW, y, pt)
            val barY = y + 14f * scale
            c.drawRoundRect(x, barY, x + colW, barY + 8f * scale, 4f * scale, 4f * scale, barTrack)
            val w = (colW * item.share.coerceIn(0f, 1f)).coerceAtLeast(6f * scale)
            c.drawRoundRect(x, barY, x + w, barY + 8f * scale, 4f * scale, 4f * scale, barFill)
        }
    }

    /** 与 App 内 TrendChip 一致：升 ↗ / 降 ↘，trend 为小数比例。 */
    internal fun trendText(trend: Float): String = when {
        // NaN/Infinity 会格式化成 "+Infinity%" 这类溢出文案，统一按持平处理
        trend.isNaN() || trend.isInfinite() -> "→ 持平"
        trend >= 0.005f -> String.format(Locale.US, "↗ %+.0f%%", trend * 100f)
        trend <= -0.005f -> String.format(Locale.US, "↘ %+.0f%%", trend * 100f)
        else -> "→ 持平"
    }

    /**
     * 名称截断。
     *
     * 模型 id 很长（`MiniMax-M3.1-Flash-Preview-…`、`wb/global:step-5-preview`），
     * 尾部截断只剩前缀，看不出是哪个变体；改成**中间截断**，前后都留一段。
     * 应用名本来就短，不受影响。
     */
    private fun ellipsize(text: String, p: Paint, max: Float, at: TextUtils.TruncateAt = TextUtils.TruncateAt.END): String =
        TextUtils.ellipsize(text, TextPaint(p), max, at).toString()

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

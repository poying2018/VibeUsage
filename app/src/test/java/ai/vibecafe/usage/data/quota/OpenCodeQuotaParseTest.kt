package ai.vibecafe.usage.data.quota

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenCode Go 额度解析回归测试（对官方 OpenAPI 契约 /inference/go/v1/usage 的形状）。
 *
 * 要锁的口径：
 * 1. percent 是**已用**，画条要取反成 percentRemaining；
 * 2. rolling/weekly/monthly → 「5 小时 / 每周 / 每月窗口」的组名映射（带「窗口」才会被总览趋势选中）；
 * 3. 缺 percent 的窗口整档跳过 —— 缺省成 0 会画出一条「满额度」的假条，比不画更误导；
 * 4. status=rate-limited 要显式可见，不能只剩一条 0% 看不出原因。
 */
class OpenCodeQuotaParseTest {

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    /** 契约形状：三档窗口，percent 为已用，resetsAt 为 ISO8601。 */
    private val THREE_WINDOWS = json(
        """
        {"usage":{
          "rolling":{"status":"ok","percent":62,"resetsAt":"2026-11-04T18:30:00Z"},
          "weekly":{"status":"ok","percent":15,"resetsAt":"2026-11-08T16:00:00Z"},
          "monthly":{"status":"rate-limited","percent":100,"resetsAt":"2026-12-01T00:00:00Z"}
        }}
        """
    )

    @Test
    fun `maps rolling weekly monthly to window groups`() {
        val groups = OpenCode.buildGroups(THREE_WINDOWS)
        assertEquals(listOf("5 小时窗口", "每周窗口", "每月窗口"), groups.keys.toList())
    }

    // ─── Console 美元金额（/api/go/status）───

    /** Console 真实响应形状：micro-cents 是**字符串**下发，字段名与推理 API 不同。 */
    private val CONSOLE_STATUS = json(
        """
        {"product":"go","access":{
          "startsAt":"2026-11-05T10:28:06.000Z","endsAt":"2026-12-05T10:28:06.000Z",
          "meters":{
            "fiveHour":{"startsAt":"2026-11-05T23:21:41.000Z","resetsAt":"2026-11-06T04:21:41.000Z",
                        "limitMicroCents":"1200000000","usedMicroCents":"41416645"},
            "week":{"startsAt":"2026-11-02T00:00:00.000Z","resetsAt":"2026-11-09T00:00:00.000Z",
                    "limitMicroCents":"3000000000","usedMicroCents":"76761614"},
            "month":{"resetsAt":"2026-12-05T10:28:06.000Z",
                     "limitMicroCents":"6000000000","usedMicroCents":"76761614"}
          }}}
        """
    )

    @Test
    fun `console meters map onto the inference window names`() {
        val meters = OpenCode.parseMeters(CONSOLE_STATUS)
        // Console 的 fiveHour/week/month ↔ 推理 API 的 rolling/weekly/monthly
        assertNotNull(meters["rolling"])
        assertNotNull(meters["weekly"])
        assertNotNull(meters["monthly"])
    }

    @Test
    fun `micro cents become dollars`() {
        val meters = OpenCode.parseMeters(CONSOLE_STATUS)
        assertEquals(12.0, meters["rolling"]!!.limit, 0.001)
        assertEquals(60.0, meters["monthly"]!!.limit, 0.001)
    }

    @Test
    fun `counts switch to absolute dollars when console data is present`() {
        // buildGroups 走全局汇率：显式钉死 7.2，与默认兜底值解耦
        val prev = ai.vibecafe.usage.data.FxRates.usdToCny
        ai.vibecafe.usage.data.FxRates.usdToCny = 7.2
        try {
            val meters = OpenCode.parseMeters(CONSOLE_STATUS)
            val bar = OpenCode.buildGroups(THREE_WINDOWS, meters).getValue("每月窗口").first()
            // 已用 ¥5.53 / 上限 ¥432 —— 这才是「用了多少钱」的直接答案；
            // monthly 在 THREE_WINDOWS 里是 rate-limited，所以尾部还带「已限流」
            assertEquals("¥5.53 / ¥432.00 · 已限流", bar.counts)
        } finally {
            ai.vibecafe.usage.data.FxRates.usdToCny = prev
        }
    }

    @Test
    fun `rate limited marker survives when money is shown`() {
        val meters = OpenCode.parseMeters(CONSOLE_STATUS)
        // THREE_WINDOWS 的 monthly 是 rate-limited：只写金额会把限流原因丢掉
        val bar = OpenCode.buildGroups(THREE_WINDOWS, meters).getValue("每月窗口").first()
        assertTrue("should keep the marker: ${bar.counts}", bar.counts!!.contains("已限流"))
    }

    @Test
    fun `counts fall back to percentage when console data is missing`() {
        val bar = OpenCode.buildGroups(THREE_WINDOWS, OpenCode.Meters.EMPTY).getValue("5 小时窗口").first()
        assertEquals("占月额度 12%", bar.counts)
    }

    @Test
    fun `zero limit meters are skipped`() {
        val status = json(
            """{"access":{"meters":{"month":{"limitMicroCents":"0","usedMicroCents":"0"}}}}"""
        )
        assertNull(OpenCode.parseMeters(status)["monthly"])
    }

    @Test
    fun `missing access returns no meters instead of throwing`() {
        assertEquals(OpenCode.Meters.EMPTY, OpenCode.parseMeters(json("""{"product":null}""")))
        assertEquals(OpenCode.Meters.EMPTY, OpenCode.parseMeters(null))
    }

    @Test
    fun `small amounts keep enough decimals in cny`() {
        // 显式传汇率 7.2：不断言全局默认值，单测顺序不影响结果
        assertEquals("¥2.98", OpenCode.cny(0.41416645, 7.2))
        assertEquals("¥0.023", OpenCode.cny(0.0032, 7.2))
        assertEquals("¥432.00", OpenCode.cny(60.0, 7.2))
        assertEquals("¥0.00", OpenCode.cny(0.0, 7.2))
    }

    @Test
    fun `account shows plan and period end`() {
        assertEquals("Go · 12-05 到期", OpenCode.accountOf(CONSOLE_STATUS))
    }

    @Test
    fun `account is null without console status`() {
        assertNull(OpenCode.accountOf(null))
        assertNull(OpenCode.accountOf(json("""{"access":{}}""")))
    }

    @Test
    fun `percent is used and must be inverted for remaining`() {
        val bar = OpenCode.buildGroups(THREE_WINDOWS).getValue("5 小时窗口").first()
        assertEquals(62, bar.usedPercent)
        assertEquals(38, bar.percentRemaining)
    }

    @Test
    fun `rate-limited status is shown in counts`() {
        val bar = OpenCode.buildGroups(THREE_WINDOWS).getValue("每月窗口").first()
        assertEquals("已限流", bar.counts)
        assertEquals(0, bar.percentRemaining)
    }

    @Test
    fun `reset uses the ISO resetsAt field`() {
        // 未来时刻（相对测试编写时的 2026-11-04）→ 「… 重置 · X后」
        val bar = OpenCode.buildGroups(THREE_WINDOWS).getValue("每周窗口").first()
        assertNotNull(bar.reset)
        assertTrue("重置文案应以 11-09 00:00 开头，实际=${bar.reset}", bar.reset!!.startsWith("11-09 00:00 重置"))
    }

    @Test
    fun `missing percent skips that window instead of drawing a fake full bar`() {
        val obj = json("""{"usage":{"weekly":{"status":"ok","resetsAt":"2026-11-08T16:00:00Z"}}}""")
        // 没有 percent → 整档不出现（而不是画成已用 0%、满额度）
        assertTrue(OpenCode.buildGroups(obj).isEmpty())
    }

    @Test
    fun `windows arrive independently and out-of-order keys keep display order`() {
        val obj = json("""{"usage":{"monthly":{"percent":50,"status":"ok"},"rolling":{"percent":10,"status":"ok"}}}""")
        // monthly 在 JSON 里排前面，但组序仍按固定窗口表：5 小时 → 每月；weekly 缺席不占位
        assertEquals(listOf("5 小时窗口", "每月窗口"), OpenCode.buildGroups(obj).keys.toList())
    }

    @Test
    fun `absent usage object yields empty groups not a crash`() {
        assertTrue(OpenCode.buildGroups(json("""{}""")).isEmpty())
        assertTrue(OpenCode.buildGroups(json("""{"usage":null}""")).isEmpty())
    }

    @Test
    fun `percent may come as a string and still parse`() {
        val obj = json("""{"usage":{"rolling":{"percent":"25.5","status":"ok"}}}""")
        val bar = OpenCode.buildGroups(obj).getValue("5 小时窗口").first()
        assertEquals(25, bar.usedPercent)
        assertEquals(75, bar.percentRemaining)
        assertNull(bar.reset)
    }

    // ─── 窗口口径归一：三档分母不同（月额度的 20% / 50% / 100%）───

    @Test
    fun `rolling and weekly windows also report their share of the monthly quota`() {
        // 官方文档：5h = 月额度的 20%，weekly = 50%，monthly = 100%。
        // 所以 rolling 用掉 60% 只等于月额度的 12%，不换算的话三档没法放一起比。
        val groups = OpenCode.buildGroups(THREE_WINDOWS)
        val rolling = groups.getValue("5 小时窗口").first()
        val weekly = groups.getValue("每周窗口").first()
        val monthly = groups.getValue("每月窗口").first()
        assertEquals("占月额度 12%", rolling.counts)
        assertEquals("占月额度 8%", weekly.counts)
        // 整月窗口本身就是月额度，写「占月额度 100%」是废话（它只保留限流状态）
        assertEquals("已限流", monthly.counts)
    }

    @Test
    fun `tiny monthly share keeps one decimal instead of rounding to zero`() {
        // 5% × 20% = 1.0%；1% × 20% = 0.2% —— 四舍五入成 0% 会看起来像没消耗
        val obj = json("""{"usage":{"rolling":{"percent":1,"status":"ok"}}}""")
        assertEquals("占月额度 0.2%", OpenCode.buildGroups(obj).getValue("5 小时窗口").first().counts)
    }

    @Test
    fun `rate-limited and monthly share are both shown when both apply`() {
        val obj = json("""{"usage":{"weekly":{"percent":100,"status":"rate-limited"}}}""")
        assertEquals(
            "已限流 · 占月额度 50%",
            OpenCode.buildGroups(obj).getValue("每周窗口").first().counts
        )
    }

    // ─── 模型目录：把 /models 的有效字段都用起来 ───

    /** 取自真实 /go/v1/models 响应的裁剪版（保留所有会用到的字段）。 */
    private val MODELS = json(
        """
        {"object":"list","data":[
          {"id":"deepseek-v4.1-flash","name":"DeepSeek V4.1 Flash","type":"chat",
           "family":"deepseek-flash","open_weights":true,"knowledge":"2025-05",
           "release_date":"2026-09-10",
           "description":"DeepSeek V4.1 Flash model for reasoning and agentic coding",
           "modalities":{"input":["text","image/png"],"output":["text"]},
           "capabilities":{"tools":{"supported":true},"reasoning":{"supported":true,"effort":["low","high"]},
                            "structured_output":true},
           "limit":{"context":1000000,"output":384000},
           "cost":{"text":{"input":{"price":0.15,"cache":{"read":0.003}},"output":{"price":0.6}},
                   "multipliers":[{"when":{"utc_days":{"in":["mon","tue","wed","thu","fri"]},
                                            "any":[{"all":[{"utc_hours":{"gte":"01:00"}},{"utc_hours":{"lt":"04:00"}}]}]},
                                   "factor":2}]}},
          {"id":"beam-free","name":"Beam Free","type":"chat","open_weights":true,
           "release_date":"2026-10-05",
           "modalities":{"input":["text"],"output":["text"]},
           "capabilities":{"tools":{"supported":true},"reasoning":{"supported":true}},
           "limit":{"context":1048576,"output":131072},
           "cost":{"text":{"input":{"price":0,"cache":{"read":0}},"output":{"price":0}}}},
          {"id":"no-name-model","object":"model","created":1793885333,"owned_by":"opencode","name":"",
           "limit":{"context":262144},"cost":{"text":{"input":{"price":2},"output":{"price":10}}}}
        ]}
        """
    )

    @Test
    fun `model catalog parses name limits and pricing`() {
        val models = OpenCode.parseModels(MODELS)
        assertEquals(3, models.size)
        val flash = models.first { it.id == "deepseek-v4.1-flash" }
        assertEquals("DeepSeek V4.1 Flash", flash.name)
        assertEquals(1_000_000L, flash.contextLimit)
        assertEquals(384_000L, flash.outputLimit)
        assertEquals(0.15, flash.inputPrice!!, 1e-9)
        assertEquals(0.003, flash.cacheReadPrice!!, 1e-9)
        assertEquals(0.6, flash.outputPrice!!, 1e-9)
        assertEquals("2026-09-10", flash.releaseDate)
        assertEquals("2025-05", flash.knowledge)
    }

    @Test
    fun `capability flags read from nested supported objects`() {
        val flash = OpenCode.parseModels(MODELS).first { it.id == "deepseek-v4.1-flash" }
        assertTrue("reasoning 在契约里是 {supported:true}", flash.reasoning)
        assertTrue("tools 在契约里是 {supported:true}", flash.tools)
        assertTrue("modalities.input 含 image/* → 视觉", flash.vision)
        assertTrue(flash.openWeights)
    }

    @Test
    fun `free model keeps zero prices instead of turning them into null`() {
        val free = OpenCode.parseModels(MODELS).first { it.id == "beam-free" }
        // 0 是「免费」这个有效信息，用 null 会和「不知道多少钱」混为一谈
        assertEquals(0.0, free.inputPrice!!, 1e-9)
        assertEquals(0.0, free.outputPrice!!, 1e-9)
        assertEquals(1_048_576L, free.contextLimit)
    }

    @Test
    fun `peak multiplier is captured and only when above 1`() {
        assertEquals(2.0, OpenCode.parseModels(MODELS).first { it.id == "deepseek-v4.1-flash" }.peakFactor!!, 1e-9)
        // 没有 multipliers 的模型：null，UI 就不显示浮动定价标签
        assertNull(OpenCode.parseModels(MODELS).first { it.id == "beam-free" }.peakFactor)
    }

    @Test
    fun `blank name and missing limit still parse without crashing`() {
        val m = OpenCode.parseModels(MODELS).first { it.id == "no-name-model" }
        // name 为空串时保留原值（UI 侧回落显示 id），不要因为空名丢掉整个模型
        assertEquals("", m.name)
        assertNull(m.outputLimit)
        assertEquals(2.0, m.inputPrice!!, 1e-9)
        assertEquals(10.0, m.outputPrice!!, 1e-9)
    }

    // ─── 高峰时段：UTC 条件 → 北京时间人话 ───

    @Test
    fun `weekday peak hours convert from UTC to Beijing time`() {
        // 真实数据：UTC 工作日 01:00–04:00 与 06:00–10:00 → 北京时间 +8
        val cond = json(
            """
            {"utc_days":{"in":["mon","tue","wed","thu","fri"]},
             "any":[{"all":[{"utc_hours":{"gte":"01:00"}},{"utc_hours":{"lt":"04:00"}}]},
                    {"all":[{"utc_hours":{"gte":"06:00"}},{"utc_hours":{"lt":"10:00"}}]}]}
            """
        )
        assertEquals("工作日 09:00–12:00、14:00–18:00", OpenCode.peakWindowOf(cond))
    }

    @Test
    fun `date-based multiplier reads as a start date`() {
        assertEquals("2026-12-01 起", OpenCode.peakWindowOf(json("""{"date":{"gte":"2026-12-01"}}""")))
    }

    @Test
    fun `peak window wraps across midnight when shifted`() {
        // UTC 16:00–20:00 → 北京时间 00:00–04:00（跨零点回绕，不能出 "24:00"）
        val cond = json("""{"any":[{"all":[{"utc_hours":{"gte":"16:00"}},{"utc_hours":{"lt":"20:00"}}]}]}""")
        assertEquals("00:00–04:00", OpenCode.peakWindowOf(cond))
    }

    @Test
    fun `single weekday list is spelled out and unrecognized shapes return null`() {
        assertEquals("周一", OpenCode.peakWindowOf(json("""{"utc_days":{"in":["mon"]}}""")))
        // 认不出的结构返回 null，让 UI 退化成只显示倍率，而不是显示一段错的时间
        assertNull(OpenCode.peakWindowOf(json("""{"utc_hours":{"gte":"01:00"}}""")))
    }

    @Test
    fun `peak window flows through the model parser`() {
        val flash = OpenCode.parseModels(MODELS).first { it.id == "deepseek-v4.1-flash" }
        assertEquals(2.0, flash.peakFactor!!, 1e-9)
        assertEquals("工作日 09:00–12:00", flash.peakWindow)
        // description 也要带出来
        assertNotNull(flash.description)
    }

    @Test
    fun `catalog is sorted by id and tolerates malformed entries`() {
        val ids = OpenCode.parseModels(MODELS).map { it.id }
        assertEquals(ids.sorted(), ids)
        // 缺 id 的条目直接跳过；data 不是数组 / 顶层为 null 都返回空表
        val noId = json("""{"data":[{"name":"x"},{"id":"ok","name":"OK"}]}""")
        assertEquals(listOf("ok"), OpenCode.parseModels(noId).map { it.id })
        assertTrue(OpenCode.parseModels(json("""{"data":"nope"}""")).isEmpty())
        assertTrue(OpenCode.parseModels(null).isEmpty())
    }
}

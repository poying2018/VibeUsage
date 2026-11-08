package ai.vibecafe.usage.data.quota

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OpenCode Console 登录通道的解析 / 编解码回归测试。
 *
 * 形状取自 Console 实际响应（`/api/go/status` 的 `access.meters.*`，
 * micro-cents 为**字符串**下发）。2026-11-06 真机验证：同一把 Key 读到
 * fiveHour limit 1200000000（=$12）、week 3000000000（=$30）、month 6000000000（=$60），
 * 与官方文档的 20% / 50% / 100% 对得上。
 *
 * 要锁的口径：
 * 1. micro-cents 字符串 → 美元（1e8 = $1）；
 * 2. 三档窗口名与 Key 通道保持一致（带「窗口」，总览趋势才认）；
 * 3. limit 缺失 / 为 0 的窗口整档跳过（0/0 会被读成满额度）；
 * 4. 没有 access（未订套餐）抛错，而不是空数据；
 * 5. 凭据前缀与编解码：oc_sk_ Key 不能被误当登录态；
 * 6. OAuth 路径的百分比由金额比算出，与 Key 路径的推理百分比同刻度。
 */
class OpenCodeConsoleTest {

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    /** Console 真实响应形状（字段名与推理 API 不同：fiveHour/week/month）。 */
    private val GO_STATUS = json(
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
    fun `console status parses string micro cents into dollars`() {
        val meters = OpenCode.parseMeters(GO_STATUS)
        assertEquals(12.0, meters["rolling"]!!.limit, 0.001)
        assertEquals(30.0, meters["weekly"]!!.limit, 0.001)
        assertEquals(60.0, meters["monthly"]!!.limit, 0.001)
        assertEquals(0.41416645, meters["rolling"]!!.used, 0.0001)
    }

    @Test
    fun `three meters map to the same window names as the key channel`() {
        val groups = OpenCodeConsole.buildUsage(GO_STATUS).groups
        assertEquals(listOf("5 小时窗口", "每周窗口", "每月窗口"), groups.keys.toList())
    }

    @Test
    fun `counts show used over limit in dollars`() {
        // buildUsage 走全局汇率：这里显式钉死 7.2，与默认兜底值解耦
        val prev = ai.vibecafe.usage.data.FxRates.usdToCny
        ai.vibecafe.usage.data.FxRates.usdToCny = 7.2
        try {
            val groups = OpenCodeConsole.buildUsage(GO_STATUS).groups
            assertEquals("¥2.98 / ¥86.40", groups.getValue("5 小时窗口").first().counts)
            assertEquals("¥5.53 / ¥216.00", groups.getValue("每周窗口").first().counts)
            assertEquals("¥5.53 / ¥432.00", groups.getValue("每月窗口").first().counts)
        } finally {
            ai.vibecafe.usage.data.FxRates.usdToCny = prev
        }
    }

    @Test
    fun `percentages are computed from the money ratio`() {
        val groups = OpenCodeConsole.buildUsage(GO_STATUS).groups
        // 0.414/12 = 3.45% → 3；0.768/30 = 2.56% → 3；0.768/60 = 1.28% → 1
        assertEquals(3, groups.getValue("5 小时窗口").first().usedPercent)
        assertEquals(1, groups.getValue("每月窗口").first().usedPercent)
    }

    @Test
    fun `overdraft is clamped to full`() {
        val status = json(
            """
            {"product":"go","access":{"meters":{"month":
              {"limitMicroCents":"1000000000","usedMicroCents":"1300000000"}}}}
            """
        )
        val bar = OpenCodeConsole.buildUsage(status).groups.getValue("每月窗口").first()
        assertEquals(100, bar.usedPercent)
        assertEquals(0, bar.percentRemaining)
    }

    @Test
    fun `zero or missing limit windows are skipped entirely`() {
        val status = json(
            """
            {"product":"go","access":{"meters":{
              "fiveHour":{"limitMicroCents":"0","usedMicroCents":"0"},
              "week":{"usedMicroCents":"1000000"},
              "month":{"limitMicroCents":"6000000000","usedMicroCents":"100000000"}
            }}}
            """
        )
        assertEquals(listOf("每月窗口"), OpenCodeConsole.buildUsage(status).groups.keys.toList())
    }

    @Test
    fun `no access throws a readable reason instead of empty data`() {
        val e = runCatching { OpenCodeConsole.buildUsage(json("""{"product":null}""")) }
            .exceptionOrNull()
        assertNotNull(e)
        assertEquals("Console 未返回额度窗口", e!!.message)
    }

    @Test
    fun `account shows plan label`() {
        assertEquals("Go · 12-05 到期", OpenCodeConsole.buildUsage(GO_STATUS).account)
    }

    @Test
    fun `api key credential is not mistaken for a login credential`() {
        assertFalse(OpenCodeConsole.isConsoleCred("oc_sk_abcdefghijkl"))
        assertNull(OpenCodeConsole.decode("oc_sk_abcdefghijkl"))
    }

    @Test
    fun `credential round trip keeps access refresh expiry client`() {
        val t = OpenCodeConsole.Tokens(
            access = "access-token", refresh = "refresh-token",
            expiresAt = 1_900_000_000_000L, clientId = "opencode"
        )
        assertEquals(t, OpenCodeConsole.decode(OpenCodeConsole.encode(t)))
    }

    @Test
    fun `corrupt credential decodes to null instead of throwing`() {
        assertNull(OpenCodeConsole.decode(OpenCodeConsole.PREFIX + "not-json"))
        assertNull(OpenCodeConsole.decode(OpenCodeConsole.PREFIX + "{}"))
    }
}

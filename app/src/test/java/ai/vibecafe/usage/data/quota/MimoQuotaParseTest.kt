package ai.vibecafe.usage.data.quota

import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Mimo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 小米 MiMo 额度解析。
 *
 * 三份 LIVE 样本是 2026-09-22 用真实登录态直连 platform.xiaomimimo.com 的 api 接口抓回来的原文：
 * 前两份是**未订阅**账号（按量余额 ¥4.60），USAGE_LIVE / DETAIL_LIVE 是**已订阅 Lite** 账号，
 * 因此 percent 是 0–1 小数、条目键叫 name、currentPeriodEnd 是「yyyy-MM-dd HH:mm:ss」北京时间
 * ——这三点都是真值教出来的，不要再按猜测改回去。
 */
class MimoQuotaParseTest {

    private val BALANCE_UNSUB =
        """{"code":0,"message":"","data":{"balance":"4.60","frozenBalance":"0.00","currency":"CNY","overdraftLimit":"0.00","remainingOverdraftLimit":"0.00","giftBalance":"0.00","cashBalance":"4.60"}}"""

    private val USAGE_UNSUB =
        """{"code":0,"message":"","data":{"monthUsage":{"percent":0,"items":null},"usage":null}}"""

    private val DETAIL_UNSUB =
        """{"code":0,"message":"","data":{"planCode":null,"planName":null,"currentPeriodEnd":null,"expired":null,"enableAutoRenew":false,"autoRenewDiscount":"0.88","hasAutoRenewSubscribed":false,"clawEnabled":false,"clawPeriodEnd":null,"clawPurchased":false}}"""

    /** 已订阅 Lite：percent 0.033 = 3.3%，used/limit 才是可核对的真量。 */
    private val USAGE_LIVE =
        """{"code":0,"message":"","data":{"monthUsage":{"percent":0.033,"items":[{"name":"month_total_token","used":135244472,"limit":4100000000,"percent":0.033}]},"usage":{"percent":0.03,"items":[{"name":"plan_total_token","used":135244472,"limit":4100000000,"percent":0.03},{"name":"compensation_total_token","used":0,"limit":0,"percent":0}]}}}"""

    private val DETAIL_LIVE =
        """{"code":0,"message":"","data":{"planCode":"lite","planName":"Lite","currentPeriodEnd":"2026-10-22 23:59:59","expired":false,"enableAutoRenew":false,"autoRenewDiscount":null,"hasAutoRenewSubscribed":true,"clawEnabled":false,"clawPeriodEnd":null,"clawPurchased":false}}"""

    // ─── 余额 ───

    @Test
    fun `live balance renders one full bar with cash split`() {
        val bars = Mimo.parseBalance(BALANCE_UNSUB)
        assertEquals(1, bars.size)
        assertEquals("CNY 余额 ¥4.60", bars[0].label)
        assertEquals(100, bars[0].percentRemaining)
        // 赠费/冻结/透支全为 0 时不占位，只留现金
        assertEquals("现金 ¥4.60", bars[0].counts)
    }

    @Test
    fun `gift frozen and overdraft appear in counts when non-zero`() {
        val bars = Mimo.parseBalance(
            """{"code":0,"data":{"balance":"14.60","cashBalance":"4.60","giftBalance":"10.00","frozenBalance":"1.20","overdraftLimit":"5.00","currency":"CNY"}}"""
        )
        assertEquals("现金 ¥4.60 · 赠费 ¥10.00 · 冻结 ¥1.20 · 可透支 ¥5.00", bars[0].counts)
    }

    @Test
    fun `usd balance uses dollar sign and missing balance yields nothing`() {
        assertEquals(
            "USD 余额 \$1.20",
            Mimo.parseBalance("""{"code":0,"data":{"balance":"1.20","cashBalance":"1.20","currency":"USD"}}""").first().label
        )
        assertTrue(Mimo.parseBalance("""{"code":0,"data":{}}""").isEmpty())
        assertTrue(Mimo.parseBalance("""{"code":0,"message":""}""").isEmpty())
    }

    // ─── 套餐额度：真订阅样本 ───

    @Test
    fun `fraction percent must move - 0_033 is 3 percent used not 0`() {
        val bars = Mimo.parsePlan(USAGE_LIVE, DETAIL_LIVE)
        val plan = bars.first { it.label == "当前套餐用量" }
        // 135244472 / 4100000000 = 3.3% → 已用 3，剩余 97
        assertEquals(3, plan.usedPercent!!.toInt())
        assertEquals(97, plan.percentRemaining)
        assertEquals("1.4亿 / 41亿", plan.counts)
    }

    @Test
    fun `plan rows come from both usage and monthUsage item sets`() {
        val labels = Mimo.parsePlan(USAGE_LIVE, DETAIL_LIVE).map { it.label }
        // 套餐周期额度在 usage.items，自然月累计在 monthUsage.items，两处都要出
        assertEquals(listOf("当前套餐用量", "本月累计用量"), labels)
    }

    @Test
    fun `reset line parses beijing datetime without timezone marker`() {
        val plan = Mimo.parsePlan(USAGE_LIVE, DETAIL_LIVE).first { it.label == "当前套餐用量" }
        val reset = plan.reset
        assertTrue("currentPeriodEnd 没解析出来", reset != null)
        assertTrue("reset=$reset", reset!!.startsWith("10-22 23:59 重置"))
        // 只有套餐周期条带重置时间，累计条不重复挂
        assertNull(Mimo.parsePlan(USAGE_LIVE, DETAIL_LIVE).first { it.label == "本月累计用量" }.reset)
    }

    @Test
    fun `zero-quota compensation row is dropped instead of showing an empty bar`() {
        // 真值里 compensation_total_token 的 used 与 limit 都是 0：没有这份额度就别占一行
        assertTrue(Mimo.parsePlan(USAGE_LIVE, DETAIL_LIVE).none { it.label == "补偿积分" })
        val withComp = Mimo.parsePlan(
            USAGE_LIVE.replace(
                """{"name":"compensation_total_token","used":0,"limit":0,"percent":0}""",
                """{"name":"compensation_total_token","used":50000000,"limit":2000000000,"percent":0.025}"""
            ),
            DETAIL_LIVE
        )
        val comp = withComp.first { it.label == "补偿积分" }
        assertEquals(2, comp.usedPercent!!.toInt())
        assertEquals("5000万 / 20亿", comp.counts)
    }

    @Test
    fun `unsubscribed account draws no fake plan bar`() {
        assertTrue(Mimo.parsePlan(USAGE_UNSUB, DETAIL_UNSUB).isEmpty())
        assertEquals("未订阅 Token Plan", Mimo.accountOf(DETAIL_UNSUB))
    }

    @Test
    fun `unsubscribed still reports rows when detail fetch failed but items exist`() {
        val bars = Mimo.parsePlan(USAGE_LIVE, null)
        assertTrue(bars.isNotEmpty())
        assertEquals(97, bars.first { it.label == "当前套餐用量" }.percentRemaining)
    }

    @Test
    fun `percent field alone is scaled when no absolute amounts come back`() {
        // 服务端只给比例不给量：0.5 是 50%，而 37 这种历史形态按 0–100 解释
        val half = Mimo.parsePlan(
            """{"code":0,"data":{"usage":{"items":[{"name":"plan_total_token","percent":0.5}]}}}""",
            DETAIL_LIVE
        )
        assertEquals(50, half.first().percentRemaining)
        val legacy = Mimo.parsePlan(
            """{"code":0,"data":{"usage":{"items":[{"name":"plan_total_token","percent":37}]}}}""",
            DETAIL_LIVE
        )
        assertEquals(63, legacy.first().percentRemaining)
    }

    @Test
    fun `used and limit win over a stale percent field`() {
        val bars = Mimo.parsePlan(
            """{"code":0,"data":{"usage":{"items":[{"name":"plan_total_token","used":800,"limit":1000,"percent":0.03}]}}}""",
            DETAIL_LIVE
        )
        assertEquals(80, bars.first().usedPercent!!.toInt())
    }

    @Test
    fun `unknown item name still surfaces with its own name instead of being dropped`() {
        val bars = Mimo.parsePlan(
            """{"code":0,"data":{"usage":{"items":[{"name":"claw_total_token","used":3,"limit":10}]}}}""",
            DETAIL_LIVE
        )
        assertEquals("claw_total_token", bars.first().label)
        assertEquals(70, bars.first().percentRemaining)
    }

    @Test
    fun `row with neither percent nor amounts is skipped`() {
        assertTrue(
            Mimo.parsePlan("""{"code":0,"data":{"usage":{"items":[{"name":"plan_total_token"}]}}}""", DETAIL_LIVE).isEmpty()
        )
    }

    // ─── 胶囊与错误 ───

    @Test
    fun `plan name pill carries expiry and auto-renew flags`() {
        assertEquals("Lite", Mimo.accountOf(DETAIL_LIVE))
        assertEquals(
            "Pro · 已过期 · 自动续订",
            Mimo.accountOf("""{"code":0,"data":{"planName":"Pro","expired":true,"enableAutoRenew":true}}""")
        )
    }

    @Test
    fun `unreadable detail response stays silent instead of claiming unsubscribed`() {
        assertNull(Mimo.accountOf("<html>502 Bad Gateway</html>"))
        assertNull(Mimo.accountOf("""{"code":500,"message":"boom"}"""))
    }

    @Test
    fun `business error code surfaces message`() {
        try {
            Mimo.parseBalance("""{"code":500,"message":"系统繁忙"}""")
            fail("expected QuotaException")
        } catch (e: ExtraQuotaApi.QuotaException) {
            assertEquals(500, e.code)
            assertTrue(e.message!!.contains("小米 MiMo 错误 500：系统繁忙"))
        }
    }

    @Test
    fun `code 401 in body becomes credential hint`() {
        try {
            Mimo.parseBalance("""{"code":401,"message":"not login"}""")
            fail("expected QuotaException")
        } catch (e: ExtraQuotaApi.QuotaException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("登录已失效"))
        }
    }

    // ─── 凭据归一 ───

    @Test
    fun `two boxes as separate userId and token`() {
        val (u, t) = Mimo.normalizeCreds("991114711", "abc123+/=")
        assertEquals("991114711", u)
        assertEquals("abc123+/=", t)
    }

    @Test
    fun `whole cookie pasted into either box is split out`() {
        val cookie = "userId=991114711; xiaomichatbot_ph=\"x\"; " +
            "api-platform_serviceToken=\"/548h+Y=\"; api-platform_slh=\"y\""
        for (paste in listOf(cookie, "  $cookie  ")) {
            val (u, t) = Mimo.normalizeCreds(paste, "")
            assertEquals("991114711", u)
            assertEquals("/548h+Y=", t)
        }
        // 反串（整条 Cookie 粘在第二个格子）
        val (u2, t2) = Mimo.normalizeCreds("", cookie)
        assertEquals("991114711", u2)
        assertEquals("/548h+Y=", t2)
    }

    @Test
    fun `token box keeps working when the name is absent`() {
        val (u, t) = Mimo.normalizeCreds("991114711", "\"/548hYF2Whe+/=\"")
        assertEquals("991114711", u)
        assertEquals("/548hYF2Whe+/=", t)
    }

    @Test
    fun `missing credentials give actionable messages`() {
        try {
            Mimo.normalizeCreds("", "")
            fail("expected QuotaException")
        } catch (e: ExtraQuotaApi.QuotaException) {
            assertTrue(e.message!!.contains("userId"))
        }
        try {
            Mimo.normalizeCreds("991114711", "short")
            fail("expected QuotaException")
        } catch (e: ExtraQuotaApi.QuotaException) {
            assertTrue(e.message!!.contains("serviceToken"))
        }
    }
}

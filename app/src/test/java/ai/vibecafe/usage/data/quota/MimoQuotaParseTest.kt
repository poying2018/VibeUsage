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
 * BALANCE_LIVE / PLAN_LIVE / DETAIL_LIVE 三份是 2026-09-22 用真实登录态直连
 * 2026-09-22 用真实登录态直连 platform.xiaomimimo.com 的 api 接口抓回来的原文
 * （账号：按量余额 ¥4.60、未订阅 Token Plan）。
 * PLAN_SUBSCRIBED 是**臆造**的已订阅样本：该账号没有套餐，items[] 真字段拿不到，
 * 字段名取自控制台前端 i18n（「当前套餐用量 {{used}} / {{limit}}」「补偿积分」）
 * 与第三方逆向资料里的 plan_total_token / compensation_total_token，属容错解析目标而非实测。
 */
class MimoQuotaParseTest {

    private val BALANCE_LIVE =
        """{"code":0,"message":"","data":{"balance":"4.60","frozenBalance":"0.00","currency":"CNY","overdraftLimit":"0.00","remainingOverdraftLimit":"0.00","giftBalance":"0.00","cashBalance":"4.60"}}"""

    private val PLAN_LIVE =
        """{"code":0,"message":"","data":{"monthUsage":{"percent":0,"items":null},"usage":null}}"""

    private val DETAIL_LIVE =
        """{"code":0,"message":"","data":{"planCode":null,"planName":null,"currentPeriodEnd":null,"expired":null,"enableAutoRenew":false,"autoRenewDiscount":"0.88","hasAutoRenewSubscribed":false,"clawEnabled":false,"clawPeriodEnd":null,"clawPurchased":false}}"""

    private val PLAN_SUBSCRIBED =
        """{"code":0,"data":{"monthUsage":{"percent":37,"items":[{"type":"plan_total_token","used":15170000000,"limit":41000000000,"percent":37},{"type":"compensation_total_token","used":0,"total":2000000000,"usedPercent":0}]}}}"""

    private val DETAIL_SUBSCRIBED =
        """{"code":0,"data":{"planCode":"lite","planName":"Lite","currentPeriodEnd":"2026-10-08T16:00:00Z","expired":false,"enableAutoRenew":true}}"""

    // ─── 余额 ───

    @Test
    fun `live balance renders one full bar with cash split`() {
        val bars = Mimo.parseBalance(BALANCE_LIVE)
        assertEquals(1, bars.size)
        val b = bars[0]
        assertEquals("CNY 余额 ¥4.60", b.label)
        assertEquals(100, b.percentRemaining)
        // 赠费/冻结/透支全为 0 时不占位，只留现金
        assertEquals("现金 ¥4.60", b.counts)
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
        assertEquals("USD 余额 \$1.20", Mimo.parseBalance(
            """{"code":0,"data":{"balance":"1.20","cashBalance":"1.20","currency":"USD"}}""").first().label)
        assertTrue(Mimo.parseBalance("""{"code":0,"data":{}}""").isEmpty())
        assertTrue(Mimo.parseBalance("""{"code":0,"message":""}""").isEmpty())
    }

    // ─── 套餐额度 ───

    @Test
    fun `unsubscribed account draws no fake plan bar`() {
        assertTrue(Mimo.parsePlan(PLAN_LIVE, DETAIL_LIVE).isEmpty())
        assertEquals("未订阅 Token Plan", Mimo.accountOf(DETAIL_LIVE))
    }

    @Test
    fun `unreadable detail response stays silent instead of claiming unsubscribed`() {
        assertNull(Mimo.accountOf("<html>502 Bad Gateway</html>"))
        assertNull(Mimo.accountOf("""{"code":500,"message":"boom"}"""))
    }

    @Test
    fun `unsubscribed still shows the month bar when detail fetch failed but items exist`() {
        // detail 拉取失败（null）时以 items 为准，不能因为拿不到套餐名就把额度藏起来
        val bars = Mimo.parsePlan(PLAN_SUBSCRIBED, null)
        assertEquals("本月套餐用量", bars.first().label)
        assertEquals(63, bars.first().percentRemaining)
        assertEquals(37, bars.first().usedPercent)
    }

    @Test
    fun `subscribed plan shows month usage bar plus compensation bar`() {
        val bars = Mimo.parsePlan(PLAN_SUBSCRIBED, DETAIL_SUBSCRIBED)
        assertEquals(2, bars.size)
        val month = bars[0]
        assertEquals("本月套餐用量", month.label)
        assertEquals(63, month.percentRemaining)
        assertEquals(37, month.usedPercent)
        // 151.7亿 / 410亿：已用与总量并列，供条右侧小字显示
        assertEquals("151.7亿 / 410亿", month.counts)
        assertTrue(month.reset!!.contains("重置"))
        val comp = bars[1]
        assertEquals("补偿积分", comp.label)
        assertEquals(100, comp.percentRemaining)
        assertEquals("0 / 20亿", comp.counts)
    }

    @Test
    fun `month percent missing falls back to summing items`() {
        val bars = Mimo.parsePlan(
            """{"code":0,"data":{"monthUsage":{"items":[{"used":25,"limit":100}]}}}""",
            DETAIL_SUBSCRIBED
        )
        assertEquals(1, bars.size)
        assertEquals(75, bars[0].percentRemaining)
        assertEquals("25 / 100", bars[0].counts)
    }

    @Test
    fun `unknown item type still surfaces with its own name instead of being dropped`() {
        val bars = Mimo.parsePlan(
            """{"code":0,"data":{"monthUsage":{"percent":10,"items":[
              {"type":"claw_total_token","used":3,"limit":10}]}}}""",
            DETAIL_SUBSCRIBED
        )
        assertEquals(2, bars.size)
        assertEquals("claw_total_token", bars[1].label)
        assertEquals(70, bars[1].percentRemaining)
    }

    @Test
    fun `subscribed but unreadable usage shape draws no bar rather than a zero percent one`() {
        // 套餐结构改了 / 端点换了形态时，宁可只剩余额组，也不能报出一条「剩余 0%」的红条
        assertTrue(Mimo.parsePlan("""{"code":0,"data":{"monthUsage":{},"usage":[]}}""", DETAIL_SUBSCRIBED).isEmpty())
        assertTrue(Mimo.parsePlan("""{"code":0,"data":{}}""", DETAIL_SUBSCRIBED).isEmpty())
    }

    @Test
    fun `plan name pill carries expiry and auto-renew flags`() {
        assertEquals("Lite · 自动续订", Mimo.accountOf(DETAIL_SUBSCRIBED))
        assertEquals(
            "Pro · 已过期",
            Mimo.accountOf("""{"code":0,"data":{"planName":"Pro","expired":true,"enableAutoRenew":false}}""")
        )
    }

    // ─── 信封与错误 ───

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

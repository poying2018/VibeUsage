package ai.vibecafe.usage.data.quota

import ai.vibecafe.usage.data.quota.ExtraQuotaApi.Glm
import ai.vibecafe.usage.data.quota.ExtraQuotaApi.QuotaException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 智谱 GLM quota/limit 响应解析：覆盖旧 token 套餐、新 credit 套餐与错误结构。 */
class GlmQuotaParseTest {

    @Test
    fun `legacy TOKENS_LIMIT plan parses both windows`() {
        val json = """
        {"code":200,"msg":"操作成功","success":true,"data":{"limits":[
            {"type":"TOKENS_LIMIT","unit":3,"number":5,"usage":12000000,"currentValue":3000000,"percentage":25,"nextResetTime":1757000000000},
            {"type":"TOKENS_LIMIT","unit":6,"number":1,"usage":60000000,"currentValue":12000000,"percentage":20,"nextResetTime":1757600000000}
        ]}}
        """.trimIndent()
        val usage = Glm.parseUsage(json)
        assertEquals(listOf("5 小时窗口", "每周窗口"), usage.groups.keys.toList())
        val five = usage.groups.getValue("5 小时窗口").single()
        assertEquals("Token 额度", five.label)
        assertEquals(75, five.percentRemaining)
        assertEquals(25, five.usedPercent)
        assertEquals("已用 300.0万/1200.0万", five.counts)
        assertTrue(five.reset!!.contains("重置"))
    }

    @Test
    fun `new CREDIT_LIMIT plan parses`() {
        val json = """
        {"code":200,"success":true,"data":{"limits":[
            {"type":"CREDIT_LIMIT","unit":3,"number":5,"usage":1500,"currentValue":450,"percentage":30,"nextResetTime":1757000000000},
            {"type":"CREDIT_LIMIT","unit":6,"number":1,"usage":6000,"currentValue":600,"percentage":10}
        ]}}
        """.trimIndent()
        val usage = Glm.parseUsage(json)
        assertEquals(listOf("5 小时窗口", "每周窗口"), usage.groups.keys.toList())
        val five = usage.groups.getValue("5 小时窗口").single()
        assertEquals("Credit 额度", five.label)
        assertEquals(70, five.percentRemaining)
        assertEquals(30, five.usedPercent)
        assertEquals("已用 450/1500", five.counts)
        assertEquals(90, usage.groups.getValue("每周窗口").single().percentRemaining)
    }

    @Test
    fun `percentage missing falls back to usage ratio`() {
        val json = """
        {"code":200,"success":true,"data":{"limits":[
            {"type":"TOKENS_LIMIT","unit":3,"number":5,"usage":1000,"currentValue":250}
        ]}}
        """.trimIndent()
        val usage = Glm.parseUsage(json)
        assertEquals(75, usage.groups.getValue("5 小时窗口").single().percentRemaining)
    }

    @Test
    fun `TIME_LIMIT monthly MCP entry parses`() {
        val json = """
        {"code":200,"success":true,"data":{"limits":[
            {"type":"TOKENS_LIMIT","unit":3,"number":5,"usage":1000,"currentValue":100,"percentage":10},
            {"type":"TIME_LIMIT","unit":5,"number":1,"usage":800,"currentValue":200,"percentage":25}
        ]}}
        """.trimIndent()
        val usage = Glm.parseUsage(json)
        val mcp = usage.groups.getValue("MCP 月度").single()
        assertEquals("MCP 额度", mcp.label)
        assertEquals(75, mcp.percentRemaining)
    }

    @Test
    fun `unrecognized window types raise descriptive error`() {
        val json = """
        {"code":200,"success":true,"data":{"limits":[
            {"type":"SOMETHING_NEW","unit":3,"number":5,"percentage":1}
        ]}}
        """.trimIndent()
        try {
            Glm.parseUsage(json)
            fail("expected QuotaException")
        } catch (e: QuotaException) {
            assertTrue(e.message!!.contains("额度窗口"))
        }
    }

    @Test
    fun `empty limits raise hint to check plan`() {
        val json = """{"code":200,"success":true,"data":{"limits":[]}}"""
        try {
            Glm.parseUsage(json)
            fail("expected QuotaException")
        } catch (e: QuotaException) {
            assertTrue(e.message!!.contains("Coding Plan"))
        }
    }

    @Test
    fun `business error surfaces code and msg`() {
        val json = """{"code":401,"msg":"令牌已过期或验证不正确","success":false}"""
        try {
            Glm.parseUsage(json)
            fail("expected QuotaException")
        } catch (e: QuotaException) {
            assertEquals(401, e.code)
            assertEquals("令牌已过期或验证不正确", e.message)
        }
    }
}

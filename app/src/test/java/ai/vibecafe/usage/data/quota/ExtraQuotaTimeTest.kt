package ai.vibecafe.usage.data.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * 额度时间口径的回归测试。
 *
 * 各家额度接口的时间口径都是 Asia/Shanghai（服务端如此声明），所以
 * 「重置时刻」必须按北京时间渲染，不能跟随设备默认时区 —— 否则非东八区设备
 * 显示的时刻会整体偏移，跨时区用户看到的是错的倒计时。
 */
class ExtraQuotaTimeTest {

    @Test
    fun `epoch reset renders in beijing time regardless of device timezone`() {
        // 2026-10-23 12:00:00 UTC = 2026-10-23 20:00 北京时间
        val ms = 1_792_756_800_000L
        val saved = TimeZone.getDefault()
        try {
            // 设备在 UTC-8（比北京晚 16 小时）：跟随设备时区会显示成 10-23 04:00
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val text = ExtraQuotaApi.epochReset(ms)
            assertTrue("应显示北京时间 10-23 20:00，实际=$text", text.startsWith("10-23 20:00"))

            // 设备在 UTC+13（比北京早 5 小时）：跟随设备时区会显示成 10-24 01:00
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val text2 = ExtraQuotaApi.epochReset(ms)
            assertTrue("应始终显示北京时间 10-23 20:00，实际=$text2", text2.startsWith("10-23 20:00"))
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `compact uses dot decimal separator in every locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            // 小数点为逗号的 locale（de/ru）会让 "%.1f" 输出 "123,4"
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("123.5万", ExtraQuotaApi.compact(1_234_567.0))
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals("123.5万", ExtraQuotaApi.compact(1_234_567.0))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `iso with numeric offset is converted to absolute time`() {
        // 2026-10-23T20:00:00+08:00 == 2026-10-23T12:00:00Z
        val withOffset = ai.vibecafe.usage.data.quota.parseIsoMillis("2026-10-23T20:00:00+08:00")
        val asUtc = ai.vibecafe.usage.data.quota.parseIsoMillis("2026-10-23T12:00:00Z")
        assertEquals("带数字偏移与 Z 形态应解析到同一时刻", asUtc, withOffset)
    }

    @Test
    fun `epoch seconds are normalized to milliseconds`() {
        // 1_792_756_800 秒 = 2026-10-23T12:00:00Z
        assertEquals(1_792_756_800_000L, ai.vibecafe.usage.data.quota.normalizeEpochMs(1_792_756_800L))
        // 已经是毫秒的原样返回
        assertEquals(1_792_756_800_000L, ai.vibecafe.usage.data.quota.normalizeEpochMs(1_792_756_800_000L))
    }
}

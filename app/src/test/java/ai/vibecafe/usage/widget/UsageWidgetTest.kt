package ai.vibecafe.usage.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/**
 * 桌面小组件快照的回归测试。
 *
 * 两条关键性质：
 * 1. 写入是**原子**的 —— 读方永远看不到半截 JSON（写一半时崩溃会让桌面退化成「等待同步」）；
 * 2. 「今天」的判定和统计引擎同口径（北京时间）—— 否则跨时区用户会看到
 *    「今日消耗」配的其实是昨天的数字。
 */
class UsageWidgetTest {

    private fun tempFile(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "vibeusage-widget-test-" + System.nanoTime())
        dir.mkdirs()
        return File(dir, name)
    }

    @Test
    fun `snapshot roundtrip`() {
        val f = tempFile("snap.json")
        UsageWidgetCache.writeTo(f, WidgetSnapshot(costToday = 12.34, tokensToday = 5678L, updatedAt = 1_700_000_000_000L))
        val read = UsageWidgetCache.readFrom(f)
        assertNotNull(read)
        assertEquals(12.34, read!!.costToday, 0.0001)
        assertEquals(5678L, read.tokensToday)
        assertEquals(1_700_000_000_000L, read.updatedAt)
    }

    @Test
    fun `overwriting a snapshot keeps the file complete`() {
        val f = tempFile("snap.json")
        // 连续覆写：每次都是「写临时文件 → 改名」
        repeat(20) { i ->
            UsageWidgetCache.writeTo(f, WidgetSnapshot(costToday = i.toDouble(), tokensToday = i.toLong(), updatedAt = i.toLong()))
        }
        val read = UsageWidgetCache.readFrom(f)
        assertNotNull("反复覆写后仍应能解析出完整 JSON", read)
        assertEquals(19.0, read!!.costToday, 0.0001)
        // 临时文件不该残留
        assertTrue("临时文件应被改名消耗掉", !File(f.parentFile, "${f.name}.tmp").exists())
    }

    @Test
    fun `reading a missing snapshot returns null`() {
        val f = tempFile("never-written.json")
        assertNull(UsageWidgetCache.readFrom(f))
    }

    @Test
    fun `reading a corrupted snapshot returns null instead of throwing`() {
        val f = tempFile("broken.json")
        f.writeText("{not json at all")
        // 坏文件不能让渲染路径抛异常（会打成「等待同步」而不是崩）
        assertNull(UsageWidgetCache.readFrom(f))
    }

    @Test
    fun `same day is judged in beijing time like the stats engine`() {
        val saved = TimeZone.getDefault()
        try {
            // 2026-10-24 00:30 北京时间 = 2026-10-23 16:30 UTC
            // 设备在 UTC+13 时，本地已经是 10-24 07:30
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val beijingMidnight = java.time.LocalDateTime.of(2026, 10, 24, 0, 30)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli()
            // 同一「北京日」内的另一个时刻：北京时间当天 23:00
            val beijingEvening = java.time.LocalDateTime.of(2026, 10, 24, 23, 0)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli()
            assertTrue("北京时间同一天应判定为同一天", isSameDay(beijingMidnight, beijingEvening))

            // 次日北京时间 00:30 就不该算同一天了
            val nextDay = java.time.LocalDateTime.of(2026, 10, 25, 0, 30)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli()
            assertTrue("跨了北京时间零点应判为不同天", !isSameDay(beijingMidnight, nextDay))
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}

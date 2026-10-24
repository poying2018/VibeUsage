package ai.vibecafe.usage.data.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/**
 * 额度历史快照（近 7 天）的落盘回归测试。
 *
 * 存储层刻意做成「注入 File 路径」的形式：不用 Context 也能跑，
 * 写-读往返 / 同日覆盖 / 7 天裁剪都能在 JVM 单测里直接验证。
 */
class QuotaHistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String = "quota_history_minimax.json"): File = File(tmp.root, name)

    private fun usage(
        remaining: Int,
        used: Int? = null,
        group: String = "5 小时窗口"
    ): ExtraQuotaApi.Usage = ExtraQuotaApi.Usage(
        account = "user@example.com",
        groups = linkedMapOf(
            group to listOf(
                ExtraQuotaApi.Bar(
                    label = "额度",
                    percentRemaining = remaining,
                    usedPercent = used,
                    counts = null,
                    reset = null
                )
            )
        )
    )

    @Test
    fun `写入后能读回当天快照`() {
        val f = file()
        QuotaHistoryStore.recordTo(f, usage(72, 28), "2026-10-23", "2026-10-23")

        val list = QuotaHistoryStore.historyFrom(f, "2026-10-23")
        assertEquals(1, list.size)
        assertEquals("2026-10-23", list[0].date)
        assertEquals(72, list[0].summary["5 小时窗口"])
        assertEquals(28, list[0].used["5 小时窗口"])
    }

    @Test
    fun `文件不存在时返回空表而不是抛异常`() {
        val list = QuotaHistoryStore.historyFrom(file("missing.json"), "2026-10-23")
        assertTrue(list.isEmpty())
    }

    @Test
    fun `同一天重复记录覆盖当天那条`() {
        val f = file()
        QuotaHistoryStore.recordTo(f, usage(80), "2026-10-23", "2026-10-23")
        QuotaHistoryStore.recordTo(f, usage(35), "2026-10-23", "2026-10-23")

        val list = QuotaHistoryStore.historyFrom(f, "2026-10-23")
        assertEquals("同一天只保留一条", 1, list.size)
        assertEquals(35, list[0].summary["5 小时窗口"])
    }

    @Test
    fun `只保留近七天`() {
        val f = file()
        val today = LocalDate.parse("2026-10-23")
        // 连续记录 10 天
        for (i in 9 downTo 0) {
            val d = today.minusDays(i.toLong()).toString()
            QuotaHistoryStore.recordTo(f, usage(50 + i), d, today.toString())
        }

        val list = QuotaHistoryStore.historyFrom(f, today.toString())
        assertEquals("只留近 7 天", 7, list.size)
        assertEquals("2026-10-17", list.first().date)
        assertEquals("2026-10-23", list.last().date)
        // 文件本身也要被裁剪，不能只在读的时候过滤
        assertFalse("旧快照不应留在文件里", f.readText().contains("2026-10-16"))
    }

    @Test
    fun `历史按日期升序返回`() {
        val f = file()
        // 故意乱序写入
        listOf("2026-10-21", "2026-10-19", "2026-10-23", "2026-10-20", "2026-10-22").forEachIndexed { i, d ->
            QuotaHistoryStore.recordTo(f, usage(10 + i), d, "2026-10-23")
        }

        val list = QuotaHistoryStore.historyFrom(f, "2026-10-23")
        assertEquals(
            listOf("2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23"),
            list.map { it.date }
        )
    }

    @Test
    fun `每个分组取第一条 bar 的剩余与已用`() {
        val usage = ExtraQuotaApi.Usage(
            account = null,
            groups = linkedMapOf(
                "5 小时窗口" to listOf(
                    ExtraQuotaApi.Bar("额度", 60, 40),
                    ExtraQuotaApi.Bar("其它", 10, 90)
                ),
                "每周窗口" to listOf(ExtraQuotaApi.Bar("额度", 25, 75))
            )
        )
        val snap = QuotaHistoryStore.snapshotOf(usage, "2026-10-23")
        assertEquals(60, snap.summary["5 小时窗口"])
        assertEquals(40, snap.used["5 小时窗口"])
        assertEquals(25, snap.summary["每周窗口"])
        assertEquals(75, snap.used["每周窗口"])
    }

    @Test
    fun `额度窗口口径优先取窗口分组`() {
        val groups = linkedMapOf(
            "套餐额度" to listOf(ExtraQuotaApi.Bar("a", 10)),
            "5 小时窗口" to listOf(ExtraQuotaApi.Bar("b", 20))
        )
        assertEquals("5 小时窗口", preferredQuotaGroup(groups))

        // 没有窗口分组时取第一个
        val only = linkedMapOf("订阅额度" to listOf(ExtraQuotaApi.Bar("a", 10)))
        assertEquals("订阅额度", preferredQuotaGroup(only))

        assertEquals(null, preferredQuotaGroup(emptyMap()))
    }
}

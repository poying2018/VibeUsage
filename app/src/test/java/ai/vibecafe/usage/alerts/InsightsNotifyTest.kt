package ai.vibecafe.usage.alerts

import ai.vibecafe.usage.ui.ag.ExtraProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 额度预警通知 id 的稳定性：
 * 同一供应商每次更新同一条通知，不同供应商不能互相覆盖，
 * 也不能撞到预算提醒（41）/ 每日摘要（43）。
 */
class InsightsNotifyTest {

    @Test
    fun `each provider gets a distinct id outside the reserved range`() {
        val ids = ExtraProvider.entries.map { InsightsNotify.quotaNotificationId(it.id) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it >= 1000 })
    }

    @Test
    fun `unknown provider id is stable`() {
        assertEquals(
            InsightsNotify.quotaNotificationId("mystery"),
            InsightsNotify.quotaNotificationId("mystery")
        )
    }
}

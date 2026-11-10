package ai.vibecafe.usage.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「自动」主题的时段边界：19:00 起深色，07:00 起浅色。 */
class ThemeStoreTest {

    @Test
    fun `auto theme is dark from 19 through 6`() {
        for (hour in 0..6) {
            assertTrue("hour=$hour should be dark", isAutoDark(hour))
        }
        for (hour in 7..18) {
            assertFalse("hour=$hour should be light", isAutoDark(hour))
        }
        for (hour in 19..23) {
            assertTrue("hour=$hour should be dark", isAutoDark(hour))
        }
    }
}

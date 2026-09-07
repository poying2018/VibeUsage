package ai.vibecafe.usage.share

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ShareCardTrendTest {

    @Test
    fun `up trend renders teal arrow plus percent`() {
        Locale.setDefault(Locale.US)
        assertEquals("↗ +12%", ShareCard.trendText(0.1234f))
        assertEquals("↗ +1%", ShareCard.trendText(0.005f))
    }

    @Test
    fun `down trend renders rose arrow minus percent`() {
        Locale.setDefault(Locale.US)
        assertEquals("↘ -8%", ShareCard.trendText(-0.08f))
    }

    @Test
    fun `near zero trend reads flat`() {
        Locale.setDefault(Locale.US)
        assertEquals("→ 持平", ShareCard.trendText(0.004f))
        assertEquals("→ 持平", ShareCard.trendText(-0.004f))
        assertEquals("→ 持平", ShareCard.trendText(0f))
    }
}

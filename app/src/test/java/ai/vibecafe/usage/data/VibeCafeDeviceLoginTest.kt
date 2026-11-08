package ai.vibecafe.usage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeCafeDeviceLoginTest {

    @Test
    fun `start parses camelCase payload`() {
        val body = """
            {"deviceCode":"d1","userCode":"D4JQ-3WH9","verificationUri":"https://vibecafe.ai/usage/device",
             "verificationUriComplete":"https://vibecafe.ai/usage/device?code=D4JQ-3WH9","expiresIn":900,"interval":5}
        """.trimIndent()
        val start = VibeCafeDeviceLogin.parseStart(body)!!
        assertEquals("d1", start.deviceCode)
        assertEquals("D4JQ-3WH9", start.userCode)
        assertEquals("https://vibecafe.ai/usage/device?code=D4JQ-3WH9", start.verifyUrl)
        assertEquals(900, start.expiresInSec)
        assertEquals(5, start.intervalSec)
    }

    @Test
    fun `start falls back to verificationUri and appends code`() {
        val start = VibeCafeDeviceLogin.parseStart(
            """{"deviceCode":"d","userCode":"ABCD-EFGH","verificationUri":"https://vibecafe.ai/usage/device"}"""
        )!!
        assertEquals("https://vibecafe.ai/usage/device?code=ABCD-EFGH", start.verifyUrl)
        assertEquals(900, start.expiresInSec)
        assertEquals(5, start.intervalSec)
    }

    @Test
    fun `start without deviceCode returns null`() {
        assertNull(VibeCafeDeviceLogin.parseStart("""{"userCode":"ABCD-EFGH"}"""))
        assertNull(VibeCafeDeviceLogin.parseStart("not json"))
    }

    @Test
    fun `pending is not terminal`() {
        assertEquals(
            VibeCafeDeviceLogin.PollResult.Pending,
            VibeCafeDeviceLogin.parsePoll(200, """{"error":"authorization_pending"}""")
        )
        assertEquals(
            VibeCafeDeviceLogin.PollResult.Pending,
            VibeCafeDeviceLogin.parsePoll(200, """{"error":"slow_down"}""")
        )
    }

    @Test
    fun `deny and expiry map to terminal states`() {
        assertEquals(
            VibeCafeDeviceLogin.PollResult.Denied,
            VibeCafeDeviceLogin.parsePoll(200, """{"error":"access_denied"}""")
        )
        assertEquals(
            VibeCafeDeviceLogin.PollResult.Expired,
            VibeCafeDeviceLogin.parsePoll(400, """{"error":"invalid_grant"}""")
        )
        assertEquals(
            VibeCafeDeviceLogin.PollResult.Expired,
            VibeCafeDeviceLogin.parsePoll(200, """{"error":"expired_token"}""")
        )
    }

    @Test
    fun `approval picks known credential fields`() {
        val approved = VibeCafeDeviceLogin.parsePoll(200, """{"apiKey":"vibe_abc"}""")!!
            as VibeCafeDeviceLogin.PollResult.Approved
        assertEquals("vibe_abc", approved.apiKey)
        assertEquals(
            "vibe_xyz",
            (VibeCafeDeviceLogin.parsePoll(200, """{"access_token":"vibe_xyz"}""")!!
                as VibeCafeDeviceLogin.PollResult.Approved).apiKey
        )
    }

    @Test
    fun `approval scans vibe_ prefixed values as fallback`() {
        val approved = VibeCafeDeviceLogin.parsePoll(
            200,
            """{"userCode":"D4JQ-3WH9","secret":"vibe_live_key"}"""
        )!! as VibeCafeDeviceLogin.PollResult.Approved
        assertEquals("vibe_live_key", approved.apiKey)
    }

    @Test
    fun `unrecognized success payload fails loudly instead of hanging`() {
        val result = VibeCafeDeviceLogin.parsePoll(200, """{"unexpected":"shape"}""")
        assertTrue(result is VibeCafeDeviceLogin.PollResult.Failed)
        assertFalse((result as VibeCafeDeviceLogin.PollResult.Failed).transient)
    }

    @Test
    fun `server errors are transient and client errors are terminal`() {
        val result = VibeCafeDeviceLogin.parsePoll(502, "<html>bad gateway</html>")
        assertTrue(result is VibeCafeDeviceLogin.PollResult.Failed)
        assertTrue((result as VibeCafeDeviceLogin.PollResult.Failed).transient)
        val rateLimited = VibeCafeDeviceLogin.parsePoll(429, "") as VibeCafeDeviceLogin.PollResult.Failed
        assertTrue(rateLimited.transient)
        val badRequest = VibeCafeDeviceLogin.parsePoll(400, "not json") as VibeCafeDeviceLogin.PollResult.Failed
        assertFalse(badRequest.transient)
    }
}

package ai.vibecafe.usage.data.quota

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenDesign Go 额度解析回归测试。
 *
 * 这些函数都是纯函数（不碰网络/Context），锁的是官方 snapshot 契约：
 * 5h/7d 窗口 credits、钱包余额、套餐月额度上限，以及工作空间 id 的提取。
 */
class OpenDesignTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `workspace id is read from workspace object not account id`() {
        assertEquals(
            "ws_123",
            OpenDesign.findWorkspaceId(obj("""{"workspace":{"workspaceId":"ws_123"},"userId":"u_1"}"""))
        )
        assertEquals(
            "ws_456",
            OpenDesign.findWorkspaceId(obj("""{"data":{"workspaceId":"ws_456"}}"""))
        )
        assertEquals(
            "ws_789",
            OpenDesign.findWorkspaceId(obj("""{"context":{"workspaceId":"ws_789"}}"""))
        )
        assertNull(OpenDesign.findWorkspaceId(obj("""{"userId":"u_1"}""")))
    }

    @Test
    fun `snapshot parses coding plan windows and wallet`() {
        val snapshot = OpenDesign.parseSnapshot(
            obj(
                """
                {
                  "schemaVersion": 1,
                  "workspaceId": "ws_1",
                  "workspaceMemberId": "m_1",
                  "billing": {"billingState": "active", "planId": "go_plus"},
                  "wallet": {"balanceUsd": "123.45", "expiresAt": null, "updatedAt": null},
                  "modelRestriction": {"allowedModels": ["public_model_a"]},
                  "designPlanWindows": [
                    {"kind":"5h","remainingCredits":"18","limitCredits":"55",
                     "windowStart":"2026-11-28T10:00:00Z","resetsAt":"2026-11-28T15:00:00Z"},
                    {"kind":"7d","remainingCredits":"100","limitCredits":"220",
                     "windowStart":null,"resetsAt":null}
                  ]
                }
                """.trimIndent()
            )
        )!!
        assertEquals("go_plus", snapshot.planId)
        assertEquals(123.45, snapshot.balanceUsd!!, 0.0001)
        assertEquals(2, snapshot.windows.size)
        assertEquals("5h", snapshot.windows[0].kind)
        assertEquals(18.0, snapshot.windows[0].remaining, 0.0001)
        assertEquals(55.0, snapshot.windows[0].limit, 0.0001)
        assertEquals("7d", snapshot.windows[1].kind)
        assertEquals(setOf("public_model_a"), snapshot.allowedModels)
    }

    @Test
    fun `malformed snapshot is rejected`() {
        assertNull(
            OpenDesign.parseSnapshot(obj("""{"schemaVersion":2,"billing":{}}"""))
        )
        assertNull(
            OpenDesign.parseSnapshot(
                obj("""{"schemaVersion":1,"billing":{"planId":"go"}}""")
            )
        )
        // remaining > limit 是脏数据，不能画成「剩余 200%」
        val bad = OpenDesign.parseSnapshot(
            obj(
                """
                {"schemaVersion":1,"billing":{"planId":"go"},
                 "wallet":{"balanceUsd":"1.00"},
                 "designPlanWindows":[{"kind":"5h","remainingCredits":"100","limitCredits":"50"}]}
                """.trimIndent()
            )
        )!!
        assertTrue(bad.windows.isEmpty())
    }

    @Test
    fun `groups use remaining percent and official monthly caps`() {
        val snapshot = OpenDesign.Snapshot(
            workspaceId = "ws_1",
            workspaceMemberId = "m_1",
            planId = "go_plus",
            billingState = "active",
            balanceUsd = 123.45,
            windows = listOf(
                OpenDesign.Window("5h", 18.0, 55.0, null),
                OpenDesign.Window("7d", 100.0, 220.0, null)
            )
        )
        val groups = OpenDesign.buildGroups(snapshot)
        assertEquals(listOf("Coding Plan 窗口", "月度总额度"), groups.keys.toList())

        val coding = groups["Coding Plan 窗口"]!!
        assertEquals(2, coding.size)
        assertEquals("5 小时窗口", coding[0].label)
        assertEquals(33, coding[0].percentRemaining) // 18/55 ≈ 32.7
        assertEquals(67, coding[0].usedPercent)
        assertTrue(coding[0].counts!!.contains("18"))
        assertEquals("7 天窗口", coding[1].label)
        assertEquals(45, coding[1].percentRemaining) // 100/220 ≈ 45.5

        val month = groups["月度总额度"]!!.single()
        assertEquals(40, month.percentRemaining) // 123.45/310 ≈ 39.8
        assertEquals(60, month.usedPercent)
        assertTrue(month.counts!!.contains("\$123.45"))
        assertTrue(month.counts!!.contains("\$310.00"))
    }

    @Test
    fun `zero wallet does not draw a misleading zero percent monthly bar`() {
        val groups = OpenDesign.buildGroups(
            OpenDesign.Snapshot(
                workspaceId = "ws_1", workspaceMemberId = "m_1",
                planId = "go", billingState = "active", balanceUsd = 0.0,
                windows = listOf(OpenDesign.Window("5h", 10.0, 55.0, null))
            )
        )
        assertEquals(listOf("Coding Plan 窗口"), groups.keys.toList())
        assertEquals("Go", OpenDesign.accountOf(
            OpenDesign.Snapshot("ws_1", "m_1", "go", "active", 0.0, emptyList())
        ))
        assertEquals("Free", OpenDesign.accountOf(
            OpenDesign.Snapshot("ws_1", "m_1", "free", "free", 0.0, emptyList())
        ))
    }

    @Test
    fun `monthly cap normalizes plan id and rejects unknown`() {
        assertEquals(220.0, OpenDesign.monthlyCap("go")!!, 0.0001)
        assertEquals(220.0, OpenDesign.monthlyCap("go_monthly")!!, 0.0001)
        assertEquals(310.0, OpenDesign.monthlyCap("go_plus")!!, 0.0001)
        assertEquals(310.0, OpenDesign.monthlyCap("go-plus")!!, 0.0001)
        assertEquals(780.0, OpenDesign.monthlyCap("team_pro")!!, 0.0001)
        assertEquals(1410.0, OpenDesign.monthlyCap("GO_MAX_YEARLY")!!, 0.0001)
        assertNull(OpenDesign.monthlyCap("free"))
        assertNull(OpenDesign.monthlyCap(null))
    }

    @Test
    fun `model ids accept id, model and plain string shapes`() {
        assertEquals(
            listOf("a", "b"),
            OpenDesign.parseModelIds("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}""")
        )
        assertEquals(
            listOf("gpt-6-luna", "hy4"),
            OpenDesign.parseModelIds("""{"data":[{"model":"hy4"},{"model":"gpt-6-luna"}]}""")
        )
        assertEquals(
            listOf("m1", "m2"),
            OpenDesign.parseModelIds("""["m2","m1"]""")
        )
        assertFalse(OpenDesign.parseModelIds("not json").isNotEmpty())
    }

    @Test
    fun `model catalog respects account restriction but empty means all`() {
        val all = listOf("public_model_a", "public_model_b", "c")
        assertEquals(listOf("public_model_a"), OpenDesign.filterModels(all, setOf("public_model_a")))
        assertEquals(listOf("c"), OpenDesign.filterModels(all, setOf("c")))
        assertEquals(all, OpenDesign.filterModels(all, emptySet()))
    }

    @Test
    fun `device code parses real activation response`() {
        val dc = OpenDesignDevice.parseDeviceCode(
            obj(
                """
                {"deviceId":"dev1","userCode":"ABC123","status":"pending",
                 "deviceSecret":"sec1",
                 "activationUrl":"https://open-design.ai/cloud/cli/activate?deviceId=dev1&userCode=ABC123",
                 "expiresAt":"2026-11-28T05:12:30.738Z","pollIntervalSeconds":2}
                """.trimIndent()
            )
        )!!
        assertEquals("dev1", dc.deviceId)
        assertEquals("ABC123", dc.userCode)
        assertEquals("sec1", dc.deviceSecret)
        assertTrue(dc.activationUrl.contains("userCode=ABC123"))
        assertEquals(2, dc.intervalSec)
        assertTrue(dc.expiresAtMs > 0)

        // 没有 activationUrl 时按官方格式拼出来，不能让用户只看到一串 code 无法跳转
        val fallback = OpenDesignDevice.parseDeviceCode(
            obj("""{"deviceId":"dev2","userCode":"XYZ","deviceSecret":"s"}""")
        )!!
        assertTrue(fallback.activationUrl.contains("deviceId=dev2"))
        assertEquals(2, fallback.intervalSec)
    }

    @Test
    fun `device token pending is not a credential and authorized parses keys`() {
        assertNull(
            OpenDesignDevice.parseToken(
                obj("""{"status":"pending","controlKey":null,"runtimeKey":null}""")
            )
        )
        val token = OpenDesignDevice.parseToken(
            obj(
                """
                {"status":"approved","controlKey":"ctrl_1","runtimeKey":"run_1",
                 "controlKeyId":"keyid_1","userId":"user_1",
                 "linkUrl":"https://amr-link.open-design.ai",
                 "apiUrl":"https://amr-api.open-design.ai","profile":"prod",
                 "user":{"email":"me@example.com"}}
                """.trimIndent()
            )
        )!!
        assertEquals("ctrl_1", token.controlKey)
        assertEquals("run_1", token.runtimeKey)
        assertEquals("keyid_1", token.controlKeyId)
        assertEquals("user_1", token.userId)
        assertEquals("me@example.com", token.userEmail)

        // 官方 CLI 的收尾端点只认 status=completed + completionReceipt
        assertNull(OpenDesignDevice.parseCompletionReceipt(obj("""{"status":"approved"}""")))
        assertEquals(
            "receipt_1",
            OpenDesignDevice.parseCompletionReceipt(
                obj("""{"status":"completed","completionReceipt":"receipt_1"}""")
            )
        )
    }

    @Test
    fun `login credential round trips and plain api key is untouched`() {
        val token = OpenDesignDevice.Token("ctrl_9", "run_9", profile = "prod")
        val stored = OpenDesignDevice.encode(token)
        assertTrue(OpenDesignDevice.isLoginCred(stored))
        assertEquals("ctrl_9", OpenDesignDevice.decode(stored)!!.controlKey)
        assertNull(OpenDesignDevice.decode("sk-plain-key"))
        assertFalse(OpenDesignDevice.isLoginCred("sk-plain-key"))
    }
}

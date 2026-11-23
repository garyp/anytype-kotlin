package com.anytypeio.anytype.apibridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppApprovalsTest {

    private var now = 1_000L

    @get:Rule
    val files = ApprovalsRule { now }

    private fun store() = files.approvals

    private fun approval(account: String = "A", pkg: String = "com.x", cert: String = "abcdef0123456789") =
        Approval(account, pkg, cert, Access.READ_ONLY, Grant(false, listOf("s1")), 5L)

    @Test
    fun approveThenFind() = runTest {
        val s = store()
        val a = approval()
        s.approve(a)
        assertEquals(a, s.find("A", "com.x"))
        assertEquals(listOf(a), s.all())
    }

    @Test
    fun approvalIsScopedToAccount() = runTest {
        val s = store()
        s.approve(approval("A"))
        assertNull(s.find("B", "com.x"))
    }

    @Test
    fun revokeRemovesAndQueuesPendingRevoke() = runTest {
        val s = store()
        s.approve(approval())
        s.clearPendingRevoke("A", "com.x")
        s.revoke("A", "com.x")
        assertNull(s.find("A", "com.x"))
        assertEquals(setOf("com.x"), s.pendingRevokes("A"))
        s.clearPendingRevoke("A", "com.x")
        assertTrue(s.pendingRevokes("A").isEmpty())
    }

    @Test
    fun approveQueuesRevokeOfOldKeys() = runTest {
        val s = store()
        s.approve(approval())
        assertEquals(setOf("com.x"), s.pendingRevokes("A"))
        assertTrue(s.pendingRevokes("B").isEmpty())
    }

    @Test
    fun denialCooldownExpiresAfter30s() = runTest {
        val s = store()
        assertFalse(s.isInDenialCooldown("com.x"))
        s.deny("com.x")
        now = 1_000L + 29_999
        assertTrue(s.isInDenialCooldown("com.x"))
        now = 1_000L + 30_001
        assertFalse(s.isInDenialCooldown("com.x"))
    }

    @Test
    fun grantRejectsEmptySpaceList() {
        assertFailsWith<IllegalArgumentException> { Grant(false, emptyList()) }
        Grant(true, emptyList())
        assertTrue(Grant.ALL.allSpaces)
    }

    @Test
    fun stateSurvivesReopening() = runTest {
        val a = approval()
        store().approve(a)
        store().deny("com.y")
        val s2 = files.open()
        assertEquals(a, s2.find("A", "com.x"))
        assertEquals(setOf("com.x"), s2.pendingRevokes("A"))
        assertTrue(s2.isInDenialCooldown("com.y"))
    }

    @Test
    fun keyNames() {
        assertEquals("app:com.x:abcdef01", KeyNames.name("com.x", "abcdef0123456789"))
        assertEquals("app:com.x:", KeyNames.prefix("com.x"))
        assertNotNull(KeyNames)
    }

    @Test
    fun concurrentUpdatesLoseNone() = runBlocking {
        // Two users of the one store, as the controller and a screen are.
        val users = listOf(store(), AppApprovals(files.dataStore) { now })
        val n = 40
        (0 until n).map { i ->
            async(Dispatchers.Default) {
                val s = users[i % 2]
                s.approve(approval(pkg = "com.p$i"))
                s.revoke("A", "com.p$i")
                if (i % 2 == 0) s.clearPendingRevoke("A", "com.p$i")
            }
        }.awaitAll()
        val fresh = files.open()
        assertTrue(fresh.all().isEmpty())
        val expected = (0 until n).filter { it % 2 == 1 }.map { "com.p$it" }.toSet()
        assertEquals(expected, fresh.pendingRevokes("A"))
    }

    /** Replaces the file under a closed store; the next [store] reads it. */
    private fun writeRaw(raw: String) {
        files.close()
        files.file.writeText(raw)
    }

    @Test
    fun corruptEntriesAreSkippedAndPendingRevokesSurvive() = runTest {
        writeRaw(
            """
            {"approvals":[
              {"accountId":"A","packageName":"com.bad","certSha256":"c","access":"NOPE",
               "grant":{"allSpaces":true,"spaceIds":[]},"approvedAt":1},
              "junk",
              {"accountId":"A","packageName":"com.x","certSha256":"abcdef0123456789","access":"READ_ONLY",
               "grant":{"allSpaces":false,"spaceIds":["s1"]},"approvedAt":5}
             ],
             "pending":[{"accountId":"A"},{"accountId":"A","packageName":"com.gone"},7],
             "deniedUntil":{"com.d":5000,"com.bad":"soon"}}
            """.trimIndent()
        )
        val s = store()
        assertEquals(listOf(approval()), s.all())
        assertEquals(setOf("com.gone"), s.pendingRevokes("A"))
        assertTrue(s.isInDenialCooldown("com.d"))
        assertFalse(s.isInDenialCooldown("com.bad"))
    }

    @Test
    fun badSectionDoesNotDropOtherSections() = runTest {
        writeRaw("""{"approvals":"oops","pending":[{"accountId":"A","packageName":"com.gone"}],"deniedUntil":3}""")
        val s = store()
        assertTrue(s.all().isEmpty())
        assertEquals(setOf("com.gone"), s.pendingRevokes("A"))
        // A write keeps the surviving pending revoke.
        s.deny("com.z")
        assertEquals(setOf("com.gone"), files.open().pendingRevokes("A"))
    }

    @Test
    fun storedGrantBreakingItsRuleIsSkipped() = runTest {
        writeRaw(
            """{"approvals":[{"accountId":"A","packageName":"com.x","certSha256":"c","access":"READ_ONLY",
               "grant":{"allSpaces":false,"spaceIds":[]},"approvedAt":1}]}"""
        )
        assertTrue(store().all().isEmpty())
    }

    @Test
    fun unparsableFileStartsEmptyAndIsReplaced() = runTest {
        writeRaw("{not json")
        val s = store()
        assertTrue(s.all().isEmpty())
        assertTrue(s.pendingRevokes("A").isEmpty())
        // The corruption handler wrote an empty state over the file; writes work again.
        s.approve(approval())
        assertEquals(listOf(approval()), files.open().all())
    }

    @Test
    fun nonObjectFileStartsEmpty() = runTest {
        writeRaw("[1, 2]")
        assertTrue(store().all().isEmpty())
    }

    @Test
    fun fileIsVersionedJson() = runTest {
        store().approve(approval())
        files.close()
        val written = files.file.readText()
        assertTrue(written.startsWith("""{"version":${ApprovalsState.SCHEMA_VERSION},"""), written)
        assertTrue(""""packageName":"com.x"""" in written, written)
    }
}

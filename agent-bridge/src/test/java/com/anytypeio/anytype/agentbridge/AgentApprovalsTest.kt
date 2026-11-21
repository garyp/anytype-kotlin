package com.anytypeio.anytype.agentbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
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
class AgentApprovalsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var now = 1_000L

    @Before
    fun clear() {
        context.getSharedPreferences("agent_bridge", Context.MODE_PRIVATE).edit().clear().commit()
        now = 1_000L
    }

    private fun store() = AgentApprovals(context) { now }

    private fun approval(account: String = "A", pkg: String = "com.x", cert: String = "abcdef0123456789") =
        Approval(account, pkg, cert, Access.READ_ONLY, Grant(false, listOf("s1")), 5L)

    @Test
    fun approveThenFind() {
        val s = store()
        val a = approval()
        s.approve(a)
        assertEquals(a, s.find("A", "com.x"))
        assertEquals(listOf(a), s.all())
    }

    @Test
    fun approvalIsScopedToAccount() {
        val s = store()
        s.approve(approval("A"))
        assertNull(s.find("B", "com.x"))
    }

    @Test
    fun revokeRemovesAndQueuesPendingRevoke() {
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
    fun approveQueuesRevokeOfOldKeys() {
        val s = store()
        s.approve(approval())
        assertEquals(setOf("com.x"), s.pendingRevokes("A"))
        assertTrue(s.pendingRevokes("B").isEmpty())
    }

    @Test
    fun denialCooldownExpiresAfter30s() {
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
    fun stateSurvivesNewInstance() {
        val a = approval()
        store().approve(a)
        store().deny("com.y")
        val s2 = store()
        assertEquals(a, s2.find("A", "com.x"))
        assertEquals(setOf("com.x"), s2.pendingRevokes("A"))
        assertTrue(s2.isInDenialCooldown("com.y"))
    }

    @Test
    fun keyNames() {
        assertEquals("agent:com.x:abcdef01", KeyNames.name("com.x", "abcdef0123456789"))
        assertEquals("agent:com.x:", KeyNames.prefix("com.x"))
        assertNotNull(KeyNames)
    }

    @Test
    fun concurrentInstancesLoseNoUpdates() {
        val stores = listOf(store(), store())
        val n = 40
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val tasks = (0 until n).map { i ->
            java.util.concurrent.Callable {
                val s = stores[i % 2]
                s.approve(approval(pkg = "com.p$i"))
                s.revoke("A", "com.p$i")
                if (i % 2 == 0) s.clearPendingRevoke("A", "com.p$i")
            }
        }
        pool.invokeAll(tasks).forEach { it.get() }
        pool.shutdown()
        val fresh = store()
        assertTrue(fresh.all().isEmpty())
        val expected = (0 until n).filter { it % 2 == 1 }.map { "com.p$it" }.toSet()
        assertEquals(expected, fresh.pendingRevokes("A"))
    }
}

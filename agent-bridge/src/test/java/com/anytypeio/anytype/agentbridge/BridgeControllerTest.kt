package com.anytypeio.anytype.agentbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.anytype.agentbridge.IAgentBridgeCallback
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeControllerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeGateway : LocalApiGateway {
        val listenCalls = mutableListOf<String>()
        val apps = mutableListOf<GatewayApp>()
        val revoked = mutableListOf<String>()
        val created = mutableListOf<Triple<String, Boolean, List<String>?>>()
        var nextPort = 40000
        var failBinds = 0
        var bindDelayMs = 0L
        var failRevoke = false
        private var seq = 0L

        @Synchronized
        override fun setListenAddr(addr: String): String? {
            listenCalls.add(addr)
            if (bindDelayMs > 0) Thread.sleep(bindDelayMs)
            if (addr.isEmpty()) return null
            if (failBinds > 0) {
                failBinds--
                throw IOException("bind failed")
            }
            return "127.0.0.1:${nextPort++}"
        }

        override fun listApps(): List<GatewayApp> = apps.toList()

        @Volatile
        var onCreate: (() -> Unit)? = null

        override fun createApp(name: String, readWrite: Boolean, spaceIds: List<String>?): GatewayApp {
            onCreate?.invoke()
            created.add(Triple(name, readWrite, spaceIds))
            return add(name)
        }

        override fun revokeApp(appHash: String) {
            if (failRevoke) throw IOException("revoke failed")
            revoked.add(appHash)
            apps.removeAll { it.appHash == appHash }
        }

        fun add(name: String, createdAt: Long = 100L + seq): GatewayApp {
            seq++
            val app = GatewayApp("hash$seq", name, "key$seq", createdAt)
            apps.add(app)
            return app
        }
    }

    private class FakeLauncher(var account: AccountRef? = AccountRef("A", "Alice")) : AccountLauncher {
        var startCalls = 0
        var failure: AccountStartException? = null
        var accountOnStart = AccountRef("A", "Alice")

        override fun runningAccount(): AccountRef? = account

        /** Real starts only: calls that found no running account. */
        var realStarts = 0
        var startDelayMs = 0L

        override suspend fun ensureStarted(): AccountRef {
            startCalls++
            failure?.let { throw it }
            account?.let { return it }
            realStarts++
            // A second caller racing here would start the account again.
            if (startDelayMs > 0) delay(startDelayMs)
            return accountOnStart.also { account = it }
        }
    }

    private lateinit var gateway: FakeGateway
    private lateinit var launcher: FakeLauncher
    private lateinit var approvals: AgentApprovals
    private val approved = mutableMapOf<Pair<Int, String>, Approval>()
    private var probeResult = true

    /** False once the last client has unbound. */
    private var bound = true
    private val probed = mutableListOf<String>()

    @Before
    fun setUp() {
        context.getSharedPreferences("agent_bridge", Context.MODE_PRIVATE).edit().clear().commit()
        gateway = FakeGateway()
        launcher = FakeLauncher()
        approvals = AgentApprovals(context)
        approved.clear()
        probeResult = true
        bound = true
        probed.clear()
    }

    private fun approval(
        account: String = "A",
        pkg: String = "com.x",
        cert: String = "abcdef0123456789",
        access: Access = Access.READ_WRITE,
        grant: Grant = Grant.ALL
    ) = Approval(account, pkg, cert, access, grant, 5L)

    /** Approves in the store too, then clears the pending revoke so tests start clean. */
    private fun approve(uid: Int, a: Approval) {
        approved[uid to a.accountId] = a
        approvals.approve(a)
        approvals.clearPendingRevoke(a.accountId, a.packageName)
    }

    private fun TestScope.controller(dispatcher: kotlinx.coroutines.CoroutineDispatcher = StandardTestDispatcher(testScheduler)) =
        BridgeController(
            gateway = gateway,
            launcher = launcher,
            approvals = approvals,
            callerCheck = CallerCheck { uid, accountId -> approved[uid to accountId] },
            approvedAnywhere = ApprovedAnywhere { uid -> approved.keys.any { it.first == uid } },
            idle = { !bound },
            probe = { addr -> probed.add(addr); probeResult },
            dispatcher = dispatcher
        )

    private val keyName = KeyNames.name("com.x", "abcdef0123456789")

    @Test
    fun revokeAgentRemovesApprovalAndKeysAndClearsPending() = runTest {
        approve(1000, approval())
        gateway.add(keyName)
        gateway.add(KeyNames.prefix("com.x") + "old")
        gateway.add(KeyNames.name("com.y", "abcdef0123456789"))
        assertTrue(controller().revokeAgent("A", "com.x"))
        assertEquals(listOf(KeyNames.name("com.y", "abcdef0123456789")), gateway.apps.map { it.appName })
        assertEquals(null, approvals.find("A", "com.x"))
        assertTrue(approvals.pendingRevokes("A").isEmpty())
    }

    @Test
    fun revokeAgentKeepsPendingWhenRevokeFailsOrAccountStopped() = runTest {
        approve(1000, approval())
        gateway.add(keyName)
        gateway.failRevoke = true
        assertEquals(false, controller().revokeAgent("A", "com.x"))
        assertEquals(setOf("com.x"), approvals.pendingRevokes("A"))
        gateway.failRevoke = false
        launcher.account = null
        assertEquals(false, controller().revokeAgent("A", "com.x"))
        assertEquals(1, gateway.apps.size)
        assertEquals(setOf("com.x"), approvals.pendingRevokes("A"))
    }

    @Test
    fun revokeDuringConnectLeavesNoKey() = runBlocking {
        approve(1000, approval())
        val inCreate = CountDownLatch(1)
        val release = CountDownLatch(1)
        gateway.onCreate = { inCreate.countDown(); release.await(5, TimeUnit.SECONDS) }
        val c = BridgeController(
            gateway = gateway, launcher = launcher, approvals = approvals,
            callerCheck = CallerCheck { uid, accountId -> approved[uid to accountId] },
            approvedAnywhere = ApprovedAnywhere { true },
            idle = { false }, probe = { true }, dispatcher = Dispatchers.IO
        )
        val connect = async(Dispatchers.Default) { c.connect(1000) }
        assertTrue(inCreate.await(5, TimeUnit.SECONDS))
        val revoke = async(Dispatchers.Default) { c.revokeAgent("A", "com.x") }
        Thread.sleep(200)
        release.countDown()
        connect.await()
        assertTrue(revoke.await())
        assertTrue(gateway.apps.none { it.appName.startsWith(KeyNames.prefix("com.x")) })
        assertTrue(approvals.pendingRevokes("A").isEmpty())
    }

    @Test
    fun approvalWithdrawnBeforeReadyRevokesKeyAndRequiresApproval() = runTest {
        // The caller check passes (stale), but the store no longer has the approval.
        approved[1000 to "A"] = approval()
        approvals.approve(approval())
        approvals.revoke("A", "com.x")
        val r = controller().connect(1000)
        assertEquals(BridgeController.ConnectResult.ApprovalRequired, r)
        assertTrue(gateway.apps.none { it.appName.startsWith(KeyNames.prefix("com.x")) })
    }

    @Test
    fun unapprovedCallerGetsApprovalRequiredAndNoKey() = runTest {
        val r = controller().connect(1000)
        assertEquals(BridgeController.ConnectResult.ApprovalRequired, r)
        assertTrue(gateway.created.isEmpty())
        assertTrue(gateway.listenCalls.isEmpty())
    }

    @Test
    fun coldStartStartsAccountThenBinds() = runTest {
        launcher.account = null
        approve(1000, approval())
        val r = controller().connect(1000)
        assertEquals(1, launcher.startCalls)
        assertEquals(BridgeController.ConnectResult.Ready("http://127.0.0.1:40000", "key1"), r)
        assertEquals(listOf(BridgeController.LISTEN), gateway.listenCalls)
    }

    @Test
    fun notLoggedInMapsToCode1() = runTest {
        // Only a caller approved under some account gets this far.
        approve(1000, approval())
        launcher.failure = AccountStartException(AccountStartException.NOT_LOGGED_IN, "no account")
        val r = controller().connect(1000)
        val e = assertIs<BridgeController.ConnectResult.Error>(r)
        assertEquals(IAgentBridgeCallback.NOT_LOGGED_IN, e.code)
        assertTrue(gateway.listenCalls.isEmpty())
    }

    @Test
    fun staleListenerIsRebound() = runTest {
        approve(1000, approval())
        val c = controller()
        val first = assertIs<BridgeController.ConnectResult.Ready>(c.connect(1000))
        assertEquals("http://127.0.0.1:40000", first.baseUrl)
        probeResult = false
        val second = assertIs<BridgeController.ConnectResult.Ready>(c.connect(1000))
        assertEquals(listOf("127.0.0.1:40000"), probed)
        assertEquals(listOf(BridgeController.LISTEN, BridgeController.LISTEN), gateway.listenCalls)
        assertEquals("http://127.0.0.1:40001", second.baseUrl)
    }

    @Test
    fun liveListenerIsReused() = runTest {
        approve(1000, approval())
        val c = controller()
        val first = c.connect(1000)
        val second = c.connect(1000)
        assertEquals(first, second)
        assertEquals(1, gateway.listenCalls.size)
        assertEquals(listOf("127.0.0.1:40000"), probed)
    }

    @Test
    fun concurrentConnectsShareOneListener() = runTest {
        approve(1000, approval())
        launcher.account = null
        launcher.startDelayMs = 200
        gateway.bindDelayMs = 300
        // Real threads so the slow bind genuinely overlaps the second connect.
        val c = controller(Dispatchers.IO)
        val a = async { c.connect(1000) }
        val b = async { c.connect(1000) }
        val ra = assertIs<BridgeController.ConnectResult.Ready>(a.await())
        val rb = assertIs<BridgeController.ConnectResult.Ready>(b.await())
        assertEquals(1, launcher.realStarts)
        assertEquals(1, gateway.listenCalls.size)
        assertEquals(ra.baseUrl, rb.baseUrl)
        assertEquals(1, gateway.created.size)
    }

    @Test
    fun failedBindClearsAddressAndRetries() = runTest {
        approve(1000, approval())
        val c = controller()
        assertIs<BridgeController.ConnectResult.Ready>(c.connect(1000))
        probeResult = false
        gateway.failBinds = 1
        val failed = assertIs<BridgeController.ConnectResult.Error>(c.connect(1000))
        assertEquals(IAgentBridgeCallback.API_FAILED, failed.code)
        // Heart closed the old listener on the failed bind: the old address must not be reused
        // even if something answered on it.
        probeResult = true
        probed.clear()
        val ok = assertIs<BridgeController.ConnectResult.Ready>(c.connect(1000))
        assertTrue(probed.isEmpty())
        assertEquals(3, gateway.listenCalls.size)
        assertEquals("http://127.0.0.1:40001", ok.baseUrl)
    }

    @Test
    fun existingKeyIsReused() = runTest {
        approve(1000, approval())
        gateway.add(keyName)
        val r = assertIs<BridgeController.ConnectResult.Ready>(controller().connect(1000))
        assertEquals("key1", r.apiKey)
        assertTrue(gateway.created.isEmpty())
    }

    @Test
    fun duplicateKeysAreDeduped() = runTest {
        approve(1000, approval())
        gateway.add(keyName, createdAt = 10)
        gateway.add(keyName, createdAt = 30)
        gateway.add(keyName, createdAt = 20)
        val r = assertIs<BridgeController.ConnectResult.Ready>(controller().connect(1000))
        assertEquals("key2", r.apiKey)
        assertEquals(setOf("hash1", "hash3"), gateway.revoked.toSet())
        assertTrue(gateway.created.isEmpty())
    }

    @Test
    fun narrowGrantPassesSpaceIds() = runTest {
        approve(1000, approval(grant = Grant(false, listOf("s1", "s2"))))
        assertIs<BridgeController.ConnectResult.Ready>(controller().connect(1000))
        assertEquals<Any>(listOf(Triple(keyName, true, listOf("s1", "s2"))), gateway.created)
    }

    @Test
    fun readOnlyGrantPassesReadWriteFalse() = runTest {
        approve(1000, approval(access = Access.READ_ONLY))
        assertIs<BridgeController.ConnectResult.Ready>(controller().connect(1000))
        assertEquals(listOf<Triple<String, Boolean, List<String>?>>(Triple(keyName, false, null)), gateway.created)
    }

    @Test
    fun pendingRevokeSweepsOldKeys() = runTest {
        val a = approval()
        approve(1000, a)
        val oldCert = gateway.add(KeyNames.name("com.x", "0000000011111111"))
        val current = gateway.add(keyName)
        val other = gateway.add(KeyNames.name("com.xy", "abcdef0123456789"))
        val revokedPkg = gateway.add(KeyNames.name("com.gone", "abcdef0123456789"))
        approvals.approve(a) // re-approval queues a pending revoke for com.x
        approvals.approve(approval(pkg = "com.gone"))
        approvals.revoke("A", "com.gone")
        val r = assertIs<BridgeController.ConnectResult.Ready>(controller().connect(1000))
        // Every key of a pending package goes, the current name included; a fresh key is minted.
        assertEquals(setOf(oldCert.appHash, current.appHash, revokedPkg.appHash), gateway.revoked.toSet())
        assertEquals(1, gateway.created.size)
        val minted = gateway.apps.single { it.appName == keyName }
        assertEquals(minted.appKey, r.apiKey)
        assertTrue(r.apiKey != current.appKey)
        assertTrue(gateway.apps.contains(other))
        assertTrue(approvals.pendingRevokes("A").isEmpty())
    }

    @Test
    fun reapprovalWithNarrowerGrantMintsNewKey() = runTest {
        approve(1000, approval())
        val old = gateway.add(keyName) // minted under the READ_WRITE, all-spaces approval
        approvals.revoke("A", "com.x")
        val narrow = approval(access = Access.READ_ONLY, grant = Grant(false, listOf("s1")))
        approvals.approve(narrow)
        approved[1000 to "A"] = narrow
        val r = assertIs<BridgeController.ConnectResult.Ready>(controller().connect(1000))
        assertEquals(listOf(old.appHash), gateway.revoked)
        assertEquals<Any>(listOf(Triple(keyName, false, listOf("s1"))), gateway.created)
        assertEquals(gateway.apps.single { it.appName == keyName }.appKey, r.apiKey)
        assertTrue(r.apiKey != old.appKey)
        assertTrue(approvals.pendingRevokes("A").isEmpty())
    }

    @Test
    fun failedRevokeOfCallersOldKeyIsNotHandedOut() = runTest {
        approve(1000, approval())
        val old = gateway.add(keyName)
        approvals.approve(approval()) // queues a pending revoke for com.x
        gateway.failRevoke = true
        val e = assertIs<BridgeController.ConnectResult.Error>(controller().connect(1000))
        assertEquals(IAgentBridgeCallback.API_FAILED, e.code)
        assertTrue(gateway.apps.contains(old))
        assertTrue(gateway.created.isEmpty())
        assertEquals(setOf("com.x"), approvals.pendingRevokes("A"))
    }

    @Test
    fun failedSweepRevokeKeepsPendingAndStillConnects() = runTest {
        approve(1000, approval())
        gateway.add(KeyNames.name("com.gone", "abcdef0123456789"))
        approvals.approve(approval(pkg = "com.gone"))
        approvals.revoke("A", "com.gone")
        gateway.failRevoke = true
        assertIs<BridgeController.ConnectResult.Ready>(controller().connect(1000))
        assertEquals(setOf("com.gone"), approvals.pendingRevokes("A"))
    }

    @Test
    fun otherAccountRequiresApproval() = runTest {
        approve(1000, approval(account = "A"))
        launcher.account = AccountRef("B", "Bob")
        val r = controller().connect(1000)
        assertEquals(BridgeController.ConnectResult.ApprovalRequired, r)
        assertTrue(gateway.listenCalls.isEmpty())
        assertTrue(gateway.created.isEmpty())
    }

    @Test
    fun unboundClosesListener() = runTest {
        approve(1000, approval())
        val c = controller()
        assertIs<BridgeController.ConnectResult.Ready>(c.connect(1000))
        bound = false
        c.onAllUnbound()
        assertEquals(listOf(BridgeController.LISTEN, ""), gateway.listenCalls)
        // Nothing cached any more: a second unbind is a no-op, the next connect binds afresh.
        c.onAllUnbound()
        assertEquals(2, gateway.listenCalls.size)
        bound = true
        probed.clear()
        assertIs<BridgeController.ConnectResult.Ready>(c.connect(1000))
        assertTrue(probed.isEmpty())
        assertEquals(3, gateway.listenCalls.size)
    }

    @Test
    fun unbindWhileAnotherBindingRemainsKeepsListener() = runTest {
        approve(1000, approval())
        val c = controller()
        assertIs<BridgeController.ConnectResult.Ready>(c.connect(1000))
        // bound stays true: the idle check runs under the mutex and sees a live binding.
        c.onAllUnbound()
        assertEquals(listOf(BridgeController.LISTEN), gateway.listenCalls)
    }

    @Test
    fun connectFinishingAfterLastUnbindClosesListener() = runTest {
        approve(1000, approval())
        // The last client unbound while this connect was queued or running.
        bound = false
        assertIs<BridgeController.ConnectResult.Ready>(controller().connect(1000))
        assertEquals(listOf(BridgeController.LISTEN, ""), gateway.listenCalls)
    }

    @Test
    fun unapprovedCallerDoesNotStartAccount() = runTest {
        launcher.account = null
        launcher.failure = AccountStartException(AccountStartException.NOT_LOGGED_IN, "no account")
        val r = controller().connect(1000)
        assertEquals(BridgeController.ConnectResult.ApprovalRequired, r)
        assertEquals(0, launcher.startCalls)
        assertTrue(gateway.listenCalls.isEmpty())
    }

    @Test
    fun listAppsFailureMapsToApiFailed() = runTest {
        approve(1000, approval())
        val c = BridgeController(
            gateway = object : LocalApiGateway by gateway {
                override fun listApps(): List<GatewayApp> = throw IOException("down")
            },
            launcher = launcher,
            approvals = approvals,
            callerCheck = { uid, acc -> approved[uid to acc] },
            approvedAnywhere = { true },
            idle = { false },
            probe = { true },
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        val e = assertIs<BridgeController.ConnectResult.Error>(c.connect(1000))
        assertEquals(IAgentBridgeCallback.API_FAILED, e.code)
    }
}

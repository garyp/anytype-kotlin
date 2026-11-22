package com.anytypeio.anytype.agentbridge

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.anytype.agentbridge.IAgentBridge
import io.anytype.agentbridge.AgentEndpoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentBridgeServiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeGateway : LocalApiGateway {
        val listenCalls = mutableListOf<String>()
        private var seq = 0
        private var port = 40000

        @Synchronized
        override fun setListenAddr(addr: String): String? {
            listenCalls.add(addr)
            return if (addr.isEmpty()) null else "127.0.0.1:${port++}"
        }

        override fun listApps(): List<GatewayApp> = emptyList()

        override fun createApp(name: String, readWrite: Boolean, spaceIds: List<String>?): GatewayApp {
            seq++
            return GatewayApp("hash$seq", name, "key$seq", seq.toLong())
        }

        override fun revokeApp(appHash: String) = Unit
    }

    private class FakeLauncher : AccountLauncher {
        override fun runningAccount() = AccountRef("A", "Alice")
        override suspend fun ensureStarted() = AccountRef("A", "Alice")
        override fun generation() = 1L
    }

    private class FakeSpaces : SpaceDirectory {
        override suspend fun activeSpaces(): List<SpaceRef> = emptyList()
    }

    private lateinit var gateway: FakeGateway
    private val approved = mutableMapOf<Int, Approval>()
    private val intent = Intent(AgentBridge.ACTION_BIND)

    /** Not [Intent.filterEquals] to [intent]: the system calls onBind/onUnbind for it separately. */
    private val otherIntent = Intent(AgentBridge.ACTION_BIND).addCategory("com.example.OTHER")
    private lateinit var service: ServiceController<AgentBridgeService>

    @Before
    fun setUp() {
        AgentBridge.resetForTest()
        gateway = FakeGateway()
        approved.clear()
        AgentBridge.callerCheckOverride = CallerCheck { uid, _ -> approved[uid] }
        AgentBridge.approvedAnywhereOverride = ApprovedAnywhere { uid -> approved.containsKey(uid) }
        service = Robolectric.buildService(AgentBridgeService::class.java, intent).create()
    }

    @After
    fun tearDown() {
        AgentBridge.resetForTest()
    }

    private fun install() = AgentBridge.install(gateway, FakeLauncher(), FakeSpaces())

    /** What the system does for the first client binding with [i]. */
    private fun bind(i: Intent = intent): IAgentBridge = IAgentBridge.Stub.asInterface(service.get().onBind(i))

    /**
     * What the system does once the last client binding with [i] has gone. The service asks for
     * onRebind, so the system calls back on every later bind with [i] while the service lives.
     */
    private fun unbind(i: Intent = intent) = assertTrue(service.get().onUnbind(i))

    /** What the system does for a new bind with [i] after an onUnbind that returned true. */
    private fun rebind(i: Intent = intent) = service.get().onRebind(i)

    /** Calls connect from a background thread, as a remote binder thread would, as [uid]. */
    private fun call(bridge: IAgentBridge, uid: Int, timeoutMs: Long = 5_000): AgentEndpoint {
        var result: AgentEndpoint? = null
        var error: Throwable? = null
        val t = Thread {
            try {
                ShadowBinder.setCallingUid(uid)
                result = bridge.connect()
            } catch (e: Throwable) {
                error = e
            }
        }
        t.start()
        t.join(timeoutMs)
        assertFalse(t.isAlive, "connect did not return within $timeoutMs ms")
        error?.let { throw it }
        return checkNotNull(result)
    }

    private fun connect(bridge: IAgentBridge, uid: Int): String {
        val e = call(bridge, uid)
        return if (e.code == IAgentBridge.OK) {
            "ready ${e.baseUrl} ${e.apiKey}"
        } else {
            assertNull(e.baseUrl)
            assertNull(e.apiKey)
            "error ${e.code}"
        }
    }

    private fun approve(uid: Int) {
        val a = Approval("A", "com.x", "abcdef", Access.READ_WRITE, Grant.ALL, 1L)
        approved[uid] = a
        // Connect re-checks the store before handing out a key.
        val store = AgentApprovals(ApplicationProvider.getApplicationContext())
        store.approve(a)
        store.clearPendingRevoke("A", "com.x")
    }

    private fun listenCalls() = synchronized(gateway) { gateway.listenCalls.toList() }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun connectReturnsEndpoint() {
        install()
        approve(10_123)
        assertEquals("ready http://127.0.0.1:40000 key1", connect(bind(), 10_123))
    }

    @Test
    fun connectReturnsApprovalRequired() {
        install()
        assertEquals("error ${IAgentBridge.APPROVAL_REQUIRED}", connect(bind(), 10_124))
    }

    @Test
    fun callingUidIsReadOnTheBinderThread() {
        install()
        approve(10_123)
        val b = bind()
        // Only 10_123 is approved; the uid must be the binder caller's, not the IO thread's.
        assertEquals("error ${IAgentBridge.APPROVAL_REQUIRED}", connect(b, 10_999))
        assertTrue(connect(b, 10_123).startsWith("ready "))
    }

    @Test
    fun notInstalledReturnsInternal() {
        assertEquals("error ${IAgentBridge.INTERNAL}", connect(bind(), 10_123))
    }

    @Test
    fun errorMessagesStayGeneric() {
        AgentBridge.install(object : LocalApiGateway by gateway {
            override fun setListenAddr(addr: String): String? =
                throw IllegalStateException("secret internal detail")
        }, FakeLauncher(), FakeSpaces())
        approve(10_123)
        val e = call(bind(), 10_123)
        assertEquals(IAgentBridge.API_FAILED, e.code)
        // Nothing but the code reaches the caller.
        assertNull(e.baseUrl)
        assertNull(e.apiKey)
    }

    @Test
    fun unapprovedCallerReturnsImmediatelyWithoutStarting() {
        val release = CountDownLatch(1)
        val started = AtomicBoolean(false)
        AgentBridge.install(gateway, object : AccountLauncher {
            override fun runningAccount(): AccountRef? = null
            override suspend fun ensureStarted(): AccountRef {
                started.set(true)
                release.await()
                return AccountRef("A", "Alice")
            }
            override fun generation() = 1L
        }, FakeSpaces())
        try {
            val e = call(bind(), 10_555, timeoutMs = 1_000)
            assertEquals(IAgentBridge.APPROVAL_REQUIRED, e.code)
            assertFalse(started.get(), "an unapproved caller started the account")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun unapprovedCallerDoesNotWaitForTheControllerMutex() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        AgentBridge.install(gateway, object : AccountLauncher {
            override fun runningAccount() = AccountRef("A", "Alice")
            override suspend fun ensureStarted(): AccountRef {
                inside.countDown()
                release.await()
                return AccountRef("A", "Alice")
            }
            override fun generation() = 1L
        }, FakeSpaces())
        approve(10_123)
        val b = bind()
        // An approved connect holds the controller mutex, blocked in the account start.
        val holder = Thread {
            ShadowBinder.setCallingUid(10_123)
            b.connect()
        }.apply { start() }
        try {
            assertTrue(inside.await(5, TimeUnit.SECONDS))
            assertEquals(IAgentBridge.APPROVAL_REQUIRED, call(b, 10_555, timeoutMs = 1_000).code)
        } finally {
            release.countDown()
            holder.join(5_000)
        }
    }

    @Test
    fun versionReturnsTheInterfaceVersion() {
        assertEquals(IAgentBridge.VERSION, bind().version())
    }

    @Test
    fun unbindClosesListener() {
        install()
        approve(10_123)
        connect(bind(), 10_123)
        unbind()
        waitUntil { "" in listenCalls() }
        assertEquals(listOf(BridgeController.LISTEN, ""), listenCalls())
    }

    @Test
    fun unbindOfOneIntentKeepsListenerWhileAnotherIntentIsBound() {
        val queued = StandardTestDispatcher()
        AgentBridge.unbindDispatcher = queued
        install()
        approve(10_123)
        val a = bind(intent)
        bind(otherIntent)
        assertTrue(connect(a, 10_123).startsWith("ready "))
        // Another app (no approval needed to bind) unbinds its own, different intent.
        unbind(otherIntent)
        queued.scheduler.advanceUntilIdle()
        Thread.sleep(100)
        assertEquals(listOf(BridgeController.LISTEN), listenCalls())
        // Once no binding of any kind remains, the listener closes.
        unbind(intent)
        queued.scheduler.advanceUntilIdle()
        waitUntil { "" in listenCalls() }
    }

    @Test
    fun rebindIsCountedSoAnotherIntentsUnbindKeepsListener() {
        val queued = StandardTestDispatcher()
        AgentBridge.unbindDispatcher = queued
        install()
        approve(10_123)
        val x = bind(intent)
        bind(otherIntent)
        // The agent drops X and binds it again; Y keeps the service alive, so the system rebinds.
        unbind(intent)
        rebind(intent)
        assertTrue(connect(x, 10_123).startsWith("ready "))
        // The other app unbinds Y.
        unbind(otherIntent)
        queued.scheduler.advanceUntilIdle()
        Thread.sleep(100)
        assertEquals(listOf(BridgeController.LISTEN), listenCalls())
        // The agent goes too: now nothing is bound.
        unbind(intent)
        queued.scheduler.advanceUntilIdle()
        waitUntil { "" in listenCalls() }
    }

    @Test
    fun destroyClosesListener() {
        val queued = StandardTestDispatcher()
        AgentBridge.unbindDispatcher = queued
        install()
        approve(10_123)
        connect(bind(), 10_123)
        unbind()
        // The system destroys the service before the queued close gets to run.
        service.destroy()
        queued.scheduler.advanceUntilIdle()
        waitUntil { "" in listenCalls() }
    }

    @Test
    fun bindAfterUnbindDecisionKeepsListener() {
        val queued = StandardTestDispatcher()
        AgentBridge.unbindDispatcher = queued
        install()
        approve(10_123)
        val b = bind()
        connect(b, 10_123)
        // The last client unbinds; the close is decided but has not run yet.
        unbind()
        // A new client binds (the system rebinds and hands out the cached binder) and connects
        // before the close runs.
        rebind()
        assertTrue(connect(b, 10_123).startsWith("ready "))
        queued.scheduler.advanceUntilIdle()
        Thread.sleep(100)
        assertFalse("" in listenCalls(), "listener closed under a new client")
    }

    @Test
    fun connectFinishingAfterLastUnbindClosesListener() {
        val queued = StandardTestDispatcher()
        AgentBridge.unbindDispatcher = queued
        install()
        approve(10_123)
        val b = bind()
        // The last binding goes before the connect runs; its close finds nothing to close.
        unbind()
        queued.scheduler.advanceUntilIdle()
        assertTrue(connect(b, 10_123).startsWith("ready "))
        // The connect opened a listener nobody holds: it closes it itself.
        assertEquals(listOf(BridgeController.LISTEN, ""), listenCalls())
    }

    @Test
    @Config(sdk = [34])
    fun componentsDisabledBelowApi35() {
        assertFalse(context.resources.getBoolean(R.bool.agent_bridge_enabled))
    }

    @Test
    fun componentsEnabledOnApi35() {
        assertTrue(context.resources.getBoolean(R.bool.agent_bridge_enabled))
    }
}

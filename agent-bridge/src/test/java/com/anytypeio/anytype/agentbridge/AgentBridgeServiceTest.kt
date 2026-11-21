package com.anytypeio.anytype.agentbridge

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.anytype.agentbridge.IAgentBridge
import io.anytype.agentbridge.IAgentBridgeCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    }

    private class FakeSpaces : SpaceDirectory {
        override suspend fun activeSpaces(): List<SpaceRef> = emptyList()
    }

    private class Recorder : IAgentBridgeCallback.Stub() {
        val latch = CountDownLatch(1)
        val events = mutableListOf<String>()

        override fun onReady(baseUrl: String, apiKey: String) = record("ready $baseUrl $apiKey")
        override fun onApprovalRequired() = record("approval")
        override fun onError(code: Int, message: String) = record("error $code $message")

        private fun record(e: String) {
            synchronized(events) { events.add(e) }
            latch.countDown()
        }

        fun await(): String {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "no callback")
            return synchronized(events) { events.single() }
        }
    }

    private lateinit var gateway: FakeGateway
    private val approved = mutableMapOf<Int, Approval>()
    private val intent = Intent(AgentBridge.ACTION_BIND)
    private lateinit var service: ServiceController<AgentBridgeService>

    @Before
    fun setUp() {
        AgentBridge.resetForTest()
        gateway = FakeGateway()
        approved.clear()
        AgentBridge.callerCheckOverride = CallerCheck { uid, _ -> approved[uid] }
        service = Robolectric.buildService(AgentBridgeService::class.java, intent).create()
    }

    @After
    fun tearDown() {
        AgentBridge.resetForTest()
    }

    private fun install() = AgentBridge.install(gateway, FakeLauncher(), FakeSpaces())

    private fun bridge(): IAgentBridge = IAgentBridge.Stub.asInterface(service.get().onBind(intent))

    private fun connect(uid: Int): String {
        val rec = Recorder()
        ShadowBinder.setCallingUid(uid)
        bridge().connect(rec)
        return rec.await()
    }

    private fun approve(uid: Int) {
        approved[uid] = Approval("A", "com.x", "abcdef", Access.READ_WRITE, Grant.ALL, 1L)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun connectDeliversOnReady() {
        install()
        approve(10_123)
        assertEquals("ready http://127.0.0.1:40000 key1", connect(10_123))
    }

    @Test
    fun connectDeliversApprovalRequired() {
        install()
        assertEquals("approval", connect(10_124))
    }

    @Test
    fun callingUidIsReadOnTheBinderThread() {
        install()
        approve(10_123)
        // Only 10_123 is approved; the uid must be the binder caller's, not the IO thread's.
        assertEquals("approval", connect(10_999))
        assertTrue(connect(10_123).startsWith("ready "))
    }

    @Test
    fun notInstalledDeliversInternalError() {
        assertEquals("error ${IAgentBridgeCallback.INTERNAL} bridge not installed", connect(10_123))
    }

    @Test
    fun errorMessagesAreGeneric() {
        AgentBridge.install(object : LocalApiGateway by gateway {
            override fun setListenAddr(addr: String): String? =
                throw IllegalStateException("secret internal detail")
        }, FakeLauncher(), FakeSpaces())
        approve(10_123)
        val r = connect(10_123)
        assertTrue(r.startsWith("error ${IAgentBridgeCallback.API_FAILED} "), r)
        assertFalse(r.contains("secret"), r)
    }

    @Test
    fun throwingCallbackIsSwallowed() {
        install()
        approve(10_123)
        val latch = CountDownLatch(1)
        ShadowBinder.setCallingUid(10_123)
        bridge().connect(object : IAgentBridgeCallback.Stub() {
            override fun onReady(baseUrl: String, apiKey: String) {
                latch.countDown()
                throw android.os.RemoteException("client died")
            }
            override fun onApprovalRequired() = Unit
            override fun onError(code: Int, message: String) = Unit
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // The service still answers afterwards.
        assertTrue(connect(10_123).startsWith("ready "))
    }

    @Test
    fun unbindClosesListener() {
        install()
        approve(10_123)
        connect(10_123)
        assertFalse(service.get().onUnbind(intent))
        waitUntil { synchronized(gateway) { gateway.listenCalls.contains("") } }
        assertEquals(listOf(BridgeController.LISTEN, ""), synchronized(gateway) { gateway.listenCalls.toList() })
    }

    @Test
    fun unbindClosesListenerEvenWhenServiceIsDestroyed() {
        val queued = StandardTestDispatcher()
        AgentBridge.unbindDispatcher = queued
        install()
        approve(10_123)
        connect(10_123)
        service.get().onUnbind(intent)
        // The system destroys the service before the queued close gets to run.
        service.destroy()
        queued.scheduler.advanceUntilIdle()
        waitUntil { synchronized(gateway) { gateway.listenCalls.contains("") } }
    }

    @Test
    fun bindAfterUnbindDecisionKeepsListener() {
        val queued = StandardTestDispatcher()
        AgentBridge.unbindDispatcher = queued
        install()
        approve(10_123)
        connect(10_123)
        // The last client unbinds; the close is decided but has not run yet.
        service.get().onUnbind(intent)
        // A new client binds and connects before it runs.
        assertTrue(connect(10_123).startsWith("ready "))
        queued.scheduler.advanceUntilIdle()
        Thread.sleep(100)
        assertFalse(synchronized(gateway) { gateway.listenCalls.contains("") }, "listener closed under a new client")
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

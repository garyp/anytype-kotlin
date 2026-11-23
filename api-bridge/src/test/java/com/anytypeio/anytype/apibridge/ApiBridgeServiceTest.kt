package com.anytypeio.anytype.apibridge

import android.content.Context
import android.content.Intent
import android.os.DeadObjectException
import androidx.test.core.app.ApplicationProvider
import io.anytype.apibridge.ApiEndpoint
import io.anytype.apibridge.IApiBridge
import io.anytype.apibridge.IConnectCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApiBridgeServiceTest {

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

        override fun createApp(name: String, readWrite: Boolean, spaceIds: List<String>?): String {
            seq++
            return "key$seq"
        }

        override fun revokeApp(appHash: String) = Unit
    }

    private class FakeLauncher(override val accountStops: Flow<Unit> = emptyFlow()) : AccountLauncher {
        override fun runningAccount() = AccountRef("A", "Alice")
        override suspend fun ensureStarted() = AccountRef("A", "Alice")
    }

    private class FakeSpaces : SpaceDirectory {
        override suspend fun activeSpaces(): List<SpaceRef> = emptyList()
    }

    private lateinit var gateway: FakeGateway
    private val approved = mutableMapOf<Int, Approval>()
    private val intent = Intent(ApiBridge.ACTION_BIND)

    /** Not [Intent.filterEquals] to [intent]: the system calls onBind/onUnbind for it separately. */
    private val otherIntent = Intent(ApiBridge.ACTION_BIND).addCategory("com.example.OTHER")
    private lateinit var service: ServiceController<ApiBridgeService>

    /**
     * In memory: nothing in it dispatches, so the unbind close stays on the queued test
     * dispatchers below. [everyUserSharesOneApprovalsStore] covers the file-backed store.
     */
    private lateinit var store: AppApprovals

    @Before
    fun setUp() {
        ApiBridge.resetForTest()
        store = AppApprovals(InMemoryDataStore(ApprovalsState()))
        ApiBridge.approvalsOverride = store
        gateway = FakeGateway()
        approved.clear()
        ApiBridge.callerApprovalsOverride = CallerApprovals { uid -> listOfNotNull(approved[uid]) }
        service = Robolectric.buildService(ApiBridgeService::class.java, intent).create()
    }

    @After
    fun tearDown() {
        ApiBridge.resetForTest()
    }

    private fun install() = ApiBridge.install(gateway, FakeLauncher(), FakeSpaces())

    /** What the system does for the first client binding with [i]. */
    private fun bind(i: Intent = intent): IApiBridge = IApiBridge.Stub.asInterface(service.get().onBind(i))

    /**
     * What the system does once the last client binding with [i] has gone. The service asks for
     * onRebind, so the system calls back on every later bind with [i] while the service lives.
     */
    private fun unbind(i: Intent = intent) = assertTrue(service.get().onUnbind(i))

    /** What the system does for a new bind with [i] after an onUnbind that returned true. */
    private fun rebind(i: Intent = intent) = service.get().onRebind(i)

    /** Records the answers a caller receives. */
    private class Answers : IConnectCallback.Stub() {
        val received = LinkedBlockingQueue<ApiEndpoint>()
        override fun onResult(endpoint: ApiEndpoint) {
            received.add(endpoint)
        }

        fun await(timeoutMs: Long = 5_000): ApiEndpoint =
            checkNotNull(received.poll(timeoutMs, TimeUnit.MILLISECONDS)) { "no answer within $timeoutMs ms" }
    }

    /**
     * Sends connect as [uid], as a remote binder thread would, and checks that the binder call
     * itself returns at once (oneway: no binder thread waits for the answer).
     */
    private fun send(bridge: IApiBridge, uid: Int, answers: IConnectCallback = Answers()): IConnectCallback {
        var error: Throwable? = null
        val t = Thread {
            try {
                ShadowBinder.setCallingUid(uid)
                bridge.connect(answers)
            } catch (e: Throwable) {
                error = e
            }
        }
        t.start()
        t.join(1_000)
        assertFalse(t.isAlive, "the binder call did not return at once")
        error?.let { throw it }
        return answers
    }

    /** Sends connect as [uid] and waits for its answer. */
    private fun call(bridge: IApiBridge, uid: Int, timeoutMs: Long = 5_000): ApiEndpoint =
        (send(bridge, uid) as Answers).await(timeoutMs)

    private fun connect(bridge: IApiBridge, uid: Int): String {
        val e = call(bridge, uid)
        return if (e.code == IApiBridge.OK) {
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
        runBlocking {
            store.approve(a)
            store.clearPendingRevoke("A", "com.x")
        }
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
        assertEquals("error ${IApiBridge.APPROVAL_REQUIRED}", connect(bind(), 10_124))
    }

    @Test
    fun callingUidIsReadOnTheBinderThread() {
        install()
        approve(10_123)
        val b = bind()
        // Only 10_123 is approved; the uid must be the binder caller's, not the IO thread's.
        assertEquals("error ${IApiBridge.APPROVAL_REQUIRED}", connect(b, 10_999))
        assertTrue(connect(b, 10_123).startsWith("ready "))
    }

    @Test
    fun notInstalledReturnsInternal() {
        assertEquals("error ${IApiBridge.INTERNAL}", connect(bind(), 10_123))
    }

    @Test
    fun errorMessagesStayGeneric() {
        ApiBridge.install(object : LocalApiGateway by gateway {
            override fun setListenAddr(addr: String): String? =
                throw IllegalStateException("secret internal detail")
        }, FakeLauncher(), FakeSpaces())
        approve(10_123)
        val e = call(bind(), 10_123)
        assertEquals(IApiBridge.API_FAILED, e.code)
        // Nothing but the code reaches the caller.
        assertNull(e.baseUrl)
        assertNull(e.apiKey)
    }

    @Test
    fun unapprovedCallerReturnsImmediatelyWithoutStarting() {
        val release = CountDownLatch(1)
        val started = AtomicBoolean(false)
        ApiBridge.install(gateway, object : AccountLauncher {
            override fun runningAccount(): AccountRef? = null
            override suspend fun ensureStarted(): AccountRef {
                started.set(true)
                release.await()
                return AccountRef("A", "Alice")
            }
            override val accountStops: Flow<Unit> = emptyFlow()
        }, FakeSpaces())
        try {
            val e = call(bind(), 10_555, timeoutMs = 1_000)
            assertEquals(IApiBridge.APPROVAL_REQUIRED, e.code)
            assertFalse(started.get(), "an unapproved caller started the account")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun unapprovedCallerDoesNotWaitForTheControllerMutex() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        ApiBridge.install(gateway, object : AccountLauncher {
            override fun runningAccount() = AccountRef("A", "Alice")
            override suspend fun ensureStarted(): AccountRef {
                inside.countDown()
                release.await()
                return AccountRef("A", "Alice")
            }
            override val accountStops: Flow<Unit> = emptyFlow()
        }, FakeSpaces())
        approve(10_123)
        val b = bind()
        // An approved connect holds the controller mutex, blocked in the account start.
        val holder = send(b, 10_123) as Answers
        try {
            assertTrue(inside.await(5, TimeUnit.SECONDS))
            assertEquals(IApiBridge.APPROVAL_REQUIRED, call(b, 10_555, timeoutMs = 1_000).code)
        } finally {
            release.countDown()
        }
        assertEquals(IApiBridge.OK, holder.await().code)
    }

    @Test
    fun slowConnectHoldsNoBinderThreadAndAnswersWhenReady() {
        val release = CountDownLatch(1)
        ApiBridge.install(gateway, object : AccountLauncher {
            override fun runningAccount() = AccountRef("A", "Alice")
            override suspend fun ensureStarted(): AccountRef {
                release.await()
                return AccountRef("A", "Alice")
            }
            override val accountStops: Flow<Unit> = emptyFlow()
        }, FakeSpaces())
        approve(10_123)
        // send() asserts that the binder call returns while the account start still blocks.
        val answers = send(bind(), 10_123) as Answers
        try {
            Thread.sleep(200)
            assertTrue(answers.received.isEmpty(), "answered before the connect finished")
        } finally {
            release.countDown()
        }
        assertEquals("key1", answers.await().apiKey)
    }

    @Test
    fun callerGoneBeforeTheAnswerIsIgnored() {
        install()
        approve(10_123)
        val b = bind()
        val calls = AtomicInteger()
        val gone = object : IConnectCallback.Stub() {
            override fun onResult(endpoint: ApiEndpoint) {
                calls.incrementAndGet()
                throw DeadObjectException()
            }
        }
        // Neither the fast path nor the background answer lets the exception escape.
        send(b, 10_999, gone)
        send(b, 10_123, gone)
        waitUntil { calls.get() == 2 && ApiBridge.inFlightConnects.isEmpty() }
        assertTrue(connect(b, 10_123).startsWith("ready "))
    }

    @Test
    fun cancelledConnectAnswersInternal() {
        ApiBridge.install(gateway, object : AccountLauncher {
            override fun runningAccount() = AccountRef("A", "Alice")
            override suspend fun ensureStarted(): AccountRef = awaitCancellation()
            override val accountStops: Flow<Unit> = emptyFlow()
        }, FakeSpaces())
        approve(10_123)
        val answers = send(bind(), 10_123) as Answers
        waitUntil { ApiBridge.inFlightConnects.isNotEmpty() }
        // What resetting the background scope does to a connect in flight.
        ApiBridge.inFlightConnects.values.single().cancel()
        assertEquals(IApiBridge.INTERNAL, answers.await().code)
        waitUntil { ApiBridge.inFlightConnects.isEmpty() }
    }

    @Test
    fun concurrentConnectsFromOneUidShareOneControllerConnect() {
        val release = CountDownLatch(1)
        val starts = AtomicInteger()
        ApiBridge.install(gateway, object : AccountLauncher {
            override fun runningAccount() = AccountRef("A", "Alice")
            override suspend fun ensureStarted(): AccountRef {
                starts.incrementAndGet()
                release.await()
                return AccountRef("A", "Alice")
            }
            override val accountStops: Flow<Unit> = emptyFlow()
        }, FakeSpaces())
        approve(10_123)
        val b = bind()
        val callers = List(5) { send(b, 10_123) as Answers }
        val results = try {
            waitUntil { starts.get() >= 1 }
            Thread.sleep(200)
            assertEquals(1, ApiBridge.inFlightConnects.size)
            release.countDown()
            callers.map { it.await() }
        } finally {
            release.countDown()
        }
        assertEquals(1, starts.get(), "concurrent calls from one uid ran more than one connect")
        assertEquals(5, results.size)
        assertTrue(results.all { it.code == IApiBridge.OK && it.apiKey == "key1" })
        waitUntil { ApiBridge.inFlightConnects.isEmpty() }
        // Once it has finished, a later call runs a connect of its own.
        assertEquals(IApiBridge.OK, call(b, 10_123).code)
        assertEquals(2, starts.get())
    }

    /** DataStore allows one instance per file: every user shares the holder's store. */
    @Test
    fun everyUserSharesOneApprovalsStore() {
        ApiBridge.approvalsOverride = null
        val first = ApiBridge.approvals(context)
        assertSame(first, ApiBridge.approvals(context))
        runBlocking { first.deny("com.shared") }
        // The reset closes the file; a new store on it reads what the first one wrote.
        ApiBridge.resetForTest()
        val second = ApiBridge.approvals(context)
        assertNotSame(first, second)
        assertTrue(runBlocking { second.isInDenialCooldown("com.shared") })
    }

    @Test
    fun versionReturnsTheInterfaceVersion() {
        assertEquals(IApiBridge.VERSION, bind().version())
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
        ApiBridge.unbindDispatcher = queued
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
        ApiBridge.unbindDispatcher = queued
        install()
        approve(10_123)
        val x = bind(intent)
        bind(otherIntent)
        // The app drops X and binds it again; Y keeps the service alive, so the system rebinds.
        unbind(intent)
        rebind(intent)
        assertTrue(connect(x, 10_123).startsWith("ready "))
        // The other app unbinds Y.
        unbind(otherIntent)
        queued.scheduler.advanceUntilIdle()
        Thread.sleep(100)
        assertEquals(listOf(BridgeController.LISTEN), listenCalls())
        // The app goes too: now nothing is bound.
        unbind(intent)
        queued.scheduler.advanceUntilIdle()
        waitUntil { "" in listenCalls() }
    }

    @Test
    fun destroyClosesListener() {
        val queued = StandardTestDispatcher()
        ApiBridge.unbindDispatcher = queued
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
        ApiBridge.unbindDispatcher = queued
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
        ApiBridge.unbindDispatcher = queued
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
        assertFalse(context.resources.getBoolean(R.bool.api_bridge_enabled))
    }

    @Test
    fun componentsEnabledOnApi35() {
        assertTrue(context.resources.getBoolean(R.bool.api_bridge_enabled))
    }

    /** A logout reported through [AccountLauncher.accountStops] makes the next connect bind afresh. */
    @Test
    fun accountStopReachesTheControllerAndTheNextConnectRebinds() {
        val stops = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        ApiBridge.install(gateway, FakeLauncher(stops), FakeSpaces())
        approve(1000)
        val bridge = bind()
        assertEquals("http://127.0.0.1:40000", call(bridge, 1000).baseUrl)
        assertEquals("http://127.0.0.1:40000", call(bridge, 1000).baseUrl)
        waitUntil { stops.subscriptionCount.value > 0 }
        stops.tryEmit(Unit)
        waitUntil {
            call(bridge, 1000).baseUrl == "http://127.0.0.1:40001"
        }
        assertEquals(listOf(BridgeController.LISTEN, BridgeController.LISTEN), listenCalls())
    }
}

package io.anytype.apibridge.client

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import io.anytype.apibridge.ApiEndpoint
import io.anytype.apibridge.IApiBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnytypeBridgeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Records each bind, and can make one throw, before Robolectric's shadow handles it. */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var lastFlags = 0
        var lastConnection: ServiceConnection? = null
        var bindCount = 0
        var bindThrows: RuntimeException? = null

        override fun bindService(
            service: Intent,
            flags: Int,
            executor: Executor,
            conn: ServiceConnection
        ): Boolean {
            lastFlags = flags
            lastConnection = conn
            bindCount++
            bindThrows?.let { throw it }
            return super.bindService(service, flags, executor, conn)
        }

        // AnytypeBridge stores context.applicationContext; keep it pointing at this wrapper.
        override fun getApplicationContext(): Context = this
    }

    private val ctx = RecordingContext(app)

    /**
     * Runs [block] off the main thread while this thread keeps idling the (paused) main looper,
     * which is where the shadowed onServiceConnected callback is posted. A plain runBlocking on
     * the test thread would block that looper and never complete.
     */
    private fun <T> connectWithLooper(onTick: () -> Unit = {}, block: suspend () -> T): T {
        val scope = CoroutineScope(Dispatchers.Default)
        val result = scope.async { block() }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!result.isCompleted) {
            onTick()
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "connect() did not complete" }
            Thread.sleep(5)
        }
        return runBlocking { result.await() }
    }
    private val pkg = AnytypeBridge.DEFAULT_PACKAGE

    /** Registers a fake bridge service whose connect() runs [block]: return or throw. */
    private fun install(block: () -> ApiEndpoint?) {
        val stub = object : IApiBridge.Stub() {
            override fun connect(): ApiEndpoint? = block()
            override fun version(): Int = IApiBridge.VERSION
        }
        val intent = Intent(AnytypeBridge.BIND_ACTION).setPackage(pkg)
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            intent, ComponentName(pkg, "$pkg.BridgeService"), stub
        )
    }

    private fun endpoint() = ApiEndpoint(IApiBridge.OK, "http://127.0.0.1:1", "k")

    private fun error(code: Int) = ApiEndpoint(code, null, null)

    @Test
    fun connectReturnsEndpoint() {
        install { endpoint() }
        val bridge = AnytypeBridge(ctx)
        assertEquals(Endpoint("http://127.0.0.1:1", "k"), connectWithLooper { bridge.connect() })
        bridge.close()
    }

    @Test
    fun approvalRequiredCodeMapsToApprovalRequired() {
        install { error(IApiBridge.APPROVAL_REQUIRED) }
        val bridge = AnytypeBridge(ctx)
        assertThrows(BridgeException.ApprovalRequired::class.java) { connectWithLooper { bridge.connect() } }
        bridge.close()
    }

    @Test
    fun errorCodeMapsToFailed() {
        install { error(IApiBridge.API_FAILED) }
        val bridge = AnytypeBridge(ctx)
        val e = assertThrows(BridgeException.Failed::class.java) { connectWithLooper { bridge.connect() } }
        assertEquals(3, e.code)
        bridge.close()
    }

    @Test
    fun okReplyWithNullFieldIsMalformed() {
        install { ApiEndpoint(IApiBridge.OK, "http://127.0.0.1:1", null) }
        val bridge = AnytypeBridge(ctx)
        val e = assertThrows(BridgeException.Failed::class.java) { connectWithLooper { bridge.connect() } }
        assertEquals(IApiBridge.INTERNAL, e.code)
        assertTrue(e.message!!.endsWith(": malformed reply"))
        bridge.close()
    }

    @Test
    fun nullReplyMeansAnIncompatibleBridge() {
        // What an Anytype with another IApiBridge (e.g. too old) answers: no reply parcel.
        install { null }
        val bridge = AnytypeBridge(ctx)
        val e = assertThrows(BridgeException.Failed::class.java) { connectWithLooper { bridge.connect() } }
        assertEquals(IApiBridge.INTERNAL, e.code)
        assertTrue(e.message!!.endsWith(": no reply: Anytype bridge too old or incompatible"))
        bridge.close()
    }

    @Test
    fun errorEndpointSurvivesAParcelRoundTrip() {
        val parcel = Parcel.obtain()
        try {
            ApiEndpoint(IApiBridge.APPROVAL_REQUIRED, null, null).writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val read = ApiEndpoint.CREATOR.createFromParcel(parcel)
            assertEquals(IApiBridge.APPROVAL_REQUIRED, read.code)
            assertNull(read.baseUrl)
            assertNull(read.apiKey)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun endpointToStringRedactsTheKey() {
        val text = Endpoint("http://127.0.0.1:1", "secret-key").toString()
        assertFalse(text, "secret-key" in text)
        assertTrue(text, "http://127.0.0.1:1" in text)
    }

    @Test(timeout = 30_000)
    fun serviceDeathDuringConnectFailsThenRebinds() {
        var dead = true
        install { if (dead) throw DeadObjectException() else endpoint() }
        val bridge = AnytypeBridge(ctx)
        val e = assertThrows(BridgeException.Failed::class.java) { connectWithLooper { bridge.connect() } }
        assertEquals(IApiBridge.INTERNAL, e.code)
        assertEquals(1, ctx.bindCount)
        dead = false
        assertEquals(Endpoint("http://127.0.0.1:1", "k"), connectWithLooper { bridge.connect() })
        assertEquals(2, ctx.bindCount)
        bridge.close()
    }

    @Test(timeout = 30_000)
    fun connectDoesBinderCallOffMainThread() {
        var offMain = false
        install {
            offMain = Looper.myLooper() != Looper.getMainLooper()
            endpoint()
        }
        val bridge = AnytypeBridge(ctx)
        // Start connect() on the main thread itself (the test thread is the main looper's
        // thread), idling the looper until it completes: the binder call must still leave it.
        val handler = Handler(Looper.getMainLooper())
        val main = Executor { handler.post(it) }.asCoroutineDispatcher()
        val result = CoroutineScope(main).async { bridge.connect() }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!result.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "connect() did not complete" }
            Thread.sleep(5)
        }
        assertEquals(Endpoint("http://127.0.0.1:1", "k"), runBlocking { result.await() })
        assertTrue(offMain)
        bridge.close()
    }

    @Test(timeout = 30_000)
    fun unexpectedBinderCallFailureMapsToFailedThenRebinds() {
        var broken = true
        install { if (broken) throw IllegalStateException("boom") else endpoint() }
        val bridge = AnytypeBridge(ctx)
        val e = assertThrows(BridgeException.Failed::class.java) { connectWithLooper { bridge.connect() } }
        assertEquals(IApiBridge.INTERNAL, e.code)
        broken = false
        assertEquals(Endpoint("http://127.0.0.1:1", "k"), connectWithLooper { bridge.connect() })
        assertEquals(2, ctx.bindCount)
        bridge.close()
    }

    @Test(timeout = 30_000)
    fun bindServiceThrowingFailsWithoutWedgingLaterConnects() {
        install { endpoint() }
        ctx.bindThrows = IllegalArgumentException("bad intent")
        val bridge = AnytypeBridge(ctx)
        assertThrows(BridgeException.NotInstalled::class.java) { connectWithLooper { bridge.connect() } }
        ctx.bindThrows = null
        assertEquals(Endpoint("http://127.0.0.1:1", "k"), connectWithLooper { bridge.connect() })
        bridge.close()
    }

    @Test(timeout = 30_000)
    fun serviceDisconnectedForcesRebind() {
        install { endpoint() }
        val bridge = AnytypeBridge(ctx)
        connectWithLooper { bridge.connect() }
        assertEquals(1, ctx.bindCount)
        // A second connect reuses the binding.
        connectWithLooper { bridge.connect() }
        assertEquals(1, ctx.bindCount)
        ctx.lastConnection!!.onServiceDisconnected(ComponentName(pkg, "$pkg.BridgeService"))
        assertEquals(Endpoint("http://127.0.0.1:1", "k"), connectWithLooper { bridge.connect() })
        assertEquals(2, ctx.bindCount)
        bridge.close()
    }

    @Test
    fun connectThrowsNotInstalledWhenBindFails() {
        shadowOf(app).declareActionUnbindable(AnytypeBridge.BIND_ACTION)
        val bridge = AnytypeBridge(ctx)
        val e = assertThrows(BridgeException.NotInstalled::class.java) { connectWithLooper { bridge.connect() } }
        assertEquals(pkg, e.pkg)
    }

    @Test
    fun approvalIntentTargetsPackage() {
        val i = AnytypeBridge(ctx).approvalIntent()
        assertEquals(AnytypeBridge.APPROVE_ACTION, i.action)
        assertEquals(pkg, i.`package`)
    }

    @Test
    fun findInstalledListsBridgeProviders() {
        val info = ServiceInfo().apply {
            packageName = "io.anytype.app.dev"
            name = "io.anytype.app.dev.BridgeService"
            exported = true
        }
        val spm = shadowOf(app.packageManager)
        spm.addOrUpdateService(info)
        spm.addIntentFilterForService(
            ComponentName(info.packageName, info.name),
            IntentFilter(AnytypeBridge.BIND_ACTION)
        )
        assertEquals(listOf("io.anytype.app.dev"), AnytypeBridge.findInstalled(app))
        assertTrue(AnytypeBridge.findInstalled(app).isNotEmpty())
    }

    @Test(timeout = 30_000)
    fun bindFlagsIncludeAutoCreateAndCapabilities() {
        install { ApiEndpoint(IApiBridge.OK, "u", "k") }
        val bridge = AnytypeBridge(ctx)
        connectWithLooper { bridge.connect() }
        assertTrue(ctx.lastFlags and Context.BIND_AUTO_CREATE != 0)
        assertTrue(ctx.lastFlags and Context.BIND_INCLUDE_CAPABILITIES != 0)
        bridge.close()
    }

    @Test(timeout = 30_000)
    fun bindingDiedWhileWaitingFailsConnect() {
        // No service registered: bind succeeds but onServiceConnected never arrives.
        val bridge = AnytypeBridge(ctx)
        var fired = false
        val e = assertThrows(BridgeException.Failed::class.java) {
            connectWithLooper(onTick = {
                val c = ctx.lastConnection
                if (c != null && !fired) {
                    fired = true
                    c.onBindingDied(ComponentName(pkg, "$pkg.BridgeService"))
                }
            }) { bridge.connect() }
        }
        assertEquals(IApiBridge.INTERNAL, e.code)
    }

    @Test(timeout = 30_000)
    fun connectAfterCloseBindsAgain() {
        install { ApiEndpoint(IApiBridge.OK, "u", "k") }
        val bridge = AnytypeBridge(ctx)
        connectWithLooper { bridge.connect() }
        val first = ctx.bindCount
        bridge.close()
        assertEquals(Endpoint("u", "k"), connectWithLooper { bridge.connect() })
        assertEquals(first + 1, ctx.bindCount)
        bridge.close()
    }

    @After
    fun restoreSigningCheck() {
        AnytypeBridge.signingCheck = AnytypeBridge.platformSigningCheck
    }

    private val goodCert = "ab".repeat(32)
    private val goodBytes = AnytypeBridge.parseSha256(goodCert)

    /** Signed with [goodCert] exactly when the package is in [trusted]. */
    private fun signedOnly(vararg trusted: String) {
        AnytypeBridge.signingCheck = SigningCheck { _, p, sha ->
            p in trusted && sha.contentEquals(goodBytes)
        }
    }

    @Test
    fun pinnedCertMatchingBinds() {
        install { endpoint() }
        signedOnly(pkg)
        // Colon-separated upper case, as keytool prints it, is accepted too.
        val pin = goodCert.uppercase().chunked(2).joinToString(":")
        val bridge = AnytypeBridge(ctx, pkg, pin)
        assertEquals(Endpoint("http://127.0.0.1:1", "k"), connectWithLooper { bridge.connect() })
        bridge.close()
    }

    @Test
    fun pinnedCertMismatchNeverBinds() {
        var asked = false
        install { asked = true; endpoint() }
        signedOnly() // the installed package is signed by someone else
        val bridge = AnytypeBridge(ctx, pkg, goodCert)
        val e = assertThrows(BridgeException.Untrusted::class.java) { connectWithLooper { bridge.connect() } }
        assertEquals(pkg, e.pkg)
        assertEquals(0, ctx.bindCount)
        assertTrue(!asked)
        // A later connect checks again and still refuses.
        assertThrows(BridgeException.Untrusted::class.java) { connectWithLooper { bridge.connect() } }
        assertEquals(0, ctx.bindCount)
    }

    @Test
    fun platformCheckRefusesUnknownPackage() {
        install { endpoint() }
        // No package with that cert is installed: the real PackageManager check fails closed.
        val bridge = AnytypeBridge(ctx, "com.not.installed", goodCert)
        assertThrows(BridgeException.Untrusted::class.java) { connectWithLooper { bridge.connect() } }
        assertEquals(0, ctx.bindCount)
    }

    @Test
    fun badPinIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) { AnytypeBridge(ctx, pkg, "abcd") }
        assertThrows(IllegalArgumentException::class.java) { AnytypeBridge(ctx, pkg, "zz".repeat(32)) }
    }

    @Test
    fun findInstalledWithPinFiltersImpostors() {
        val spm = shadowOf(app.packageManager)
        listOf("io.anytype.app", "com.impostor").forEach { p ->
            val info = ServiceInfo().apply {
                packageName = p
                name = "$p.BridgeService"
                exported = true
            }
            spm.addOrUpdateService(info)
            spm.addIntentFilterForService(ComponentName(p, info.name), IntentFilter(AnytypeBridge.BIND_ACTION))
        }
        signedOnly("io.anytype.app")
        assertEquals(setOf("io.anytype.app", "com.impostor"), AnytypeBridge.findInstalled(app).toSet())
        assertEquals(listOf("io.anytype.app"), AnytypeBridge.findInstalled(app, goodCert))
        assertEquals(2, AnytypeBridge.findInstalled(app, null).size)
    }
}

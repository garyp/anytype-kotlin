package io.anytype.agentbridge.client

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.anytype.agentbridge.IAgentBridge
import io.anytype.agentbridge.IAgentBridgeCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
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

    /**
     * Robolectric 4.18 shadows only bindService(Intent, int, Executor, ServiceConnection) and the
     * 3-arg overload; the API 33 BindServiceFlags overload falls through to the real ContextImpl
     * and returns false. This wrapper forwards it to the shadowed overload, so production code
     * can keep using BindServiceFlags.
     */
    private class FlagsForwardingContext(base: Context) : ContextWrapper(base) {
        var lastFlags = 0
        var lastConnection: ServiceConnection? = null
        var bindCount = 0

        override fun bindService(
            service: Intent,
            flags: Context.BindServiceFlags,
            executor: Executor,
            conn: ServiceConnection
        ): Boolean {
            // BindServiceFlags.getValue() is a hidden API, so read it reflectively.
            val value = Context.BindServiceFlags::class.java.getMethod("getValue").invoke(flags) as Long
            lastFlags = value.toInt()
            lastConnection = conn
            bindCount++
            return baseContext.bindService(service, value.toInt(), executor, conn)
        }

        // AnytypeBridge stores context.applicationContext; keep it pointing at this wrapper.
        override fun getApplicationContext(): Context = this
    }

    private val ctx = FlagsForwardingContext(app)

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

    private fun install(block: (IAgentBridgeCallback) -> Unit) {
        val stub = object : IAgentBridge.Stub() {
            override fun connect(cb: IAgentBridgeCallback) = block(cb)
        }
        val intent = Intent(AnytypeBridge.BIND_ACTION).setPackage(pkg)
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            intent, ComponentName(pkg, "$pkg.BridgeService"), stub
        )
    }

    @Test
    fun connectReturnsEndpointOnReady() {
        install { it.onReady("http://127.0.0.1:1", "k") }
        val bridge = AnytypeBridge(ctx)
        assertEquals(Endpoint("http://127.0.0.1:1", "k"), connectWithLooper { bridge.connect() })
        bridge.close()
    }

    @Test
    fun connectThrowsApprovalRequired() {
        install { it.onApprovalRequired() }
        val bridge = AnytypeBridge(ctx)
        assertThrows(BridgeException.ApprovalRequired::class.java) { connectWithLooper { bridge.connect() } }
        bridge.close()
    }

    @Test
    fun connectThrowsFailedWithCode() {
        install { it.onError(3, "x") }
        val bridge = AnytypeBridge(ctx)
        val e = assertThrows(BridgeException.Failed::class.java) { connectWithLooper { bridge.connect() } }
        assertEquals(3, e.code)
        assertEquals("x", e.msg)
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
        install { it.onReady("u", "k") }
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
        assertEquals(IAgentBridgeCallback.INTERNAL, e.code)
    }

    @Test(timeout = 30_000)
    fun serviceDeathAfterRequestSentFailsConnect() {
        var requested = false
        install { requested = true } // never calls back
        val bridge = AnytypeBridge(ctx)
        var fired = false
        val e = assertThrows(BridgeException.Failed::class.java) {
            connectWithLooper(onTick = {
                val c = ctx.lastConnection
                if (requested && c != null && !fired) {
                    fired = true
                    c.onServiceDisconnected(ComponentName(pkg, "$pkg.BridgeService"))
                }
            }) { bridge.connect() }
        }
        assertEquals(IAgentBridgeCallback.INTERNAL, e.code)
        bridge.close()
    }

    @Test(timeout = 30_000)
    fun connectAfterMarkStaleBindsAgain() {
        install { it.onReady("u", "k") }
        val bridge = AnytypeBridge(ctx)
        connectWithLooper { bridge.connect() }
        val first = ctx.bindCount
        bridge.markStale()
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
        install { it.onReady("http://127.0.0.1:1", "k") }
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
        install { asked = true; it.onReady("http://127.0.0.1:1", "k") }
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
        install { it.onReady("http://127.0.0.1:1", "k") }
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

    @Test
    fun resumeOnceIgnoresSecondResume() {
        // A binder callback and a failure sweep can race on one request; the loser must not throw.
        val result = runBlocking {
            suspendCancellableCoroutine<String> { cont ->
                val once = ResumeOnce(cont)
                once.resume("first")
                once.fail(IllegalStateException("late failure"))
                once.resume("late result")
            }
        }
        assertEquals("first", result)
    }
}

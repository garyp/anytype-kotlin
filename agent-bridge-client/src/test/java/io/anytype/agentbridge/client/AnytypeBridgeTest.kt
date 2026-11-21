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
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        override fun bindService(
            service: Intent,
            flags: Context.BindServiceFlags,
            executor: Executor,
            conn: ServiceConnection
        ): Boolean {
            // BindServiceFlags.getValue() is a hidden API, so read it reflectively.
            val value = Context.BindServiceFlags::class.java.getMethod("getValue").invoke(flags) as Long
            return baseContext.bindService(service, value.toInt(), executor, conn)
        }

        // AnytypeBridge stores context.applicationContext; keep it pointing at this wrapper.
        override fun getApplicationContext(): Context = this
    }

    private val ctx: Context = FlagsForwardingContext(app)

    /**
     * Runs [block] off the main thread while this thread keeps idling the (paused) main looper,
     * which is where the shadowed onServiceConnected callback is posted. A plain runBlocking on
     * the test thread would block that looper and never complete.
     */
    private fun <T> connectWithLooper(block: suspend () -> T): T {
        val scope = CoroutineScope(Dispatchers.Default)
        val result = scope.async { block() }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!result.isCompleted) {
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
}

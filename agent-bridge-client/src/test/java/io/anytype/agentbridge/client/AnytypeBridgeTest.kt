package io.anytype.agentbridge.client

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import io.anytype.agentbridge.IAgentBridge
import io.anytype.agentbridge.IAgentBridgeCallback
import kotlinx.coroutines.runBlocking
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
    fun connectReturnsEndpointOnReady() = runBlocking {
        install { it.onReady("http://127.0.0.1:1", "k") }
        val bridge = AnytypeBridge(app)
        assertEquals(Endpoint("http://127.0.0.1:1", "k"), bridge.connect())
        bridge.close()
    }

    @Test
    fun connectThrowsApprovalRequired() {
        install { it.onApprovalRequired() }
        val bridge = AnytypeBridge(app)
        assertThrows(BridgeException.ApprovalRequired::class.java) { runBlocking { bridge.connect() } }
        bridge.close()
    }

    @Test
    fun connectThrowsFailedWithCode() {
        install { it.onError(3, "x") }
        val bridge = AnytypeBridge(app)
        val e = assertThrows(BridgeException.Failed::class.java) { runBlocking { bridge.connect() } }
        assertEquals(3, e.code)
        assertEquals("x", e.msg)
        bridge.close()
    }

    @Test
    fun connectThrowsNotInstalledWhenBindFails() {
        shadowOf(app).declareActionUnbindable(AnytypeBridge.BIND_ACTION)
        val bridge = AnytypeBridge(app)
        val e = assertThrows(BridgeException.NotInstalled::class.java) { runBlocking { bridge.connect() } }
        assertEquals(pkg, e.pkg)
    }

    @Test
    fun approvalIntentTargetsPackage() {
        val i = AnytypeBridge(app).approvalIntent()
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

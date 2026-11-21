package com.anytypeio.anytype.agentbridge.consent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.anytypeio.anytype.agentbridge.Access
import com.anytypeio.anytype.agentbridge.AccountLauncher
import com.anytypeio.anytype.agentbridge.AccountRef
import com.anytypeio.anytype.agentbridge.AccountStartException
import com.anytypeio.anytype.agentbridge.AgentApprovals
import com.anytypeio.anytype.agentbridge.AgentBridge
import com.anytypeio.anytype.agentbridge.GatewayApp
import com.anytypeio.anytype.agentbridge.Grant
import com.anytypeio.anytype.agentbridge.LocalApiGateway
import com.anytypeio.anytype.agentbridge.SpaceDirectory
import com.anytypeio.anytype.agentbridge.SpaceRef
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentApprovalActivityTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var approvals: AgentApprovals
    private val started = AtomicInteger()

    private object NoGateway : LocalApiGateway {
        override fun setListenAddr(addr: String): String? = null
        override fun listApps(): List<GatewayApp> = emptyList()
        override fun createApp(name: String, readWrite: Boolean, spaceIds: List<String>?) =
            throw UnsupportedOperationException()
        override fun revokeApp(appHash: String) = Unit
    }

    private inner class FakeLauncher(private val fail: Exception? = null) : AccountLauncher {
        override fun runningAccount(): AccountRef? = null
        override suspend fun ensureStarted(): AccountRef {
            started.incrementAndGet()
            fail?.let { throw it }
            return AccountRef("A", "Alice")
        }
    }

    private object FakeSpaces : SpaceDirectory {
        override suspend fun activeSpaces() = listOf(SpaceRef("s1", "Work"), SpaceRef("s2", "Home"))
    }

    @Before
    fun setUp() {
        AgentBridge.resetForTest()
        AgentApprovalActivity.resetForTest()
        // A normal permission: granted at install on a device, not by Robolectric.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS)
        context.getSharedPreferences("agent_bridge", Context.MODE_PRIVATE).edit().clear().commit()
        approvals = AgentApprovals(context)
        started.set(0)
        AgentApprovalActivity.callerIdentity = CallerIdentity { Caller(10_123, PKG) }
        AgentApprovalActivity.certLookup = CertLookup { _, pkg -> if (pkg == PKG) CERT else null }
    }

    @After
    fun tearDown() {
        AgentBridge.resetForTest()
        AgentApprovalActivity.resetForTest()
    }

    private fun install(launcher: AccountLauncher = FakeLauncher()) =
        AgentBridge.install(NoGateway, launcher, FakeSpaces)

    private fun launch(): ActivityScenario<AgentApprovalActivity> =
        ActivityScenario.launchActivityForResult(
            Intent(AgentApprovalActivity.ACTION_APPROVE).setClass(context, AgentApprovalActivity::class.java)
        )

    /**
     * Robolectric marks an activity that calls finish() after onCreate as finishing but does not
     * drive it to DESTROYED, so a finishing activity counts as finished and its result is read
     * from the shadow.
     */
    private fun awaitFinished(scenario: ActivityScenario<AgentApprovalActivity>): Int {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (scenario.state == Lifecycle.State.DESTROYED) return scenario.result.resultCode
            var code: Int? = null
            scenario.onActivity { if (it.isFinishing) code = shadowOf(it).resultCode }
            code?.let { return it }
            check(System.currentTimeMillis() < deadline) { "activity did not finish" }
            Thread.sleep(10)
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTextExists(text)
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextExists(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun noIdentityCancels() {
        install()
        AgentApprovalActivity.callerIdentity = CallerIdentity { null }
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(launch()))
        assertNotNull(ShadowToast.getTextOfLatestToast())
        assertEquals(0, started.get())
    }

    @Test
    fun unknownCertCancels() {
        install()
        AgentApprovalActivity.certLookup = CertLookup { _, _ -> null }
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(launch()))
        assertNotNull(ShadowToast.getTextOfLatestToast())
        assertEquals(0, started.get())
    }

    @Test
    fun ownPackageCancels() {
        install()
        AgentApprovalActivity.callerIdentity = CallerIdentity { Caller(10_000, context.packageName) }
        AgentApprovalActivity.certLookup = CertLookup { _, _ -> CERT }
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(launch()))
        assertEquals(0, started.get())
        assertTrue(approvals.all().isEmpty())
    }

    @Test
    fun deniedCallerInCooldownCancelsImmediately() {
        install()
        approvals.deny(PKG)
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(launch()))
        assertEquals(0, started.get())
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun notLoggedInCancels() {
        install(FakeLauncher(AccountStartException(AccountStartException.NOT_LOGGED_IN, "secret detail")))
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(launch()))
        assertEquals("Sign in to Anytype first", ShadowToast.getTextOfLatestToast())
        assertTrue(approvals.all().isEmpty())
    }

    @Test
    fun notInstalledCancels() {
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(launch()))
        assertEquals("Sign in to Anytype first", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun windowIsSecure() {
        install()
        val scenario = launch()
        awaitText("Allow")
        scenario.onActivity {
            val flags = it.window.attributes.flags
            assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    @Test
    fun allowStoresApprovalAndReturnsOk() {
        install()
        val scenario = launch()
        awaitText("Allow $PKG to access Anytype?")
        compose.onNodeWithText("Allow").performScrollTo().assertIsEnabled().performClick()
        assertEquals(Activity.RESULT_OK, awaitFinished(scenario))
        val a = approvals.find("A", PKG)
        assertNotNull(a)
        assertEquals(CERT, a.certSha256)
        assertEquals(Access.READ_WRITE, a.access)
        assertEquals(Grant.ALL, a.grant)
        assertTrue(a.approvedAt > 0)
    }

    @Test
    fun allowSpecificSpacesReadOnly() {
        install()
        val scenario = launch()
        awaitText("All spaces")
        compose.onNodeWithText("All spaces").performScrollTo().performClick()
        compose.onNodeWithText("Allow").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Home").performScrollTo().performClick()
        compose.onNodeWithText("Read only").performScrollTo().performClick()
        compose.onNodeWithText("Allow").performScrollTo().assertIsEnabled().performClick()
        assertEquals(Activity.RESULT_OK, awaitFinished(scenario))
        val a = assertNotNull(approvals.find("A", PKG))
        assertEquals(Access.READ_ONLY, a.access)
        assertEquals(Grant(false, listOf("s2")), a.grant)
    }

    @Test
    fun dontAllowSetsCooldown() {
        install()
        val scenario = launch()
        awaitText("Don't allow")
        compose.onNodeWithText("Don't allow").performScrollTo().performClick()
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(scenario))
        assertTrue(approvals.isInDenialCooldown(PKG))
        assertTrue(approvals.all().isEmpty())
    }

    private companion object {
        const val PKG = "com.agent"
        const val CERT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}

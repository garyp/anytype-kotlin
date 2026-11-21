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
        AgentApprovalActivity.armDelayMs = 0
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
     * Launches and waits for the dialog. Robolectric never gives a dialog window focus, so the
     * test does what the system does when the dialog comes to the front.
     */
    private fun launchShown(): ActivityScenario<AgentApprovalActivity> {
        val scenario = launch()
        val deadline = System.currentTimeMillis() + 5_000
        var root: android.view.View? = null
        while (root == null) {
            check(System.currentTimeMillis() < deadline) { "no dialog" }
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity { root = it.dialogRoot }
            if (root == null) Thread.sleep(10)
        }
        root!!.rootView.dispatchWindowFocusChanged(true)
        return scenario
    }

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
        val scenario = launchShown()
        awaitText("Allow")
        scenario.onActivity {
            val flags = it.window.attributes.flags
            assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    @Test
    fun allowStoresApprovalAndReturnsOk() {
        install()
        val scenario = launchShown()
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
        val scenario = launchShown()
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
        val scenario = launchShown()
        awaitText("Don't allow")
        compose.onNodeWithText("Don't allow").performScrollTo().performClick()
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(scenario))
        assertTrue(approvals.isInDenialCooldown(PKG))
        assertTrue(approvals.all().isEmpty())
    }

    /** Waits until Allow is armed and enabled, i.e. clickable by a real tap. */
    private fun awaitAllowEnabled() {
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Allow") and androidx.compose.ui.test.isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun dialogRoot(scenario: ActivityScenario<AgentApprovalActivity>): android.view.ViewGroup {
        var root: android.view.View? = null
        scenario.onActivity { root = it.dialogRoot }
        return assertNotNull(root) as android.view.ViewGroup
    }

    /** A tap at the centre of Allow, delivered to [target] with the given MotionEvent flags. */
    private fun tapAllow(target: android.view.View, flags: Int, upFlags: Int = flags) {
        val c = compose.onNodeWithText("Allow").fetchSemanticsNode().boundsInRoot.center
        fun ev(action: Int, t: Long, flags: Int): android.view.MotionEvent {
            val props = android.view.MotionEvent.PointerProperties().apply {
                id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = android.view.MotionEvent.PointerCoords().apply { x = c.x; y = c.y; pressure = 1f; size = 1f }
            return android.view.MotionEvent.obtain(
                100L, t, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, flags
            )
        }
        target.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, 100L, flags))
        target.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, 150L, upFlags))
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun assertNothingDecided(scenario: ActivityScenario<AgentApprovalActivity>) {
        // Give a (wrongly) accepted click the time to reach disk and finish.
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue(approvals.all().isEmpty())
        scenario.onActivity { assertTrue(!it.isFinishing) }
    }

    @Test
    fun obscuredTapOnDialogIsDropped() {
        install()
        val scenario = launchShown()
        awaitAllowEnabled()
        val root = dialogRoot(scenario)
        tapAllow(root, android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertNothingDecided(scenario)
        tapAllow(root, android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        assertNothingDecided(scenario)
        // Covered only once the finger is down: the dialog root drops the UP, so no click.
        tapAllow(root, 0, upFlags = android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertNothingDecided(scenario)
        // The same tap, unobscured, still works.
        tapAllow(root, 0)
        assertEquals(Activity.RESULT_OK, awaitFinished(scenario))
        assertNotNull(approvals.find("A", PKG))
    }

    @Test
    fun obscuredTapOnAllowItselfIsIgnored() {
        install()
        val scenario = launchShown()
        awaitAllowEnabled()
        // Straight to the compose view, past the dialog root's filter: Allow's own guard must refuse.
        val composeView = dialogRoot(scenario).getChildAt(0)
        tapAllow(composeView, android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertNothingDecided(scenario)
        tapAllow(composeView, 0)
        assertEquals(Activity.RESULT_OK, awaitFinished(scenario))
    }

    @Test
    fun dialogRootFiltersObscuredTouches() {
        install()
        val scenario = launchShown()
        awaitAllowEnabled()
        val root = dialogRoot(scenario)
        assertTrue(root.filterTouchesWhenObscured)
        assertTrue(root.isAccessibilityDataSensitive)
    }

    @Test
    fun allowArmsOnlyAfterDelay() {
        AgentApprovalActivity.armDelayMs = 750
        install()
        val scenario = launchShown()
        awaitText("Allow $PKG to access Anytype?")
        compose.onNodeWithText("Allow").performScrollTo().assertIsNotEnabled()
        // A real tap now does nothing.
        tapAllow(dialogRoot(scenario), 0)
        assertNothingDecided(scenario)
        compose.mainClock.advanceTimeBy(1_000)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        awaitAllowEnabled()
        compose.onNodeWithText("Allow").performClick()
        assertEquals(Activity.RESULT_OK, awaitFinished(scenario))
    }

    @Test
    fun appIconIsShown() {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = PKG
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = PKG }
        })
        pm.setApplicationIcon(PKG, android.graphics.drawable.ColorDrawable(android.graphics.Color.RED))
        install()
        launchShown()
        awaitText("All spaces")
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag(AgentApprovalActivity.ICON_TAG))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun noIconWhenUnavailable() {
        install()
        launchShown()
        awaitAllowEnabled()
        assertTrue(
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag(AgentApprovalActivity.ICON_TAG))
                .fetchSemanticsNodes().isEmpty()
        )
    }

    private companion object {
        const val PKG = "com.agent"
        const val CERT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}

package com.anytypeio.anytype.apibridge.consent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.anytypeio.anytype.apibridge.Access
import com.anytypeio.anytype.apibridge.AccountLauncher
import com.anytypeio.anytype.apibridge.AccountRef
import com.anytypeio.anytype.apibridge.AccountStartException
import com.anytypeio.anytype.apibridge.AppApprovals
import com.anytypeio.anytype.apibridge.ApiBridge
import com.anytypeio.anytype.apibridge.GatewayApp
import com.anytypeio.anytype.apibridge.Grant
import com.anytypeio.anytype.apibridge.LocalApiGateway
import com.anytypeio.anytype.apibridge.SpaceDirectory
import com.anytypeio.anytype.apibridge.SpaceRef
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import com.anytypeio.anytype.localization.R as Loc
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectApprovalActivityTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var approvals: AppApprovals
    private val started = AtomicInteger()

    private object NoGateway : LocalApiGateway {
        override fun setListenAddr(addr: String): String? = null
        override fun listApps(): List<GatewayApp> = emptyList()
        override fun createApp(name: String, readWrite: Boolean, spaceIds: List<String>?) =
            throw UnsupportedOperationException()
        override fun revokeApp(appHash: String) = Unit
    }

    private inner class FakeLauncher(
        private val fail: Exception? = null,
        private val accountName: String = "Alice"
    ) : AccountLauncher {
        override fun runningAccount(): AccountRef? = null
        override suspend fun ensureStarted(): AccountRef {
            started.incrementAndGet()
            fail?.let { throw it }
            return AccountRef("A", accountName)
        }
        override fun generation() = 1L
    }

    private object FakeSpaces : SpaceDirectory {
        override suspend fun activeSpaces() = listOf(SpaceRef("s1", "Work"), SpaceRef("s2", "Home"))
    }

    @Before
    fun setUp() {
        ApiBridge.resetForTest()
        ConnectApprovalActivity.resetForTest()
        // A normal permission: granted at install on a device, not by Robolectric.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS)
        context.getSharedPreferences("api_bridge", Context.MODE_PRIVATE).edit().clear().commit()
        approvals = AppApprovals(context)
        started.set(0)
        ConnectApprovalActivity.callerIdentity = CallerIdentity { Caller(10_123, PKG) }
        ConnectApprovalActivity.certLookup = CertLookup { _, pkg -> if (pkg == PKG) CERT else null }
        ConnectApprovalActivity.armDelayMs = 0
    }

    @After
    fun tearDown() {
        ApiBridge.resetForTest()
        ConnectApprovalActivity.resetForTest()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED)
    }

    private fun install(launcher: AccountLauncher = FakeLauncher()) =
        ApiBridge.install(NoGateway, launcher, FakeSpaces)

    private fun launch(): ActivityScenario<ConnectApprovalActivity> =
        ActivityScenario.launchActivityForResult(
            Intent(ConnectApprovalActivity.ACTION_APPROVE).setClass(context, ConnectApprovalActivity::class.java)
        )

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val allow get() = str(Loc.string.api_bridge_consent_allow)
    private val allSpaces get() = str(Loc.string.api_bridge_all_spaces)
    private val readOnly get() = str(Loc.string.api_bridge_access_read_only)
    private val title get() = str(Loc.string.api_bridge_consent_title, PKG)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The sheet's window: ModalBottomSheet shows a dialog of its own. */
    private fun sheet(): ComponentDialog =
        assertNotNull(ShadowDialog.getLatestDialog() as? ComponentDialog, "no sheet")

    /** The sheet window's root view, which every touch on the sheet passes first. */
    private fun sheetRoot(): ViewGroup = sheet().window!!.decorView as ViewGroup

    /** The view that holds the sheet's composables, under the sheet window's root. */
    private fun sheetComposeView(): View {
        fun find(v: View): View? = when {
            v is ViewRootForTest -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return assertNotNull(find(sheetRoot()), "no compose view in the sheet")
    }

    /** Launches and waits for the sheet; see [awaitSheet]. */
    private fun launchShown(): ActivityScenario<ConnectApprovalActivity> = launch().also { awaitSheet() }

    /**
     * Waits for the sheet window. Robolectric never gives it focus, so [focus] does what the
     * system does when the sheet comes to the front.
     */
    private fun awaitSheet(focus: Boolean = true) {
        val deadline = System.currentTimeMillis() + 5_000
        while (ShadowDialog.getLatestDialog()?.isShowing != true) {
            check(System.currentTimeMillis() < deadline) { "no sheet" }
            idle()
            Thread.sleep(10)
        }
        if (focus) setSheetFocus(true)
    }

    private fun setSheetFocus(focused: Boolean) {
        sheetRoot().dispatchWindowFocusChanged(focused)
        idle()
        compose.waitForIdle()
    }

    /** Lets [armDelayMs][ConnectApprovalActivity.armDelayMs] pass. */
    private fun passArmDelay() {
        compose.mainClock.advanceTimeBy(1_000)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
    }

    /**
     * Robolectric marks an activity that calls finish() after onCreate as finishing but does not
     * drive it to DESTROYED, so a finishing activity counts as finished and its result is read
     * from the shadow.
     */
    private fun awaitFinished(scenario: ActivityScenario<ConnectApprovalActivity>): Int {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idle()
            if (scenario.state == Lifecycle.State.DESTROYED) return scenario.result.resultCode
            var code: Int? = null
            scenario.onActivity { if (it.isFinishing) code = shadowOf(it).resultCode }
            code?.let { return it }
            check(System.currentTimeMillis() < deadline) { "activity did not finish" }
            Thread.sleep(10)
        }
    }

    private fun exists(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun awaitText(text: String) = compose.waitUntil(5_000) { exists(text) }

    /** Waits until Allow is armed and enabled, i.e. clickable by a real tap. */
    private fun awaitAllowEnabled() = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(allow) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    private fun clickAllow() = compose.onNodeWithText(allow).performScrollTo().assertIsEnabled().performClick()

    /** All spaces off, Home ticked, read only. */
    private fun chooseHomeReadOnly() {
        compose.onNodeWithText(allSpaces).performScrollTo().performClick()
        compose.onNodeWithText("Home").performScrollTo().performClick()
        compose.onNodeWithText(readOnly).performScrollTo().performClick()
    }

    private fun assertHomeReadOnlyApproved() {
        val a = assertNotNull(approvals.find("A", PKG))
        assertEquals(Access.READ_ONLY, a.access)
        assertEquals(Grant(false, listOf("s2")), a.grant)
    }

    /** A tap at the centre of Allow, delivered to [target] with the given MotionEvent flags. */
    private fun tapAllow(target: View, flags: Int, upFlags: Int = flags) {
        val c = compose.onNodeWithText(allow).fetchSemanticsNode().boundsInRoot.center
        fun ev(action: Int, t: Long, flags: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { x = c.x; y = c.y; pressure = 1f; size = 1f }
            return MotionEvent.obtain(
                100L, t, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, flags
            )
        }
        target.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 100L, flags))
        target.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 150L, upFlags))
        idle()
        compose.waitForIdle()
    }

    private fun assertNothingDecided(scenario: ActivityScenario<ConnectApprovalActivity>) {
        // Give a (wrongly) accepted click the time to reach disk and finish.
        repeat(20) { idle(); Thread.sleep(10) }
        assertTrue(approvals.all().isEmpty())
        scenario.onActivity { assertTrue(!it.isFinishing) }
    }

    private fun installApp(label: String) {
        shadowOf(context.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = PKG
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = PKG
                labelRes = 0
                nonLocalizedLabel = label
            }
        })
    }

    // Caller checks.

    @Test
    fun noIdentityCancels() {
        install()
        ConnectApprovalActivity.callerIdentity = CallerIdentity { null }
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(launch()))
        assertEquals(str(Loc.string.api_bridge_unverified_caller), ShadowToast.getTextOfLatestToast())
        assertEquals(0, started.get())
    }

    @Test
    fun unknownCertCancels() {
        install()
        ConnectApprovalActivity.certLookup = CertLookup { _, _ -> null }
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(launch()))
        assertEquals(str(Loc.string.api_bridge_unverified_caller), ShadowToast.getTextOfLatestToast())
        assertEquals(0, started.get())
    }

    @Test
    fun ownPackageCancels() {
        install()
        ConnectApprovalActivity.callerIdentity = CallerIdentity { Caller(10_000, context.packageName) }
        ConnectApprovalActivity.certLookup = CertLookup { _, _ -> CERT }
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
        assertEquals(str(Loc.string.api_bridge_sign_in_first), ShadowToast.getTextOfLatestToast())
        assertTrue(approvals.all().isEmpty())
    }

    @Test
    fun notInstalledCancels() {
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(launch()))
        assertEquals(str(Loc.string.api_bridge_sign_in_first), ShadowToast.getTextOfLatestToast())
    }

    // Answers.

    @Test
    fun allowStoresApprovalAndReturnsOk() {
        install()
        val scenario = launchShown()
        awaitText(title)
        awaitAllowEnabled()
        clickAllow()
        assertEquals(Activity.RESULT_OK, awaitFinished(scenario))
        val a = assertNotNull(approvals.find("A", PKG))
        assertEquals(CERT, a.certSha256)
        assertEquals(Access.READ_WRITE, a.access)
        assertEquals(Grant.ALL, a.grant)
        assertTrue(a.approvedAt > 0)
    }

    @Test
    fun allowSpecificSpacesReadOnly() {
        install()
        val scenario = launchShown()
        awaitText(allSpaces)
        compose.onNodeWithText(allSpaces).performScrollTo().performClick()
        compose.onNodeWithText(allow).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Home").performScrollTo().performClick()
        compose.onNodeWithText(readOnly).performScrollTo().performClick()
        clickAllow()
        assertEquals(Activity.RESULT_OK, awaitFinished(scenario))
        assertHomeReadOnlyApproved()
    }

    @Test
    fun dontAllowSetsCooldown() {
        install()
        val scenario = launchShown()
        val deny = str(Loc.string.api_bridge_consent_deny)
        awaitText(deny)
        compose.onNodeWithText(deny).performScrollTo().performClick()
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(scenario))
        assertTrue(approvals.isInDenialCooldown(PKG))
        assertTrue(approvals.all().isEmpty())
    }

    /** Back on the sheet is Don't allow, cooldown included. */
    @Test
    fun backMeansDontAllow() {
        install()
        val scenario = launchShown()
        awaitAllowEnabled()
        sheet().onBackPressedDispatcher.onBackPressed()
        // The sheet animates out before it reports the dismissal.
        compose.waitForIdle()
        assertEquals(Activity.RESULT_CANCELED, awaitFinished(scenario))
        assertTrue(approvals.isInDenialCooldown(PKG))
        assertTrue(approvals.all().isEmpty())
    }

    // Recreation.

    /** A rotation recreates the activity; it must not reset the choices to the broadest access. */
    @Test
    fun choicesSurviveRecreation() {
        install()
        val scenario = launchShown()
        awaitText(allSpaces)
        chooseHomeReadOnly()

        scenario.recreate()
        awaitSheet()
        awaitText("Home")
        awaitAllowEnabled()
        clickAllow()
        assertEquals(Activity.RESULT_OK, awaitFinished(scenario))
        assertHomeReadOnlyApproved()
    }

    /** While Allow saves, a recreation must still keep the narrowed choices. */
    @Test
    fun choicesAreSavedWhileSaving() {
        install()
        val scenario = launchShown()
        awaitText(allSpaces)
        chooseHomeReadOnly()
        awaitAllowEnabled()
        clickAllow()
        val saved = Bundle()
        scenario.onActivity {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .callActivityOnSaveInstanceState(it, saved)
        }
        assertEquals(ConsentChoices(false, listOf("s2"), Access.READ_ONLY), ConsentChoices.readFrom(saved))
    }

    // Protections.

    @Test
    fun activityWindowIsSecureWithOverlaysHidden() {
        install()
        val scenario = launchShown()
        awaitText(allow)
        scenario.onActivity {
            assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            // Hidden APIs: the flag that setHideOverlayWindows(true) sets.
            val params = WindowManager.LayoutParams::class.java
            val hide = params.getField("SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS").getInt(null)
            assertTrue(params.getField("privateFlags").getInt(it.window.attributes) and hide != 0)
        }
    }

    @Test
    fun sheetWindowIsSecure() {
        install()
        launchShown()
        awaitAllowEnabled()
        assertTrue(sheet().window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    /** The filter sits on the sheet window's root; sensitivity reaches the composables below it. */
    @Test
    fun sheetRootFiltersObscuredTouchesAndIsSensitive() {
        install()
        launchShown()
        awaitAllowEnabled()
        val root = sheetRoot()
        assertTrue(root.filterTouchesWhenObscured)
        assertTrue(root.isAccessibilityDataSensitive)
        assertTrue(sheetComposeView().isAccessibilityDataSensitive)
    }

    @Test
    fun obscuredTapOnSheetIsDropped() {
        install()
        val scenario = launchShown()
        awaitAllowEnabled()
        val root = sheetRoot()
        tapAllow(root, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertNothingDecided(scenario)
        tapAllow(root, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        assertNothingDecided(scenario)
        // Covered only once the finger is down: the sheet root drops the UP, so no click.
        tapAllow(root, 0, upFlags = MotionEvent.FLAG_WINDOW_IS_OBSCURED)
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
        // Straight to the compose view, past the sheet root's filter: Allow's own check must refuse.
        val composeView = sheetComposeView()
        tapAllow(composeView, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertNothingDecided(scenario)
        tapAllow(composeView, 0)
        assertEquals(Activity.RESULT_OK, awaitFinished(scenario))
    }

    @Test
    fun allowArmsOnlyAfterDelay() {
        ConnectApprovalActivity.armDelayMs = 750
        install()
        val scenario = launchShown()
        awaitText(title)
        compose.onNodeWithText(allow).performScrollTo().assertIsNotEnabled()
        // A real tap now does nothing.
        tapAllow(sheetRoot(), 0)
        assertNothingDecided(scenario)
        passArmDelay()
        awaitAllowEnabled()
        compose.onNodeWithText(allow).performClick()
        assertEquals(Activity.RESULT_OK, awaitFinished(scenario))
    }

    @Test
    fun allowStaysDisarmedWithoutFocus() {
        ConnectApprovalActivity.armDelayMs = 750
        install()
        val scenario = launch()
        awaitSheet(focus = false)
        awaitText(title)
        passArmDelay()
        compose.waitForIdle()
        compose.onNodeWithText(allow).performScrollTo().assertIsNotEnabled()
        tapAllow(sheetRoot(), 0)
        assertNothingDecided(scenario)
    }

    /** Focus lost (another window on top) disarms Allow; focus regained arms it after the delay again. */
    @Test
    fun allowRearmsAfterFocusReturns() {
        ConnectApprovalActivity.armDelayMs = 750
        install()
        val scenario = launchShown()
        awaitText(title)
        passArmDelay()
        awaitAllowEnabled()
        setSheetFocus(false)
        compose.onNodeWithText(allow).assertIsNotEnabled()
        setSheetFocus(true)
        // Not before the delay has passed again.
        compose.onNodeWithText(allow).assertIsNotEnabled()
        tapAllow(sheetRoot(), 0)
        assertNothingDecided(scenario)
        passArmDelay()
        awaitAllowEnabled()
        compose.onNodeWithText(allow).performClick()
        assertEquals(Activity.RESULT_OK, awaitFinished(scenario))
    }

    /**
     * The sheet stays open until the user answers: leaving it for Connected apps (or anywhere
     * else) must not cancel the request. A noHistory activity is finished as soon as the user
     * navigates away, so the consent screen must not be declared that way.
     */
    @Test
    fun consentScreenIsNotNoHistory() {
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, ConnectApprovalActivity::class.java), 0
        )
        assertEquals(0, info.flags and android.content.pm.ActivityInfo.FLAG_NO_HISTORY)
    }

    // Content.

    @Test
    fun bodyFollowsTheChoices() {
        install()
        launchShown()
        awaitText(str(Loc.string.api_bridge_consent_body_all_read_write, PKG))
        awaitText(str(Loc.string.api_bridge_consent_account, "Alice"))
        compose.onNodeWithText(allSpaces).performScrollTo().performClick()
        awaitText(str(Loc.string.api_bridge_consent_body_choose_spaces, PKG))
        awaitText(str(Loc.string.api_bridge_consent_note_spaces))
        compose.onNodeWithText("Home").performScrollTo().performClick()
        compose.onNodeWithText(readOnly).performScrollTo().performClick()
        val res = context.resources
        awaitText(res.getQuantityString(Loc.plurals.api_bridge_consent_body_spaces_read_only, 1, PKG, 1))
    }

    @Test
    fun blankAccountNameHasNoCaption() {
        install(FakeLauncher(accountName = " "))
        launchShown()
        awaitAllowEnabled()
        val prefix = str(Loc.string.api_bridge_consent_account, "")
        assertTrue(!exists(prefix, substring = true))
    }

    @Test
    fun appIconIsShown() {
        val pm = shadowOf(context.packageManager)
        installApp(PKG)
        pm.setApplicationIcon(PKG, android.graphics.drawable.ColorDrawable(android.graphics.Color.RED))
        install()
        launchShown()
        awaitText(allSpaces)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(ConnectApprovalActivity.ICON_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun noIconWhenUnavailable() {
        install()
        launchShown()
        awaitAllowEnabled()
        assertTrue(compose.onAllNodes(hasTestTag(ConnectApprovalActivity.ICON_TAG)).fetchSemanticsNodes().isEmpty())
    }

    /**
     * The label is read as HTML, as the system reads app labels: a raw newline is only
     * whitespace, and the label ends at its first line break.
     */
    @Test
    fun labelShowsOnlyItsFirstLine() {
        installApp("  Evil\nApp<br>Injected line")
        install()
        launchShown()
        awaitText(str(Loc.string.api_bridge_consent_title, "Evil App"))
        assertTrue(!exists("Injected", substring = true))
    }

    @Test
    fun longLabelIsBounded() {
        installApp("L".repeat(5_000))
        install()
        launchShown()
        awaitAllowEnabled()
        val texts = compose.onAllNodes(hasText("LLLL", substring = true)).fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }
            .map { it.text }
        assertTrue(texts.isNotEmpty())
        assertTrue(texts.all { t -> t.count { it == 'L' } <= MAX_LABEL_CHARS }, "label not bounded: ${texts.map { it.length }}")
    }

    @Test
    fun manageLinkOpensConnectedApps() {
        install()
        val scenario = launchShown()
        awaitText(allSpaces)
        compose.onNodeWithText(str(Loc.string.api_bridge_manage_connected_apps)).performScrollTo().performClick()
        var next: Intent? = null
        scenario.onActivity { next = shadowOf(it).nextStartedActivity }
        assertEquals(
            com.anytypeio.anytype.apibridge.access.ConnectedAppsActivity::class.java.name,
            next?.component?.className
        )
    }

    /** Anytype's in-app Light/Dark setting goes through AppCompatDelegate. */
    @Test
    fun followsTheAppNightMode() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        install()
        launch().onActivity {
            val night = it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES, night)
        }
    }

    private companion object {
        const val PKG = "com.client"
        const val CERT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val MAX_LABEL_CHARS = com.anytypeio.anytype.apibridge.MAX_LABEL_CHARS
    }
}

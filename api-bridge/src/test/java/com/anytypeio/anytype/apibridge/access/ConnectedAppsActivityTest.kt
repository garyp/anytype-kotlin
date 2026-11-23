package com.anytypeio.anytype.apibridge.access

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.anytypeio.anytype.apibridge.Access
import com.anytypeio.anytype.apibridge.AccountLauncher
import com.anytypeio.anytype.apibridge.AccountRef
import com.anytypeio.anytype.apibridge.AccountStartException
import com.anytypeio.anytype.apibridge.ApiBridge
import com.anytypeio.anytype.apibridge.ApprovalsRule
import com.anytypeio.anytype.apibridge.BlockingApprovals
import com.anytypeio.anytype.apibridge.Approval
import com.anytypeio.anytype.apibridge.GatewayApp
import com.anytypeio.anytype.apibridge.Grant
import com.anytypeio.anytype.apibridge.KeyNames
import com.anytypeio.anytype.apibridge.LocalApiGateway
import com.anytypeio.anytype.apibridge.MAX_LABEL_CHARS
import com.anytypeio.anytype.apibridge.SpaceDirectory
import com.anytypeio.anytype.apibridge.SpaceRef
import com.anytypeio.anytype.localization.R as Loc
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectedAppsActivityTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val files = ApprovalsRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var approvals: BlockingApprovals

    private class FakeGateway(var failRevoke: Boolean = false, var failList: Boolean = false) : LocalApiGateway {
        val apps = CopyOnWriteArrayList<GatewayApp>()
        val revoked = CopyOnWriteArrayList<String>()
        override fun setListenAddr(addr: String): String? = null
        /** When set, listApps blocks until it opens. */
        @Volatile
        var listGate: java.util.concurrent.CountDownLatch? = null
        override fun listApps(): List<GatewayApp> {
            listGate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
            if (failList) throw IllegalStateException("boom")
            return apps.toList()
        }
        override fun createApp(name: String, readWrite: Boolean, spaceIds: List<String>?) =
            throw UnsupportedOperationException()
        override fun revokeApp(appHash: String) {
            if (failRevoke) throw IllegalStateException("boom")
            revoked.add(appHash)
            apps.removeAll { it.appHash == appHash }
        }
    }

    private class FakeLauncher(@Volatile var running: AccountRef? = AccountRef("A", "Alice")) : AccountLauncher {
        @Volatile
        var started = 0
        var fail: Exception? = null
        override fun runningAccount(): AccountRef? = running
        override suspend fun ensureStarted(): AccountRef {
            started++
            fail?.let { throw it }
            return AccountRef("A", "Alice").also { running = it }
        }
        override val accountStops: Flow<Unit> = emptyFlow()
    }

    private object NoSpaces : SpaceDirectory {
        override suspend fun activeSpaces() = emptyList<SpaceRef>()
    }

    private val gateway = FakeGateway()

    @Before
    fun setUp() {
        ApiBridge.resetForTest()
        ApiBridge.approvalsOverride = files.approvals
        approvals = BlockingApprovals(files.approvals)
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS)
    }

    @After
    fun tearDown() {
        ApiBridge.resetForTest()
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_UNSPECIFIED)
    }

    private fun approval(pkg: String, account: String = "A", grant: Grant = Grant.ALL) =
        Approval(account, pkg, CERT, Access.READ_WRITE, grant, APPROVED_AT)

    private fun app(pkg: String, hash: String) = GatewayApp(hash, KeyNames.name(pkg, CERT), "k", 1)

    private fun launch(): ActivityScenario<ConnectedAppsActivity> =
        ActivityScenario.launch(Intent(context, ConnectedAppsActivity::class.java))

    private fun awaitText(text: String) = compose.waitUntil(5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitGone(text: String) = compose.waitUntil(5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty()
    }

    private fun awaitCond(cond: () -> Boolean) = compose.waitUntil(5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        cond()
    }

    @Test
    fun listsApprovalsOfRunningAccount() {
        approvals.approve(approval("com.one"))
        approvals.approve(approval("com.two", grant = Grant(false, listOf("s1", "s2"))))
        approvals.approve(approval("com.other", account = "B"))
        ApiBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch()
        awaitText("com.one")
        awaitText("com.two")
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(APPROVED_AT))
        val readWrite = str(Loc.string.api_bridge_access_read_write)
        awaitText(str(Loc.string.api_bridge_app_summary, readWrite, str(Loc.string.api_bridge_all_spaces), date))
        val twoSpaces = context.resources.getQuantityString(Loc.plurals.api_bridge_space_count, 2, 2)
        awaitText(str(Loc.string.api_bridge_app_summary, readWrite, twoSpaces, date))
        awaitText("0123 4567 89ab cdef")
        assertTrue(compose.onAllNodes(hasText("com.other", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun startsAccountWhenNotRunning() {
        approvals.approve(approval("com.one"))
        val launcher = FakeLauncher(running = null)
        ApiBridge.install(gateway, launcher, NoSpaces)
        launch()
        awaitText("com.one")
        assertEquals(1, launcher.started)
    }

    @Test
    fun emptyState() {
        ApiBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch()
        awaitText(str(Loc.string.api_bridge_empty_title))
        awaitText(str(Loc.string.api_bridge_empty_description))
    }

    @Test
    fun notSignedInShowsGenericError() {
        val launcher = FakeLauncher(running = null).apply {
            fail = AccountStartException(AccountStartException.NOT_LOGGED_IN, "secret detail")
        }
        ApiBridge.install(gateway, launcher, NoSpaces)
        launch()
        awaitText(str(Loc.string.api_bridge_sign_in_first))
        assertTrue(compose.onAllNodes(hasText("secret detail", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun bridgeNotInstalledDoesNotCrash() {
        launch()
        awaitText(str(Loc.string.api_bridge_not_ready))
    }

    @Test
    fun revokeRemovesApprovalAndKeys() {
        approvals.approve(approval("com.one"))
        approvals.approve(approval("com.two"))
        // com.two's key was minted after its approval: nothing of it is pending revocation.
        approvals.clearPendingRevoke("A", "com.two")
        gateway.apps.add(app("com.one", "h1"))
        gateway.apps.add(app("com.one", "h1b").copy(appName = KeyNames.prefix("com.one") + "old"))
        gateway.apps.add(app("com.two", "h2"))
        ApiBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch()
        awaitText("com.one")
        compose.onNodeWithTag(ConnectedAppsActivity.revokeTag("com.one")).performClick()
        awaitGone("com.one")
        awaitCond { "com.one" !in approvals.pendingRevokes("A") }
        assertNull(approvals.find("A", "com.one"))
        assertEquals(setOf("h1", "h1b"), gateway.revoked.toSet())
        assertTrue(compose.onAllNodes(hasText(retrying)).fetchSemanticsNodes().isEmpty())
        assertEquals("com.two", approvals.find("A", "com.two")?.packageName)
        assertTrue(gateway.apps.any { it.appHash == "h2" })
    }

    @Test
    fun revokeWhileGatewayFailsKeepsPending() {
        approvals.approve(approval("com.one"))
        approvals.clearPendingRevoke("A", "com.one")
        gateway.apps.add(app("com.one", "h1"))
        gateway.failRevoke = true
        ApiBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch()
        awaitText("com.one")
        compose.onNodeWithTag(ConnectedAppsActivity.revokeTag("com.one")).performClick()
        awaitGone("com.one")
        awaitText(retrying)
        assertNull(approvals.find("A", "com.one"))
        assertEquals(setOf("com.one"), approvals.pendingRevokes("A"))
    }

    @Test
    fun revokeWhenAccountNotRunningKeepsPendingAndSkipsGateway() {
        approvals.approve(approval("com.one"))
        gateway.apps.add(app("com.one", "h1"))
        val launcher = FakeLauncher()
        ApiBridge.install(gateway, launcher, NoSpaces)
        launch()
        awaitText("com.one")
        launcher.running = null
        compose.onNodeWithTag(ConnectedAppsActivity.revokeTag("com.one")).performClick()
        awaitGone("com.one")
        assertTrue(gateway.revoked.isEmpty())
        assertEquals(setOf("com.one"), approvals.pendingRevokes("A"))
        // No key is usable while the account is stopped: nothing to warn about.
        assertTrue(compose.onAllNodes(hasText(retrying)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun uninstalledPackageShowsPackageNameAndInstalledShowsLabel() {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = "com.one"
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = "com.one"
                labelRes = 0
                nonLocalizedLabel = "App One"
            }
        })
        approvals.approve(approval("com.one"))
        approvals.approve(approval("com.gone"))
        ApiBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch()
        awaitText("App One")
        awaitText("com.gone")
        assertTrue(compose.onAllNodes(hasTestTag(ConnectedAppsActivity.revokeTag("com.gone"))).fetchSemanticsNodes().size == 1)
    }

    /**
     * Back or a rotation right after the tap must not skip the revoke: the approval goes and the
     * key is revoked although the screen is gone before the revoke is done.
     */
    @Test
    fun revokeCompletesAfterLeavingTheScreen() {
        approvals.approve(approval("com.one"))
        // Only the screen's revoke may revoke h1: nothing is pending before it.
        approvals.clearPendingRevoke("A", "com.one")
        gateway.apps.add(app("com.one", "h1"))
        ApiBridge.install(gateway, FakeLauncher(), NoSpaces)
        val scenario = launch()
        awaitText("com.one")
        // The key revoke cannot finish before the screen is gone.
        val listing = java.util.concurrent.CountDownLatch(1)
        gateway.listGate = listing
        compose.onNodeWithTag(ConnectedAppsActivity.revokeTag("com.one")).performClick()
        scenario.close()
        listing.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (approvals.find("A", "com.one") != null || "h1" !in gateway.revoked) {
            check(System.currentTimeMillis() < deadline) { "the revoke was dropped" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /** Without a controller (bridge not installed) leaving the screen still drops the approval. */
    @Test
    fun revokeWithoutControllerCompletesAfterLeavingTheScreen() {
        approvals.approve(approval("com.one"))
        ApiBridge.install(gateway, FakeLauncher(), NoSpaces)
        val scenario = launch()
        awaitText("com.one")
        // The controller only exists once the bridge is installed; drop it after loading.
        ApiBridge.resetForTest()
        compose.onNodeWithTag(ConnectedAppsActivity.revokeTag("com.one")).performClick()
        scenario.close()
        val deadline = System.currentTimeMillis() + 5_000
        while (approvals.find("A", "com.one") != null) {
            check(System.currentTimeMillis() < deadline) { "the revoke was dropped" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /** The notice is about the latest revoke: a later revoke that completes clears it. */
    @Test
    fun noticeClearsWhenALaterRevokeCompletes() {
        approvals.approve(approval("com.one"))
        approvals.approve(approval("com.two"))
        approvals.clearPendingRevoke("A", "com.one")
        approvals.clearPendingRevoke("A", "com.two")
        gateway.apps.add(app("com.one", "h1"))
        gateway.apps.add(app("com.two", "h2"))
        gateway.failRevoke = true
        ApiBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch()
        awaitText("com.one")
        compose.onNodeWithTag(ConnectedAppsActivity.revokeTag("com.one")).performClick()
        awaitText(retrying)
        gateway.failRevoke = false
        compose.onNodeWithTag(ConnectedAppsActivity.revokeTag("com.two")).performClick()
        awaitGone("com.two")
        awaitGone(retrying)
    }

    @Test
    fun windowIsSecureWithOverlaysHidden() {
        ApiBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch().onActivity {
            val attrs = it.window.attributes
            assertTrue(attrs.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
            // Hidden APIs: the flag that setHideOverlayWindows(true) sets.
            val params = android.view.WindowManager.LayoutParams::class.java
            val hide = params.getField("SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS").getInt(null)
            assertTrue(params.getField("privateFlags").getInt(attrs) and hide != 0)
        }
    }

    /** Anytype's in-app Light/Dark setting goes through AppCompatDelegate. */
    @Test
    fun followsTheAppNightMode() {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        ApiBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch().onActivity {
            val night = it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES, night)
        }
    }

    /** The label is the other app's: only its first line shows, trimmed, and only a bounded length of it. */
    @Test
    fun labelIsSanitized() {
        // Read as HTML, as the system reads app labels: a raw newline is whitespace, and the
        // label ends at its first line break.
        installApp("com.one", "  Evil\nApp<br>Injected line")
        installApp("com.two", "L".repeat(5_000))
        approvals.approve(approval("com.one"))
        approvals.approve(approval("com.two"))
        ApiBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch()
        awaitText("Evil App")
        assertTrue(compose.onAllNodes(hasText("Evil App")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText("Injected", substring = true)).fetchSemanticsNodes().isEmpty())
        awaitText("LLLL")
        val long = compose.onAllNodes(hasText("LLLL", substring = true)).fetchSemanticsNodes()
            .flatMap { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text] }
            .map { it.text }
        assertTrue(long.isNotEmpty() && long.all { it.length <= MAX_LABEL_CHARS }, "label not bounded: ${long.map { it.length }}")
    }

    private fun installApp(pkg: String, label: String) {
        shadowOf(context.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = pkg
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = pkg
                labelRes = 0
                nonLocalizedLabel = label
            }
        })
    }

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val retrying get() = str(Loc.string.api_bridge_revoke_retrying)

    private companion object {
        const val CERT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val APPROVED_AT = 1_700_000_000_000
    }
}

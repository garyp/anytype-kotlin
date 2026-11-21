package com.anytypeio.anytype.agentbridge.access

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
import com.anytypeio.anytype.agentbridge.Access
import com.anytypeio.anytype.agentbridge.AccountLauncher
import com.anytypeio.anytype.agentbridge.AccountRef
import com.anytypeio.anytype.agentbridge.AccountStartException
import com.anytypeio.anytype.agentbridge.AgentApprovals
import com.anytypeio.anytype.agentbridge.AgentBridge
import com.anytypeio.anytype.agentbridge.Approval
import com.anytypeio.anytype.agentbridge.GatewayApp
import com.anytypeio.anytype.agentbridge.Grant
import com.anytypeio.anytype.agentbridge.KeyNames
import com.anytypeio.anytype.agentbridge.LocalApiGateway
import com.anytypeio.anytype.agentbridge.SpaceDirectory
import com.anytypeio.anytype.agentbridge.SpaceRef
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentAccessActivityTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var approvals: AgentApprovals

    private class FakeGateway(var failRevoke: Boolean = false, var failList: Boolean = false) : LocalApiGateway {
        val apps = CopyOnWriteArrayList<GatewayApp>()
        val revoked = CopyOnWriteArrayList<String>()
        override fun setListenAddr(addr: String): String? = null
        override fun listApps(): List<GatewayApp> {
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

    private class FakeLauncher(var running: AccountRef? = AccountRef("A", "Alice")) : AccountLauncher {
        var started = 0
        var fail: Exception? = null
        override fun runningAccount(): AccountRef? = running
        override suspend fun ensureStarted(): AccountRef {
            started++
            fail?.let { throw it }
            return AccountRef("A", "Alice").also { running = it }
        }
    }

    private object NoSpaces : SpaceDirectory {
        override suspend fun activeSpaces() = emptyList<SpaceRef>()
    }

    private val gateway = FakeGateway()

    @Before
    fun setUp() {
        AgentBridge.resetForTest()
        context.getSharedPreferences("agent_bridge", Context.MODE_PRIVATE).edit().clear().commit()
        approvals = AgentApprovals(context)
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS)
    }

    @After
    fun tearDown() = AgentBridge.resetForTest()

    private fun approval(pkg: String, account: String = "A", grant: Grant = Grant.ALL) =
        Approval(account, pkg, CERT, Access.READ_WRITE, grant, 1_700_000_000_000)

    private fun app(pkg: String, hash: String) = GatewayApp(hash, KeyNames.name(pkg, CERT), "k", 1)

    private fun launch(): ActivityScenario<AgentAccessActivity> =
        ActivityScenario.launch(Intent(context, AgentAccessActivity::class.java))

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
        AgentBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch()
        awaitText("com.one")
        awaitText("com.two")
        awaitText("All spaces")
        awaitText("2 spaces")
        awaitText("0123 4567 89ab cdef")
        assertTrue(compose.onAllNodes(hasText("com.other", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun startsAccountWhenNotRunning() {
        approvals.approve(approval("com.one"))
        val launcher = FakeLauncher(running = null)
        AgentBridge.install(gateway, launcher, NoSpaces)
        launch()
        awaitText("com.one")
        assertEquals(1, launcher.started)
    }

    @Test
    fun emptyState() {
        AgentBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch()
        awaitText("No apps have access to Anytype.")
    }

    @Test
    fun notSignedInShowsGenericError() {
        val launcher = FakeLauncher(running = null).apply {
            fail = AccountStartException(AccountStartException.NOT_LOGGED_IN, "secret detail")
        }
        AgentBridge.install(gateway, launcher, NoSpaces)
        launch()
        awaitText("Sign in to Anytype first")
        assertTrue(compose.onAllNodes(hasText("secret detail", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun bridgeNotInstalledDoesNotCrash() {
        launch()
        awaitText("Anytype isn't ready")
    }

    @Test
    fun revokeRemovesApprovalAndKeys() {
        approvals.approve(approval("com.one"))
        approvals.approve(approval("com.two"))
        gateway.apps.add(app("com.one", "h1"))
        gateway.apps.add(app("com.one", "h1b").copy(appName = KeyNames.prefix("com.one") + "old"))
        gateway.apps.add(app("com.two", "h2"))
        AgentBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch()
        awaitText("com.one")
        compose.onNodeWithTag(AgentAccessActivity.revokeTag("com.one")).performClick()
        awaitGone("com.one")
        awaitCond { "com.one" !in approvals.pendingRevokes("A") }
        assertNull(approvals.find("A", "com.one"))
        assertEquals(setOf("h1", "h1b"), gateway.revoked.toSet())
        assertEquals("com.two", approvals.find("A", "com.two")?.packageName)
        assertTrue(gateway.apps.any { it.appHash == "h2" })
    }

    @Test
    fun revokeWhileGatewayFailsKeepsPending() {
        approvals.approve(approval("com.one"))
        approvals.clearPendingRevoke("A", "com.one")
        gateway.apps.add(app("com.one", "h1"))
        gateway.failRevoke = true
        AgentBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch()
        awaitText("com.one")
        compose.onNodeWithTag(AgentAccessActivity.revokeTag("com.one")).performClick()
        awaitGone("com.one")
        assertNull(approvals.find("A", "com.one"))
        assertEquals(setOf("com.one"), approvals.pendingRevokes("A"))
    }

    @Test
    fun revokeWhenAccountNotRunningKeepsPendingAndSkipsGateway() {
        approvals.approve(approval("com.one"))
        gateway.apps.add(app("com.one", "h1"))
        val launcher = FakeLauncher()
        AgentBridge.install(gateway, launcher, NoSpaces)
        launch()
        awaitText("com.one")
        launcher.running = null
        compose.onNodeWithTag(AgentAccessActivity.revokeTag("com.one")).performClick()
        awaitGone("com.one")
        assertTrue(gateway.revoked.isEmpty())
        assertEquals(setOf("com.one"), approvals.pendingRevokes("A"))
    }

    @Test
    fun uninstalledPackageShowsPackageNameAndInstalledShowsLabel() {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = "com.one"
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = "com.one"
                labelRes = 0
                nonLocalizedLabel = "Agent One"
            }
        })
        approvals.approve(approval("com.one"))
        approvals.approve(approval("com.gone"))
        AgentBridge.install(gateway, FakeLauncher(), NoSpaces)
        launch()
        awaitText("Agent One")
        awaitText("com.gone")
        assertTrue(compose.onAllNodes(hasTestTag(AgentAccessActivity.revokeTag("com.gone"))).fetchSemanticsNodes().size == 1)
    }

    private companion object {
        const val CERT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}

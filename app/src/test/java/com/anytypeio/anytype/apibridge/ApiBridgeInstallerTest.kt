package com.anytypeio.anytype.apibridge

import com.anytypeio.anytype.core_models.ObjectWrapper
import com.anytypeio.anytype.core_models.Relations
import com.anytypeio.anytype.core_models.restrictions.SpaceStatus
import com.anytypeio.anytype.domain.account.AwaitAccountStartManager
import com.anytypeio.anytype.domain.auth.interactor.LaunchAccount
import com.anytypeio.anytype.domain.auth.interactor.LaunchWallet
import com.anytypeio.anytype.domain.auth.repo.AuthRepository
import com.anytypeio.anytype.domain.base.BaseUseCase
import com.anytypeio.anytype.domain.base.Either
import com.anytypeio.anytype.domain.config.ConfigStorage
import com.anytypeio.anytype.domain.multiplayer.SpaceViewSubscriptionContainer
import com.anytypeio.anytype.middleware.localapi.LocalApiApp
import com.anytypeio.anytype.middleware.localapi.LocalApiControl
import dagger.Lazy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

class ApiBridgeInstallerTest {

    private val awaitStart = mock<AwaitAccountStartManager>()
    private val configStorage = mock<ConfigStorage>()
    private val launchWallet = mock<LaunchWallet>()
    private val launchAccount = mock<LaunchAccount>()

    private fun authWithMnemonic(mnemonic: String?) = mock<AuthRepository> {
        onBlocking { getMnemonic() } doReturn mnemonic
    }

    private fun launcher(
        auth: AuthRepository,
        profileName: suspend () -> String? = { "Alice" }
    ) = MiddlewareAccountLauncher(
        auth = Lazy { auth },
        configStorage = Lazy { configStorage },
        awaitAccountStart = awaitStart,
        launchWallet = Lazy { launchWallet },
        launchAccount = Lazy { launchAccount },
        profileName = profileName
    )

    @Test
    fun accountStopsEmitOnLogoutOnly() = runTest {
        val manager = object : AwaitAccountStartManager {
            val state = MutableStateFlow<AwaitAccountStartManager.State>(AwaitAccountStartManager.State.Init)
            override fun state(): Flow<AwaitAccountStartManager.State> = state
            override fun setState(state: AwaitAccountStartManager.State) { this.state.value = state }
            override fun awaitStart() = state.filterIsInstance<AwaitAccountStartManager.State.Started>()
            override fun awaitStopped() = state.filterIsInstance<AwaitAccountStartManager.State.Stopped>()
            override fun hasStarted() = state.value is AwaitAccountStartManager.State.Started
        }
        val launcher = MiddlewareAccountLauncher(
            auth = Lazy { authWithMnemonic("m") },
            configStorage = Lazy { configStorage },
            awaitAccountStart = manager,
            launchWallet = Lazy { launchWallet },
            launchAccount = Lazy { launchAccount },
            profileName = { null }
        )
        val stops = mutableListOf<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { launcher.accountStops.toList(stops) }
        manager.setState(AwaitAccountStartManager.State.Started)
        assertEquals(0, stops.size)
        manager.setState(AwaitAccountStartManager.State.Stopped)
        assertEquals(1, stops.size)
        manager.setState(AwaitAccountStartManager.State.Started)
        manager.setState(AwaitAccountStartManager.State.Stopped)
        assertEquals(2, stops.size)
        job.cancel()
    }

    @Test
    fun ensureStartedWithoutMnemonicThrowsNotLoggedIn() = runBlocking {
        whenever(awaitStart.hasStarted()).thenReturn(false)
        for (mnemonic in listOf(null, "", "   ")) {
            try {
                launcher(authWithMnemonic(mnemonic)).ensureStarted()
                fail("expected AccountStartException for mnemonic=$mnemonic")
            } catch (e: AccountStartException) {
                assertEquals(AccountStartException.NOT_LOGGED_IN, e.code)
            }
        }
        verifyBlocking(launchWallet, never()) { invoke(any<BaseUseCase.None>()) }
        verifyBlocking(launchAccount, never()) { invoke(any<BaseUseCase.None>()) }
    }

    @Test
    fun ensureStartedLaunchesWalletThenAccount() = runBlocking {
        whenever(awaitStart.hasStarted()).thenReturn(false)
        whenever(configStorage.getAccountId()).thenReturn("acc-1")
        launchWallet.stub { onBlocking { invoke(any()) } doReturn Either.Right(Unit) }
        launchAccount.stub { onBlocking { invoke(any()) } doReturn Either.Right("analytics" to "network") }

        val account = launcher(authWithMnemonic("one two three")).ensureStarted()

        assertEquals(AccountRef("acc-1", "Alice"), account)
        val order = inOrder(launchWallet, launchAccount)
        order.verifyBlocking(launchWallet) { invoke(BaseUseCase.None) }
        order.verifyBlocking(launchAccount) { invoke(BaseUseCase.None) }
    }

    @Test
    fun ensureStartedMapsLaunchFailureToStartFailed() = runBlocking {
        whenever(awaitStart.hasStarted()).thenReturn(false)
        launchWallet.stub {
            onBlocking { invoke(any()) } doReturn Either.Left(RuntimeException("boom"))
        }
        try {
            launcher(authWithMnemonic("one two three")).ensureStarted()
            fail("expected AccountStartException")
        } catch (e: AccountStartException) {
            assertEquals(AccountStartException.START_FAILED, e.code)
        }
        verifyBlocking(launchAccount, never()) { invoke(any<BaseUseCase.None>()) }
    }

    @Test
    fun ensureStartedMapsLaunchExceptionToStartFailed() = runBlocking {
        whenever(awaitStart.hasStarted()).thenReturn(false)
        launchWallet.stub { onBlocking { invoke(any()) } doThrow IllegalStateException("x") }
        try {
            launcher(authWithMnemonic("one two three")).ensureStarted()
            fail("expected AccountStartException")
        } catch (e: AccountStartException) {
            assertEquals(AccountStartException.START_FAILED, e.code)
        }
    }

    @Test
    fun ensureStartedSkipsLaunchWhenAlreadyStarted() = runBlocking {
        whenever(awaitStart.hasStarted()).thenReturn(true)
        whenever(configStorage.getAccountId()).thenReturn("acc-1")
        val profileCalls = intArrayOf(0)
        val launcher = launcher(authWithMnemonic("one two three")) {
            profileCalls[0]++
            "Alice"
        }

        assertEquals(AccountRef("acc-1", "Alice"), launcher.ensureStarted())
        assertEquals(AccountRef("acc-1", "Alice"), launcher.ensureStarted())

        verifyBlocking(launchWallet, never()) { invoke(any<BaseUseCase.None>()) }
        verifyBlocking(launchAccount, never()) { invoke(any<BaseUseCase.None>()) }
        assertEquals("profile name is cached per account", 1, profileCalls[0])
        assertEquals(AccountRef("acc-1", "Alice"), launcher.runningAccount())
    }

    @Test
    fun runningAccountNullBeforeStart() {
        whenever(awaitStart.hasStarted()).thenReturn(false)
        whenever(configStorage.getAccountId()).thenReturn("acc-1")
        val auth = authWithMnemonic("one two three")

        assertNull(launcher(auth).runningAccount())
        verify(configStorage, never()).getAccountId()
    }

    @Test
    fun runningAccountAfterStartNeedsNoSuspendCall() {
        whenever(awaitStart.hasStarted()).thenReturn(true)
        whenever(configStorage.getAccountId()).thenReturn("acc-1")
        val auth = mock<AuthRepository>()

        val ref = launcher(auth) { error("must not resolve the name here") }.runningAccount()

        assertEquals("acc-1", ref?.id)
        verify(configStorage, times(1)).getAccountId()
    }

    @Test
    fun gatewayAdapterPassesCreateAppThrough() {
        val control = mock<LocalApiControl> {
            on { createApp("client", true, listOf("space.a")) } doReturn "k"
            on { createApp("client", false, null) } doReturn "k2"
        }
        val gateway = LocalApiControlGateway(control)

        assertEquals("k", gateway.createApp("client", true, listOf("space.a")))
        assertEquals("k2", gateway.createApp("client", false, null))
    }

    @Test
    fun gatewayAdapterDelegatesOtherCalls() {
        val control = mock<LocalApiControl> {
            on { setListenAddr("127.0.0.1:0") } doReturn "127.0.0.1:5555"
            on { listApps() } doReturn listOf(LocalApiApp("h", "n", "k", 1L))
        }
        val gateway = LocalApiControlGateway(control)

        assertEquals("127.0.0.1:5555", gateway.setListenAddr("127.0.0.1:0"))
        assertEquals(listOf(GatewayApp("h", "n", "k", 1L)), gateway.listApps())
        gateway.revokeApp("h")
        verify(control).revokeApp("h")
    }

    @Test
    fun spaceDirectoryReturnsOnlyActiveJoinedSpaces() = runBlocking {
        val views = listOf(
            spaceView("v1", "space.one", "One", SpaceStatus.OK, SpaceStatus.SPACE_ACTIVE),
            spaceView("v2", "space.two", null, SpaceStatus.OK, SpaceStatus.UNKNOWN),
            spaceView("v3", "space.joining", "Invite", SpaceStatus.OK, SpaceStatus.SPACE_JOINING),
            spaceView("v4", "space.removing", "Gone", SpaceStatus.OK, SpaceStatus.SPACE_REMOVING),
            spaceView("v5", "space.deleted", "Del", SpaceStatus.OK, SpaceStatus.SPACE_DELETED),
            spaceView("v6", "space.loading", "Load", SpaceStatus.LOADING, SpaceStatus.SPACE_ACTIVE),
            spaceView("v7", null, "No target", SpaceStatus.OK, SpaceStatus.SPACE_ACTIVE)
        )
        val container = mock<SpaceViewSubscriptionContainer> {
            on { observe() } doReturn MutableStateFlow(views)
        }

        val spaces = SpaceViewDirectory(Lazy { container }).activeSpaces()

        assertEquals(
            listOf(SpaceRef("space.one", "One"), SpaceRef("space.two", "")),
            spaces
        )
        verify(container, never()).start()
    }

    private fun spaceView(
        id: String,
        target: String?,
        name: String?,
        local: SpaceStatus,
        account: SpaceStatus
    ) = ObjectWrapper.SpaceView(
        buildMap {
            put(Relations.ID, id)
            target?.let { put(Relations.TARGET_SPACE_ID, it) }
            name?.let { put(Relations.NAME, it) }
            put(Relations.SPACE_LOCAL_STATUS, local.code.toDouble())
            put(Relations.SPACE_ACCOUNT_STATUS, account.code.toDouble())
        }
    )
}

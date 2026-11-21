package com.anytypeio.anytype.agentbridge

import com.anytypeio.anytype.domain.account.AwaitAccountStartManager
import com.anytypeio.anytype.domain.auth.interactor.LaunchAccount
import com.anytypeio.anytype.domain.auth.interactor.LaunchWallet
import com.anytypeio.anytype.domain.auth.repo.AuthRepository
import com.anytypeio.anytype.domain.config.ConfigStorage
import com.anytypeio.anytype.domain.multiplayer.SpaceViewSubscriptionContainer
import com.anytypeio.anytype.core_models.restrictions.SpaceStatus
import com.anytypeio.anytype.feature_os_widgets.presentation.MiddlewareLaunchResult
import com.anytypeio.anytype.feature_os_widgets.presentation.awaitFirstSpacesEmission
import com.anytypeio.anytype.feature_os_widgets.presentation.launchMiddlewareForConfig
import com.anytypeio.anytype.middleware.agent.LocalApiApp
import com.anytypeio.anytype.middleware.agent.LocalApiControl
import dagger.Lazy
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Hands the app's middleware to [AgentBridge].
 *
 * [install] must run exactly once per process, synchronously in `Application.onCreate`, so the
 * bridge service never sees a half-installed bridge. It does no disk or network I/O: every
 * adapter holds its dependencies through [Lazy] and touches them only when the bridge calls it,
 * which the bridge does off the main thread.
 */
class AgentBridgeInstaller @Inject constructor(
    private val gateway: LocalApiGateway,
    private val launcher: AccountLauncher,
    private val spaces: SpaceDirectory
) {
    fun install() {
        AgentBridge.install(gateway = gateway, launcher = launcher, spaces = spaces)
    }
}

/** [LocalApiGateway] over heart's local JSON API control calls. */
class LocalApiControlGateway(
    private val control: Lazy<LocalApiControl>
) : LocalApiGateway {

    override fun setListenAddr(addr: String): String? = control.get().setListenAddr(addr)

    override fun listApps(): List<GatewayApp> = control.get().listApps().map { it.toGatewayApp() }

    override fun createApp(name: String, readWrite: Boolean, spaceIds: List<String>?): GatewayApp =
        control.get().createApp(name, readWrite, spaceIds).toGatewayApp()

    override fun revokeApp(appHash: String) = control.get().revokeApp(appHash)

    private fun LocalApiApp.toGatewayApp() = GatewayApp(
        appHash = appHash,
        appName = appName,
        appKey = appKey,
        createdAt = createdAt
    )
}

/**
 * [AccountLauncher] that starts the stored account the way the OS widget config screens do.
 *
 * The account id comes from [ConfigStorage], which is in memory and safe to read from any thread.
 * The display name is the name of the profile object, which needs a middleware call. That call
 * is suspending, and [runningAccount] is not, so [ensureStarted] resolves the name and caches it
 * per account id; [runningAccount] only reads the cache. The bridge uses [runningAccount] for
 * the account id alone, and takes the name for the consent screen from [ensureStarted].
 *
 * Which of NOT_LOGGED_IN and START_FAILED happened reaches only the bridge module, which
 * withholds it from callers that no account has approved.
 */
class MiddlewareAccountLauncher(
    private val auth: Lazy<AuthRepository>,
    private val configStorage: Lazy<ConfigStorage>,
    private val awaitAccountStart: AwaitAccountStartManager,
    private val launchWallet: Lazy<LaunchWallet>,
    private val launchAccount: Lazy<LaunchAccount>,
    private val profileName: suspend () -> String?
) : AccountLauncher {

    private val mutex = Mutex()

    @Volatile
    private var cachedName: Pair<String, String>? = null

    override fun runningAccount(): AccountRef? {
        if (!awaitAccountStart.hasStarted()) return null
        val id = configStorage.get().getAccountId() ?: return null
        return AccountRef(id = id, name = cachedName?.takeIf { it.first == id }?.second.orEmpty())
    }

    override suspend fun ensureStarted(): AccountRef = mutex.withLock {
        val mnemonic = try {
            auth.get().getMnemonic()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "$TAG could not read the stored mnemonic")
            null
        }
        if (mnemonic.isNullOrBlank()) {
            throw AccountStartException(AccountStartException.NOT_LOGGED_IN, "No account is stored")
        }
        if (!awaitAccountStart.hasStarted()) launch()
        val id = configStorage.get().getAccountId()
            ?: throw AccountStartException(AccountStartException.START_FAILED, "Account id missing")
        AccountRef(id = id, name = nameOf(id))
    }

    private suspend fun launch() {
        val result = try {
            launchMiddlewareForConfig(TAG, launchWallet.get(), launchAccount.get())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MiddlewareLaunchResult.Failure(e.message ?: e.javaClass.simpleName)
        }
        if (result is MiddlewareLaunchResult.Failure) {
            throw AccountStartException(AccountStartException.START_FAILED, result.message)
        }
    }

    private suspend fun nameOf(id: String): String {
        cachedName?.takeIf { it.first == id }?.let { return it.second }
        val name = try {
            profileName()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "$TAG could not resolve the profile name")
            null
        }
        if (name.isNullOrBlank()) return ""
        cachedName = id to name
        return name
    }

    private companion object {
        const val TAG = "AgentBridge"
    }
}

/**
 * [SpaceDirectory] over the app-wide space view subscription.
 *
 * The container starts its own subscription when the account starts (it observes
 * [AwaitAccountStartManager]), and a second start() would subscribe twice, so this never calls it.
 * Returns the spaces the user has joined and can open: no pending invites, no spaces being
 * removed or deleted. Ids are full space ids (targetSpaceId), as heart's app grants expect.
 */
class SpaceViewDirectory(
    private val container: Lazy<SpaceViewSubscriptionContainer>
) : SpaceDirectory {

    override suspend fun activeSpaces(): List<SpaceRef> {
        val spaces = container.get()
        awaitFirstSpacesEmission(spaces.observe())
        return spaces.observe().first()
            .filter { it.isActive && it.spaceAccountStatus != SpaceStatus.SPACE_JOINING }
            .mapNotNull { view ->
                val id = view.targetSpaceId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SpaceRef(id = id, name = view.name.orEmpty())
            }
    }
}

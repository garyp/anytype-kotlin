package com.anytypeio.anytype.apibridge

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Process-wide entry point of the API bridge. The app calls [install] once its middleware
 * ports exist; until then [ApiBridgeService] answers every connect with an INTERNAL error.
 *
 * The [BridgeController] lives here, not in the service: it remembers the listener heart opened,
 * and closing that listener after the last unbind must outlive the service instance, which the
 * system destroys right after that unbind.
 */
object ApiBridge {

    const val ACTION_BIND = "io.anytype.apibridge.BIND"

    @Volatile
    internal var gateway: LocalApiGateway? = null
        private set

    @Volatile
    internal var launcher: AccountLauncher? = null
        private set

    @Volatile
    internal var spaces: SpaceDirectory? = null
        private set

    private var controller: BridgeController? = null
    private val lock = Any()

    /** The process's one approvals store, and the scope its DataStore runs in; guarded by [lock]. */
    private var approvals: AppApprovals? = null
    private var approvalsScope: CoroutineScope? = null

    /** Test seam: replaces the approvals store, so a test owns its file. */
    @VisibleForTesting
    @Volatile
    internal var approvalsOverride: AppApprovals? = null

    /**
     * Distinct bind intents currently bound. The system calls onBind/onUnbind once per intent
     * (by [android.content.Intent.filterEquals]), not per client, so this is zero exactly when no
     * binding of any kind remains.
     */
    internal val activeBindings = AtomicInteger()

    internal fun idle() = activeBindings.get() <= 0

    /** Runs the listener close after the last unbind; never cancelled. */
    @VisibleForTesting
    @Volatile
    internal var unbindDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Runs connects, unbind closes and revoke retries; outlives every service and screen. */
    internal val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The connect in flight per calling uid. A second call from the same uid gets the same
     * result instead of queueing another connect, so one uid holds at most one connect at a time.
     */
    internal val inFlightConnects = ConcurrentHashMap<Int, Deferred<BridgeController.ConnectResult>>()

    /** Test seam: replaces the [CallerVerifier]. */
    @VisibleForTesting
    @Volatile
    internal var callerApprovalsOverride: CallerApprovals? = null

    /**
     * Call exactly once per process, synchronously in `Application.onCreate` (on API 35+), so no
     * bind can arrive before the ports exist. A second call drops the controller without closing
     * the listener it opened. The ports must be cheap to construct: their methods are called off
     * the main thread, so do the I/O there, not here.
     */
    fun install(gateway: LocalApiGateway, launcher: AccountLauncher, spaces: SpaceDirectory) {
        synchronized(lock) {
            this.gateway = gateway
            this.launcher = launcher
            this.spaces = spaces
            controller = null
        }
        // Like the app's other account-scoped components, follow the account lifecycle.
        backgroundScope.launch {
            launcher.accountStops.collect { synchronized(lock) { controller }?.onAccountStopped() }
        }
    }

    /**
     * The approvals store. DataStore allows one instance per file in a process, so every user
     * (controller, caller check, consent and access screens) shares this one. Works before
     * [install].
     */
    internal fun approvals(context: Context): AppApprovals = synchronized(lock) {
        approvalsOverride?.let { return it }
        approvals?.let { return it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        AppApprovals(approvalsDataStore(context.applicationContext, scope)).also {
            approvals = it
            approvalsScope = scope
        }
    }

    /** Null until [install] has run. */
    internal fun controller(context: Context): BridgeController? = synchronized(lock) {
        controller?.let { return it }
        val gateway = gateway ?: return null
        val launcher = launcher ?: return null
        if (Build.VERSION.SDK_INT < 35) return null
        val app = context.applicationContext
        val store = approvals(app)
        BridgeController(
            gateway = gateway,
            launcher = launcher,
            approvals = store,
            callerApprovals = callerApprovalsOverride
                ?: CallerVerifier(app.packageManager, store),
            dispatcher = Dispatchers.IO,
            idle = ::idle,
            retryScope = backgroundScope
        ).also { controller = it }
    }

    @VisibleForTesting
    internal fun resetForTest() = synchronized(lock) {
        gateway = null
        launcher = null
        spaces = null
        controller = null
        callerApprovalsOverride = null
        approvalsOverride = null
        // Closes the default store's file, and waits until DataStore lets go of it, so the
        // next test may open it again.
        approvalsScope?.let { runBlocking { it.coroutineContext.job.cancelAndJoin() } }
        approvalsScope = null
        approvals = null
        unbindDispatcher = Dispatchers.IO
        activeBindings.set(0)
        // Revoke retries of an earlier test's controller would sweep this test's store.
        backgroundScope.coroutineContext.cancelChildren()
        inFlightConnects.clear()
    }
}

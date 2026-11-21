package com.anytypeio.anytype.agentbridge

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide entry point of the agent bridge. The app calls [install] once its middleware
 * ports exist; until then [AgentBridgeService] answers every connect with an INTERNAL error.
 *
 * The [BridgeController] lives here, not in the service: it remembers the listener heart opened,
 * and closing that listener after the last unbind must outlive the service instance, which the
 * system destroys right after that unbind.
 */
object AgentBridge {

    const val ACTION_BIND = "io.anytype.agentbridge.BIND"

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

    internal val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Test seams: replace the [CallerVerifier]-backed checks. */
    @VisibleForTesting
    @Volatile
    internal var callerCheckOverride: CallerCheck? = null

    @VisibleForTesting
    @Volatile
    internal var approvedAnywhereOverride: ApprovedAnywhere? = null

    fun install(gateway: LocalApiGateway, launcher: AccountLauncher, spaces: SpaceDirectory) {
        synchronized(lock) {
            this.gateway = gateway
            this.launcher = launcher
            this.spaces = spaces
            controller = null
        }
    }

    /** Null until [install] has run. */
    internal fun controller(context: Context): BridgeController? = synchronized(lock) {
        controller?.let { return it }
        val gateway = gateway ?: return null
        val launcher = launcher ?: return null
        if (Build.VERSION.SDK_INT < 35) return null
        val app = context.applicationContext
        val approvals = AgentApprovals(app)
        val verifier = CallerVerifier(app.packageManager, approvals)
        BridgeController(
            gateway = gateway,
            launcher = launcher,
            approvals = approvals,
            callerCheck = callerCheckOverride
                ?: CallerCheck { uid, acc -> verifier.approvedCaller(uid, acc) },
            approvedAnywhere = approvedAnywhereOverride
                ?: ApprovedAnywhere { uid -> verifier.approvedAnywhere(uid) },
            probe = ::probe,
            dispatcher = Dispatchers.IO,
            idle = ::idle
        ).also { controller = it }
    }

    /** True when something accepts a TCP connection at `host:port` within 300 ms. */
    internal fun probe(addr: String): Boolean {
        val i = addr.lastIndexOf(':')
        if (i <= 0) return false
        val port = addr.substring(i + 1).toIntOrNull() ?: return false
        return try {
            Socket().use { it.connect(InetSocketAddress(addr.substring(0, i), port), PROBE_TIMEOUT_MS) }
            true
        } catch (e: Exception) {
            false
        }
    }

    @VisibleForTesting
    internal fun resetForTest() = synchronized(lock) {
        gateway = null
        launcher = null
        spaces = null
        controller = null
        callerCheckOverride = null
        approvedAnywhereOverride = null
        unbindDispatcher = Dispatchers.IO
        activeBindings.set(0)
    }

    private const val PROBE_TIMEOUT_MS = 300
}

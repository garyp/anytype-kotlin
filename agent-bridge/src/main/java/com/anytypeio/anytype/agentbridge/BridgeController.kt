package com.anytypeio.anytype.agentbridge

import android.util.Log
import io.anytype.agentbridge.IAgentBridgeCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Test seam over [CallerVerifier.approvedCaller]. */
fun interface CallerCheck {
    fun approved(uid: Int, accountId: String): Approval?
}

/** Test seam over [CallerVerifier.approvedAnywhere]. */
fun interface ApprovedAnywhere {
    fun approvedAnywhere(uid: Int): Boolean
}

/**
 * Connect and unbind logic of the agent bridge.
 *
 * Every connect runs under one [Mutex]: turn away a caller approved under no account at all
 * (it must not start the account or learn its state), start the account, check the caller's
 * approval for that account, sweep keys pending revocation, make sure heart's local API listens,
 * and hand out the caller's key. Blocking work (gateway, approvals store, [probe]) runs on
 * [dispatcher].
 *
 * [idle] is true when no client is bound to the service. It is read under the mutex, so closing
 * the listener and a new connect never interleave.
 */
class BridgeController(
    private val gateway: LocalApiGateway,
    private val launcher: AccountLauncher,
    private val approvals: AgentApprovals,
    private val callerCheck: CallerCheck,
    private val approvedAnywhere: ApprovedAnywhere,
    /** True when something accepts connections at the given host:port. */
    private val probe: (String) -> Boolean,
    private val dispatcher: CoroutineDispatcher,
    /** True when no client binding of any kind remains. */
    private val idle: () -> Boolean
) {

    sealed interface ConnectResult {
        data class Ready(val baseUrl: String, val apiKey: String) : ConnectResult
        object ApprovalRequired : ConnectResult
        data class Error(val code: Int, val message: String) : ConnectResult
    }

    private val mutex = Mutex()

    /** The address heart's local API listens on; guarded by [mutex]. */
    private var listenAddr: String? = null

    suspend fun connect(uid: Int): ConnectResult = mutex.withLock {
        val result = try {
            connectLocked(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            ConnectResult.Error(IAgentBridgeCallback.INTERNAL, e.message ?: e.javaClass.simpleName)
        }
        // The last client unbound while this connect ran, and the close it triggered may have
        // found nothing to close yet: close whatever this connect opened.
        if (idle()) closeListenerLocked()
        result
    }

    private suspend fun connectLocked(uid: Int): ConnectResult {
        // 0. A caller approved under no account learns nothing, and starts nothing.
        if (!withContext(dispatcher) { approvedAnywhere.approvedAnywhere(uid) }) {
            return ConnectResult.ApprovalRequired
        }

        // 1. Account.
        val account = try {
            launcher.ensureStarted()
        } catch (e: AccountStartException) {
            return ConnectResult.Error(e.code, e.message ?: "account start failed")
        }

        return withContext(dispatcher) {
            // 2. Approval, per account.
            val approval = callerCheck.approved(uid, account.id)
                ?: return@withContext ConnectResult.ApprovalRequired

            // 3. Sweep keys pending revocation.
            val apps = try {
                sweep(account.id, approval.packageName, gateway.listApps())
            } catch (e: Exception) {
                return@withContext apiFailed("sweep", e)
            }

            // 4. Listener.
            val addr = try {
                ensureListening()
            } catch (e: Exception) {
                return@withContext apiFailed("setListenAddr", e)
            }

            // 5. Key.
            val key = try {
                keyFor(approval, apps)
            } catch (e: Exception) {
                return@withContext apiFailed("key", e)
            }

            // 6.
            ConnectResult.Ready("http://$addr", key)
        }
    }

    /**
     * Revokes every key of each package pending revocation for this account, including a key
     * named like the current approval: the name depends only on package and cert, so a key
     * minted under an earlier approval would otherwise outlive a narrower re-approval. Step 5
     * then mints a fresh key with the stored grant. A pending entry is cleared only when all
     * its revokes succeed. Returns the apps that remain.
     *
     * Throws when a key of [callerPkg] could not be revoked, so that key is never handed out.
     */
    private fun sweep(accountId: String, callerPkg: String, listed: List<GatewayApp>): List<GatewayApp> {
        val apps = listed.toMutableList()
        var callerFailure: Exception? = null
        for (pkg in approvals.pendingRevokes(accountId)) {
            val prefix = KeyNames.prefix(pkg)
            var failed = false
            apps.filter { it.appName.startsWith(prefix) }.forEach { app ->
                try {
                    gateway.revokeApp(app.appHash)
                    apps.remove(app)
                } catch (e: Exception) {
                    // Keep the pending entry; the next connect retries.
                    Log.w(TAG, "revoke of ${app.appName} failed", e)
                    failed = true
                    if (pkg == callerPkg) callerFailure = e
                }
            }
            if (!failed) approvals.clearPendingRevoke(accountId, pkg)
        }
        callerFailure?.let { throw it }
        return apps
    }

    private fun ensureListening(): String {
        listenAddr?.let { if (probe(it)) return it }
        // A failed bind means heart has already closed the old listener.
        listenAddr = null
        val bound = gateway.setListenAddr(LISTEN)
            ?: throw IllegalStateException("heart reported no listen address")
        listenAddr = bound
        return bound
    }

    private fun keyFor(approval: Approval, apps: List<GatewayApp>): String {
        val name = KeyNames.name(approval.packageName, approval.certSha256)
        val mine = apps.filter { it.appName == name }.sortedByDescending { it.createdAt }
        val newest = mine.firstOrNull()
        if (newest != null) {
            mine.drop(1).forEach { gateway.revokeApp(it.appHash) }
            return newest.appKey
        }
        return gateway.createApp(
            name = name,
            readWrite = approval.access == Access.READ_WRITE,
            spaceIds = if (approval.grant.allSpaces) null else approval.grant.spaceIds
        ).appKey
    }

    private fun apiFailed(step: String, e: Exception): ConnectResult.Error {
        Log.w(TAG, "$step failed", e)
        return ConnectResult.Error(IAgentBridgeCallback.API_FAILED, e.message ?: "$step failed")
    }

    /** Closes the listener if, under the mutex, [idle] still says no client is bound. */
    suspend fun onAllUnbound() {
        mutex.withLock {
            if (idle()) closeListenerLocked()
        }
    }

    private suspend fun closeListenerLocked() {
        if (listenAddr == null) return
        listenAddr = null
        try {
            withContext(dispatcher) { gateway.setListenAddr("") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "closing the listener failed", e)
        }
    }

    companion object {
        const val LISTEN = "127.0.0.1:0"
        private const val TAG = "AgentBridge"
    }
}

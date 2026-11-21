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

/**
 * Connect and unbind logic of the agent bridge.
 *
 * Every connect runs under one [Mutex]: start the account, check the caller's approval for that
 * account, sweep keys pending revocation, make sure heart's local API listens, and hand out the
 * caller's key. Blocking work (gateway, approvals store, [probe]) runs on [dispatcher].
 */
class BridgeController(
    private val gateway: LocalApiGateway,
    private val launcher: AccountLauncher,
    private val approvals: AgentApprovals,
    private val callerCheck: CallerCheck,
    /** True when something accepts connections at the given host:port. */
    private val probe: (String) -> Boolean,
    private val dispatcher: CoroutineDispatcher
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
        try {
            connectLocked(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            ConnectResult.Error(IAgentBridgeCallback.INTERNAL, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun connectLocked(uid: Int): ConnectResult {
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
                sweep(account.id, gateway.listApps())
            } catch (e: Exception) {
                return@withContext apiFailed("listApps", e)
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

    /** Revokes stale keys of pending packages; returns the apps that remain. */
    private fun sweep(accountId: String, listed: List<GatewayApp>): List<GatewayApp> {
        val apps = listed.toMutableList()
        for (pkg in approvals.pendingRevokes(accountId)) {
            val keep = approvals.find(accountId, pkg)?.let { KeyNames.name(it.packageName, it.certSha256) }
            val prefix = KeyNames.prefix(pkg)
            var failed = false
            apps.filter { it.appName.startsWith(prefix) && it.appName != keep }.forEach { app ->
                try {
                    gateway.revokeApp(app.appHash)
                    apps.remove(app)
                } catch (e: Exception) {
                    // Keep the pending entry; the next connect retries.
                    Log.w(TAG, "revoke of ${app.appName} failed", e)
                    failed = true
                }
            }
            if (!failed) approvals.clearPendingRevoke(accountId, pkg)
        }
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

    /** Closes the listener once the last client has unbound. */
    suspend fun onAllUnbound() {
        mutex.withLock {
            if (listenAddr == null) return@withLock
            listenAddr = null
            try {
                withContext(dispatcher) { gateway.setListenAddr("") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "closing the listener failed", e)
            }
        }
    }

    companion object {
        const val LISTEN = "127.0.0.1:0"
        private const val TAG = "AgentBridge"
    }
}

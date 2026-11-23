package com.anytypeio.anytype.apibridge

import android.util.Log
import io.anytype.apibridge.IApiBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Connect, revoke and unbind logic of the API bridge.
 *
 * Every connect runs under one [Mutex]: turn away a caller approved under no account at all
 * (it must not start the account or learn its state), start the account, check the caller's
 * approval for that account, sweep keys pending revocation, make sure heart's local API listens,
 * and hand out the caller's key. The mutex keeps the listener and key state whole: one bind at a
 * time, no duplicate keys, and no close of the listener in the middle of a connect. Blocking work
 * (gateway, approvals store) runs on [dispatcher].
 *
 * [revokeApp] takes no lock, so it never waits behind a connect that is starting the account.
 * It still cannot leave a revoked app with a working key, because of the order of the steps on
 * each side: a revoke removes the approval before it lists the keys, and a connect mints the
 * key before it re-checks the approval (step 5b). Whichever runs first, one side sees the new
 * key: either the revoke lists it and revokes it, or the connect finds the approval gone and
 * revokes it. A connect may still hand out a key that the revoke cuts off a moment later, which
 * is the outcome the user asked for.
 *
 * Every key revoke goes through one sweep of the account's pending revokes: [AppApprovals]
 * queues one for a package on every approval and revocation, and the sweep clears it only once
 * every key of that package is revoked. The sweep runs on connect (before any listener opens),
 * on [revokeApp], and on the last unbind, while the account runs. When a revoke fails, a retry
 * loop in [retryScope] sweeps again with backoff while that account runs.
 *
 * [idle] is true when no client is bound to the service. It is read under the mutex, so closing
 * the listener and a new connect never interleave.
 */
class BridgeController(
    private val gateway: LocalApiGateway,
    private val launcher: AccountLauncher,
    private val approvals: AppApprovals,
    private val callerApprovals: CallerApprovals,
    private val dispatcher: CoroutineDispatcher,
    /** True when no client binding of any kind remains. */
    private val idle: () -> Boolean,
    /** Runs the revoke retry loop; must outlive any screen that starts a revoke. */
    private val retryScope: CoroutineScope,
    /** Delay before the first retry; doubles up to [retryMaxDelayMs]. */
    private val retryStartDelayMs: Long = RETRY_START_MS,
    private val retryMaxDelayMs: Long = RETRY_MAX_MS
) {

    sealed interface ConnectResult {
        data class Ready(val baseUrl: String, val apiKey: String) : ConnectResult
        object ApprovalRequired : ConnectResult
        data class Error(val code: Int, val message: String) : ConnectResult
    }

    private val mutex = Mutex()

    /** The address heart's local API listens on; guarded by [mutex]. */
    private var listenAddr: String? = null

    /** [AccountLauncher.generation] when [listenAddr] was bound; guarded by [mutex]. */
    private var listenGeneration = 0L

    /** Revoke retry loops by account id; guarded by itself. */
    private val retries = mutableMapOf<String, Job>()

    /**
     * False when [uid] is approved under no account. Blocking but cheap, and takes no lock: the
     * service answers such a caller at once, so it never waits on the mutex or starts an account.
     * [connect] checks the same again under the mutex.
     */
    fun isApprovedAnywhere(uid: Int): Boolean = callerApprovals.verifiedApprovals(uid).isNotEmpty()

    suspend fun connect(uid: Int): ConnectResult = mutex.withLock {
        val result = try {
            connectLocked(uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            ConnectResult.Error(IApiBridge.INTERNAL, e.message ?: e.javaClass.simpleName)
        }
        // The last client unbound while this connect ran, and the close it triggered may have
        // found nothing to close yet: close whatever this connect opened.
        if (idle()) withContext(dispatcher) { closeListener() }
        result
    }

    private suspend fun connectLocked(uid: Int): ConnectResult {
        // 0. A caller approved under no account learns nothing, and starts nothing. The service
        // turns such callers away before they get here; this catches an approval revoked since.
        if (withContext(dispatcher) { callerApprovals.verifiedApprovals(uid) }.isEmpty()) {
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
            val approval = callerApprovals.verifiedApprovals(uid).find { it.accountId == account.id }
            if (approval == null) {
                sweepQuietly(account.id)
                return@withContext ConnectResult.ApprovalRequired
            }

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

            // 5b. Defence in depth: the approval may have gone or changed since step 2. Never
            // hand out a key for an approval that no longer exists, or that a re-approval
            // replaced (another cert, access or grant); sweep what step 5 minted or reused.
            // The withdrawal or re-approval queued a pending revoke, but step 3 may already have
            // cleared it, so queue it again.
            val current = approvals.find(account.id, approval.packageName)
            if (current == null || !current.sameGrantAs(approval)) {
                approvals.queueRevoke(account.id, approval.packageName)
                sweepQuietly(account.id)
                return@withContext ConnectResult.ApprovalRequired
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
    private fun sweep(accountId: String, callerPkg: String?, listed: List<GatewayApp>): List<GatewayApp> {
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
                    // Keep the pending entry; the retry loop and the next connect retry it.
                    Log.w(TAG, "revoke of ${app.appName} failed", e)
                    failed = true
                    if (pkg == callerPkg) callerFailure = e
                }
            }
            if (failed) scheduleRetry(accountId) else approvals.clearPendingRevoke(accountId, pkg)
        }
        callerFailure?.let { throw it }
        return apps
    }

    /** [sweep] for no caller; a failure only logs, and the pending entries stay. */
    private fun sweepQuietly(accountId: String) {
        if (approvals.pendingRevokes(accountId).isEmpty()) return
        try {
            sweep(accountId, null, gateway.listApps())
        } catch (e: Exception) {
            Log.w(TAG, "sweep failed", e)
            scheduleRetry(accountId)
        }
    }

    /**
     * Sweeps [accountId]'s pending revokes again with backoff until none is left or the account
     * stops running; a stopped account's keys are unusable, and the next connect sweeps them.
     * One loop per account.
     */
    private fun scheduleRetry(accountId: String) {
        synchronized(retries) {
            if (retries[accountId]?.isActive == true) return
            retries[accountId] = retryScope.launch {
                var wait = retryStartDelayMs
                while (true) {
                    delay(wait)
                    // Under the mutex, unlike revokeApp: a sweep here that ran between a
                    // connect's own sweep and its return could revoke the key it hands out.
                    val done = mutex.withLock {
                        withContext(dispatcher) {
                            if (launcher.runningAccount()?.id != accountId) {
                                true
                            } else {
                                sweepQuietly(accountId)
                                approvals.pendingRevokes(accountId).isEmpty()
                            }
                        }
                    }
                    if (done) break
                    wait = (wait * 2).coerceAtMost(retryMaxDelayMs)
                }
            }
        }
    }

    /**
     * Withdraws [pkg]'s approval for [accountId] and, when that account runs, sweeps the revoke
     * that queued: heart rejects a revoked key at once, which cuts off a connected app.
     *
     * Returns true when the revoke is complete: no key of [pkg] is left, or the account does not
     * run, so no key is usable and the next connect sweeps them. False when a key revoke failed
     * and a background retry runs while the account runs.
     */
    suspend fun revokeApp(accountId: String, pkg: String): Boolean = withContext(dispatcher) {
        // Withdraw before sweeping: see the class comment for why no lock is needed.
        approvals.revoke(accountId, pkg)
        if (launcher.runningAccount()?.id != accountId) return@withContext true
        sweepQuietly(accountId)
        pkg !in approvals.pendingRevokes(accountId)
    }

    /**
     * Reuses the cached address only while the account has not restarted since the bind. Heart
     * drops its listener on every account start and stop, and any local app may then bind the old
     * port: the old address would hand the caller that app's server. Every start and stop goes
     * through [AccountLauncher.generation], and heart closes the listener on nothing else.
     */
    private fun ensureListening(): String {
        // Read before binding: a restart during the bind leaves a stale generation behind, so
        // the next connect binds again.
        val generation = launcher.generation()
        listenAddr?.let { if (generation == listenGeneration) return it }
        // A failed bind means heart has already closed the old listener.
        listenAddr = null
        val bound = gateway.setListenAddr(LISTEN)
            ?: throw IllegalStateException("heart reported no listen address")
        listenAddr = bound
        listenGeneration = generation
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
        )
    }

    /** Same pin, access and grant; the approval date does not matter. */
    private fun Approval.sameGrantAs(other: Approval) =
        certSha256 == other.certSha256 && access == other.access && grant == other.grant

    private fun apiFailed(step: String, e: Exception): ConnectResult.Error {
        Log.w(TAG, "$step failed", e)
        return ConnectResult.Error(IApiBridge.API_FAILED, e.message ?: "$step failed")
    }

    /**
     * Sweeps the running account's pending revokes, then closes the listener if, under the
     * mutex, [idle] still says no client is bound.
     */
    suspend fun onAllUnbound() {
        mutex.withLock {
            // Both reads are in memory: switch threads only when there is work, and only once.
            val sweepFor = launcher.runningAccount()?.id
                ?.takeIf { approvals.pendingRevokes(it).isNotEmpty() }
            if (sweepFor == null && (listenAddr == null || !idle())) return
            withContext(dispatcher) {
                sweepFor?.let { sweepQuietly(it) }
                if (idle()) closeListener()
            }
        }
    }

    /** Closes heart's listener if one is open. Call under the mutex, on [dispatcher]. */
    private fun closeListener() {
        if (listenAddr == null) return
        listenAddr = null
        try {
            gateway.setListenAddr("")
        } catch (e: Exception) {
            Log.w(TAG, "closing the listener failed", e)
        }
    }

    companion object {
        const val LISTEN = "127.0.0.1:0"
        const val RETRY_START_MS = 2_000L
        const val RETRY_MAX_MS = 60_000L
        private const val TAG = "ApiBridge"
    }
}

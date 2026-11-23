package com.anytypeio.anytype.apibridge

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.first

/**
 * Persistent approvals, pending key revocations and denial cooldowns, in an
 * [ApprovalsState] [DataStore] (see [ApprovalsSerializer] for the file format).
 *
 * Every change is one [DataStore.updateData], which serializes read-modify-write across all
 * callers, so no lock is needed here. The store does its own I/O off the caller's thread; reads
 * after the first come from memory.
 */
class AppApprovals(
    private val store: DataStore<ApprovalsState>,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun find(accountId: String, pkg: String): Approval? =
        state().approvals.firstOrNull { it.accountId == accountId && it.packageName == pkg }

    suspend fun all(): List<Approval> = state().approvals

    /** Replaces the package's approval; its earlier keys are queued for revocation. */
    suspend fun approve(a: Approval) = update {
        copy(
            approvals = approvals.filterNot { it.accountId == a.accountId && it.packageName == a.packageName } + a,
            pending = pending + PendingRevoke(a.accountId, a.packageName)
        )
    }

    /** Removes the package's approval and queues its keys for revocation. */
    suspend fun revoke(accountId: String, pkg: String) = update {
        copy(
            approvals = approvals.filterNot { it.accountId == accountId && it.packageName == pkg },
            pending = pending + PendingRevoke(accountId, pkg)
        )
    }

    /** Queues every key of [pkg] under [accountId] for revocation; the approval stays. */
    suspend fun queueRevoke(accountId: String, pkg: String) = update {
        copy(pending = pending + PendingRevoke(accountId, pkg))
    }

    suspend fun pendingRevokes(accountId: String): Set<String> =
        state().pending.filter { it.accountId == accountId }.map { it.packageName }.toSet()

    suspend fun clearPendingRevoke(accountId: String, pkg: String) = update {
        copy(pending = pending - PendingRevoke(accountId, pkg))
    }

    /** Starts [pkg]'s cooldown, and drops the cooldowns that have ended. */
    suspend fun deny(pkg: String) {
        val now = clock()
        update { copy(deniedUntil = deniedUntil.filterValues { it > now } + (pkg to now + DENIAL_COOLDOWN_MS)) }
    }

    suspend fun isInDenialCooldown(pkg: String): Boolean {
        val until = state().deniedUntil[pkg] ?: return false
        return until > clock()
    }

    private suspend fun state(): ApprovalsState = store.data.first()

    private suspend fun update(change: ApprovalsState.() -> ApprovalsState) {
        store.updateData { it.change() }
    }

    companion object {
        const val DENIAL_COOLDOWN_MS = 30_000L
    }
}

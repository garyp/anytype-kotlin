package com.anytypeio.anytype.apibridge

import androidx.datastore.core.DataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.rules.TemporaryFolder

/**
 * A real, file-backed approvals store for one test, on a temporary file. The store runs on
 * [Dispatchers.IO], like the app's; it is closed after the test, so no instance outlives it.
 */
class ApprovalsRule(private val clock: () -> Long = System::currentTimeMillis) : TemporaryFolder() {

    private var scope: CoroutineScope? = null

    val file: File get() = File(root, APPROVALS_FILE)

    /** The store opened by [open]; opened on first use. */
    val approvals: AppApprovals
        get() = current ?: open()

    private var current: AppApprovals? = null

    /** The DataStore under [approvals]. */
    lateinit var dataStore: DataStore<ApprovalsState>
        private set

    /** Opens a new store on [file], closing the previous one first: one instance per file. */
    fun open(): AppApprovals {
        close()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        dataStore = approvalsDataStore(file, s)
        return AppApprovals(dataStore, clock).also { current = it }
    }

    /** Closes the open store and waits until DataStore has released [file]. */
    fun close() {
        val s = scope ?: return
        runBlocking { s.coroutineContext.job.cancelAndJoin() }
        scope = null
        current = null
    }

    override fun after() {
        close()
        super.after()
    }
}

/**
 * A [DataStore] held in memory. Nothing in it dispatches, so it suits tests on a virtual-time
 * [kotlinx.coroutines.test.TestScope], and a blocking call into it never waits on another thread.
 */
class InMemoryDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    override val data: Flow<T> = state
    override suspend fun updateData(transform: suspend (t: T) -> T): T =
        mutex.withLock { transform(state.value).also { state.value = it } }
}

/** [AppApprovals] for test code outside coroutines: each call blocks until it is done. */
class BlockingApprovals(val store: AppApprovals) {
    fun find(accountId: String, pkg: String) = runBlocking { store.find(accountId, pkg) }
    fun all() = runBlocking { store.all() }
    fun approve(a: Approval) = runBlocking { store.approve(a) }
    fun revoke(accountId: String, pkg: String) = runBlocking { store.revoke(accountId, pkg) }
    fun pendingRevokes(accountId: String) = runBlocking { store.pendingRevokes(accountId) }
    fun clearPendingRevoke(accountId: String, pkg: String) = runBlocking { store.clearPendingRevoke(accountId, pkg) }
    fun deny(pkg: String) = runBlocking { store.deny(pkg) }
    fun isInDenialCooldown(pkg: String) = runBlocking { store.isInDenialCooldown(pkg) }
}

package com.anytypeio.anytype.apibridge

import kotlinx.coroutines.flow.Flow

data class AccountRef(val id: String, val name: String)
data class SpaceRef(val id: String, val name: String)

data class GatewayApp(
    val appHash: String,
    val appName: String,
    val appKey: String,
    val createdAt: Long
)

interface LocalApiGateway {
    fun setListenAddr(addr: String): String?
    fun listApps(): List<GatewayApp>
    /** Mints a key and returns it. [spaceIds] null grants every space. */
    fun createApp(name: String, readWrite: Boolean, spaceIds: List<String>?): String
    fun revokeApp(appHash: String)
}

interface AccountLauncher {
    /** Null when the account is not started. */
    fun runningAccount(): AccountRef?

    /** Throws [AccountStartException] with NOT_LOGGED_IN or START_FAILED. */
    suspend fun ensureStarted(): AccountRef

    /**
     * Emits when the account stops (logout). Heart closes its local API listener then, and any
     * local app may take the port, so the bridge forgets the address. Restarting a running
     * account always goes through a stop: heart keeps the listener when the running account is
     * selected again.
     */
    val accountStops: Flow<Unit>
}

interface SpaceDirectory {
    suspend fun activeSpaces(): List<SpaceRef>
}

class AccountStartException(val code: Int, message: String) : Exception(message) {
    companion object {
        const val NOT_LOGGED_IN = 1
        const val START_FAILED = 2
    }
}

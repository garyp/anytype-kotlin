package com.anytypeio.anytype.agentbridge

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
    fun createApp(name: String, readWrite: Boolean, spaceIds: List<String>?): GatewayApp
    fun revokeApp(appHash: String)
}

interface AccountLauncher {
    /** Null when the account is not started. */
    fun runningAccount(): AccountRef?

    /** Throws [AccountStartException] with NOT_LOGGED_IN or START_FAILED. */
    suspend fun ensureStarted(): AccountRef

    /**
     * Changes on every account start and stop, whoever caused it (the bridge, login, logout,
     * account switch, a network-mode change). Heart drops its local API listener on each, so the
     * bridge never reuses a listen address from an earlier generation.
     */
    fun generation(): Long
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

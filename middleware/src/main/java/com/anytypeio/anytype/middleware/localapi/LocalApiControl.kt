package com.anytypeio.anytype.middleware.localapi

import anytype.Rpc
import anytype.model.Account

data class LocalApiApp(
    val appHash: String,
    val appName: String,
    val appKey: String,
    val createdAt: Long
)

class LocalApiException(message: String) : Exception(message)

/**
 * Controls heart's local JSON API: the listen address and the app keys that
 * authenticate against it.
 */
class LocalApiControl(private val calls: HeartCalls = GomobileHeartCalls) {

    /**
     * Binds the JSON API to [addr]. Returns the OS-confirmed bound address, or null
     * when [addr] is empty (the server is disabled).
     */
    fun setListenAddr(addr: String): String? {
        val response = Rpc.Account.ChangeJsonApiAddr.Response.ADAPTER.decode(
            calls.changeJsonApiAddr(
                Rpc.Account.ChangeJsonApiAddr.Request.ADAPTER.encode(
                    Rpc.Account.ChangeJsonApiAddr.Request(listenAddr = addr)
                )
            )
        )
        response.error?.takeIf { it.code != Rpc.Account.ChangeJsonApiAddr.Response.Error.Code.NULL }
            ?.let { throw LocalApiException("ChangeJsonApiAddr failed: ${it.code} ${it.description}") }
        if (addr.isEmpty()) return null
        val status = response.status
            ?: throw LocalApiException("ChangeJsonApiAddr returned no status for \"$addr\"")
        if (!status.success) {
            throw LocalApiException("JSON API bind failed on ${status.listenAddr}: ${status.error}")
        }
        return status.listenAddr
    }

    fun listApps(): List<LocalApiApp> {
        val response = Rpc.Account.LocalLink.ListApps.Response.ADAPTER.decode(
            calls.listApps(
                Rpc.Account.LocalLink.ListApps.Request.ADAPTER.encode(
                    Rpc.Account.LocalLink.ListApps.Request()
                )
            )
        )
        response.error?.takeIf { it.code != Rpc.Account.LocalLink.ListApps.Response.Error.Code.NULL }
            ?.let { throw LocalApiException("ListApps failed: ${it.code} ${it.description}") }
        return response.app.map {
            LocalApiApp(
                appHash = it.appHash,
                appName = it.appName,
                appKey = it.appKey,
                createdAt = it.createdAt
            )
        }
    }

    /**
     * Mints a JSON-API-scoped app key. [spaceIds] null grants every space;
     * an empty list is rejected.
     */
    fun createApp(name: String, readWrite: Boolean, spaceIds: List<String>?): LocalApiApp {
        require(spaceIds == null || spaceIds.isNotEmpty()) {
            "spaceIds must be null (all spaces) or non-empty"
        }
        val grant = Account.Auth.AppGrant(
            spaceIds = spaceIds.orEmpty(),
            allSpaces = spaceIds == null,
            perm = if (readWrite) Account.Auth.AppGrant.Perm.ReadWrite
            else Account.Auth.AppGrant.Perm.Read
        )
        val request = Rpc.Account.LocalLink.CreateApp.Request(
            app = Account.Auth.AppInfo(
                appName = name,
                scope = Account.Auth.LocalApiScope.JsonAPI,
                grant = grant
            )
        )
        val response = Rpc.Account.LocalLink.CreateApp.Response.ADAPTER.decode(
            calls.createApp(Rpc.Account.LocalLink.CreateApp.Request.ADAPTER.encode(request))
        )
        response.error?.takeIf { it.code != Rpc.Account.LocalLink.CreateApp.Response.Error.Code.NULL }
            ?.let { throw LocalApiException("CreateApp failed: ${it.code} ${it.description}") }
        // CreateApp returns only the key; resolve heart's own entry (hash, name,
        // createdAt) from the listing so every field comes from the same source.
        val listed = try {
            listApps()
        } catch (e: Exception) {
            throw LocalApiException(
                "App key was created but could not be resolved: listing apps failed (${e.message})"
            )
        }
        return listed.firstOrNull { it.appKey == response.appKey }
            ?: throw LocalApiException(
                "App key was created but could not be resolved: not found in the app list"
            )
    }

    /** Revokes an app key. An unknown hash counts as already revoked. */
    fun revokeApp(appHash: String) {
        val response = Rpc.Account.LocalLink.RevokeApp.Response.ADAPTER.decode(
            calls.revokeApp(
                Rpc.Account.LocalLink.RevokeApp.Request.ADAPTER.encode(
                    Rpc.Account.LocalLink.RevokeApp.Request(appHash = appHash)
                )
            )
        )
        val error = response.error ?: return
        when (error.code) {
            Rpc.Account.LocalLink.RevokeApp.Response.Error.Code.NULL,
            Rpc.Account.LocalLink.RevokeApp.Response.Error.Code.NOT_FOUND -> Unit
            else -> throw LocalApiException("RevokeApp failed: ${error.code} ${error.description}")
        }
    }
}

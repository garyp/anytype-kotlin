package com.anytypeio.anytype.apibridge

import kotlinx.serialization.Serializable

// These types are also the stored schema of [ApprovalsState]: renaming a class, a property or an
// Access constant changes the file format, and needs a bump of [ApprovalsState.SCHEMA_VERSION].

@Serializable
enum class Access { READ_WRITE, READ_ONLY }

/** Deserialization runs the init block too, so a stored grant that breaks the rule is rejected. */
@Serializable
data class Grant(val allSpaces: Boolean, val spaceIds: List<String>) {
    init {
        require(allSpaces || spaceIds.isNotEmpty()) { "Grant needs all spaces or at least one space" }
    }

    companion object {
        val ALL = Grant(allSpaces = true, spaceIds = emptyList())
    }
}

@Serializable
data class Approval(
    val accountId: String,
    val packageName: String,
    /** Signing certificate SHA-256, lowercase hex. */
    val certSha256: String,
    val access: Access,
    val grant: Grant,
    val approvedAt: Long
)

@Serializable
data class PendingRevoke(val accountId: String, val packageName: String)

object KeyNames {
    fun name(pkg: String, certSha256: String) = "app:$pkg:${certSha256.take(8)}"
    fun prefix(pkg: String) = "app:$pkg:"
}

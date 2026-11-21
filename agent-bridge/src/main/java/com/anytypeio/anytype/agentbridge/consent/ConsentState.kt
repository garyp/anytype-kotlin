package com.anytypeio.anytype.agentbridge.consent

import com.anytypeio.anytype.agentbridge.Access
import com.anytypeio.anytype.agentbridge.Grant
import com.anytypeio.anytype.agentbridge.SpaceRef

/** What the consent screen shows and what the user has chosen so far. Immutable. */
data class ConsentState(
    val loading: Boolean = true,
    val accountName: String = "",
    val appLabel: String = "",
    val packageName: String = "",
    val certShort: String = "",
    val spaces: List<SpaceRef> = emptyList(),
    val allSpaces: Boolean = true,
    val selected: Set<String> = emptySet(),
    val access: Access = Access.READ_WRITE,
    val error: String? = null
) {
    /** A narrowed grant must name at least one space: [Grant] refuses an empty one. */
    val canAllow: Boolean
        get() = !loading && error == null && (allSpaces || selected.isNotEmpty())

    /** Ids that are not among [spaces] are ignored. */
    fun toggleSpace(id: String): ConsentState {
        if (spaces.none { it.id == id }) return this
        return copy(selected = if (id in selected) selected - id else selected + id)
    }

    fun setAllSpaces(b: Boolean) = copy(allSpaces = b)

    fun setAccess(a: Access) = copy(access = a)

    /** Throws when [canAllow] is false for the selection, which callers check first. */
    fun grant(): Grant =
        if (allSpaces) Grant.ALL
        else Grant(allSpaces = false, spaceIds = spaces.map { it.id }.filter { it in selected })

    fun title() = "Allow $appLabel to access Anytype?"

    fun body(): String {
        val verbs = if (access == Access.READ_WRITE) "read and change" else "read"
        val where = when {
            allSpaces -> "all your spaces"
            selected.size == 1 -> "1 space"
            else -> "${selected.size} spaces"
        }
        val of = if (accountName.isBlank()) "" else " of $accountName"
        return "$appLabel will be able to $verbs all objects in $where$of while it is connected."
    }

    companion object {
        const val NARROWED_NOTE =
            "With specific spaces, the app can use the v2 API and MCP only, and cannot create spaces."

        /** First 16 hex chars, in groups of 4 separated by spaces. */
        fun shortCert(hex: String): String = hex.take(16).chunked(4).joinToString(" ")
    }
}

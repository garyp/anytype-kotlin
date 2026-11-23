package com.anytypeio.anytype.apibridge.consent

import android.os.Bundle
import com.anytypeio.anytype.apibridge.Access
import com.anytypeio.anytype.apibridge.Grant
import com.anytypeio.anytype.apibridge.SpaceRef

/**
 * What the consent screen shows once it has loaded, and what the user has chosen so far.
 * Immutable. It holds no text: the screen picks the strings from [reach] and [limit].
 */
data class ConsentState(
    val appLabel: String,
    val packageName: String,
    val certShort: String,
    /** The running account's name; blank when it could not be read. */
    val accountName: String,
    val spaces: List<SpaceRef>,
    val allSpaces: Boolean = true,
    val selected: Set<String> = emptySet(),
    val access: Access = Access.READ_WRITE
) {
    /** What the approval would cover, as the body sentence describes it. */
    sealed interface Reach {
        data object AllSpaces : Reach
        data class Spaces(val count: Int) : Reach
        /** Specific spaces, none ticked yet: Allow is disabled, and the body asks for a choice. */
        data object NoSpaceChosen : Reach
    }

    /**
     * What a limited approval rules out. Heart serves `/v1` only to a key with access to all
     * spaces and read and change; any narrower key is `/v2` and MCP only.
     */
    enum class Limit { SPECIFIC_SPACES, READ_ONLY }

    /** A narrowed grant must name at least one space: [Grant] refuses an empty one. */
    val canAllow: Boolean
        get() = allSpaces || selected.isNotEmpty()

    val reach: Reach
        get() = when {
            allSpaces -> Reach.AllSpaces
            selected.isEmpty() -> Reach.NoSpaceChosen
            else -> Reach.Spaces(selected.size)
        }

    /** Null for full access. */
    val limit: Limit?
        get() = when {
            !allSpaces -> Limit.SPECIFIC_SPACES
            access == Access.READ_ONLY -> Limit.READ_ONLY
            else -> null
        }

    val choices: ConsentChoices
        get() = ConsentChoices(allSpaces, spaces.map { it.id }.filter { it in selected }, access)

    /** Ids that are not among [spaces] are ignored. */
    fun toggleSpace(id: String): ConsentState {
        if (spaces.none { it.id == id }) return this
        return copy(selected = if (id in selected) selected - id else selected + id)
    }

    fun setAllSpaces(b: Boolean) = copy(allSpaces = b)

    fun setAccess(a: Access) = copy(access = a)

    /** Applies choices from before a recreation; spaces that are no longer listed are dropped. */
    fun withChoices(c: ConsentChoices?): ConsentState {
        if (c == null) return this
        return c.selected.fold(copy(allSpaces = c.allSpaces, selected = emptySet(), access = c.access)) { s, id ->
            s.toggleSpace(id)
        }
    }

    /** Throws when [canAllow] is false for the selection, which callers check first. */
    fun grant(): Grant =
        if (allSpaces) Grant.ALL
        else Grant(allSpaces = false, spaceIds = spaces.map { it.id }.filter { it in selected })

    companion object {
        /** First 16 hex chars, in groups of 4 separated by spaces. */
        fun shortCert(hex: String): String = hex.take(16).chunked(4).joinToString(" ")
    }
}

/** The user's choices on the consent screen, kept in the saved instance state across a recreation. */
data class ConsentChoices(
    val allSpaces: Boolean,
    val selected: List<String>,
    val access: Access
) {
    fun writeTo(outState: Bundle) {
        outState.putBundle(KEY, Bundle().apply {
            putBoolean(KEY_ALL_SPACES, allSpaces)
            putStringArrayList(KEY_SELECTED, ArrayList(selected))
            putString(KEY_ACCESS, access.name)
        })
    }

    companion object {
        private const val KEY = "consent_choices"
        private const val KEY_ALL_SPACES = "all_spaces"
        private const val KEY_SELECTED = "selected"
        private const val KEY_ACCESS = "access"

        /** Null when [savedInstanceState] holds no choices. */
        fun readFrom(savedInstanceState: Bundle?): ConsentChoices? {
            val b = savedInstanceState?.getBundle(KEY) ?: return null
            return ConsentChoices(
                allSpaces = b.getBoolean(KEY_ALL_SPACES, true),
                selected = b.getStringArrayList(KEY_SELECTED).orEmpty(),
                access = b.getString(KEY_ACCESS)?.let { n -> Access.entries.find { it.name == n } }
                    ?: Access.READ_WRITE
            )
        }
    }
}

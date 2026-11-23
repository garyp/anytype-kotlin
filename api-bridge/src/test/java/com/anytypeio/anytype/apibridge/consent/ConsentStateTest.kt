package com.anytypeio.anytype.apibridge.consent

import com.anytypeio.anytype.apibridge.Access
import com.anytypeio.anytype.apibridge.Grant
import com.anytypeio.anytype.apibridge.SpaceRef
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentStateTest {

    private val loaded = ConsentState(
        loading = false,
        accountName = "Alice",
        appLabel = "Client",
        packageName = "com.client",
        certShort = "aabb ccdd eeff 0011",
        spaces = listOf(SpaceRef("s1", "Work"), SpaceRef("s2", "Home"), SpaceRef("s3", "Club"))
    )

    @Test
    fun defaultsToAllSpacesReadWrite() {
        assertTrue(loaded.allSpaces)
        assertEquals(Access.READ_WRITE, loaded.access)
        assertTrue(loaded.selected.isEmpty())
        assertTrue(loaded.canAllow)
        assertEquals(Grant.ALL, loaded.grant())
    }

    @Test
    fun allowDisabledWithEmptySelection() {
        val s = loaded.setAllSpaces(false)
        assertFalse(s.canAllow)
        // Selecting then unselecting leaves it disabled again.
        val again = s.toggleSpace("s1").toggleSpace("s1")
        assertFalse(again.canAllow)
    }

    @Test
    fun allowDisabledWhileLoading() {
        assertFalse(loaded.copy(loading = true).canAllow)
        assertFalse(ConsentState().canAllow)
    }

    @Test
    fun allowDisabledOnError() {
        assertFalse(loaded.copy(error = "Sign in to Anytype first").canAllow)
    }

    @Test
    fun grantFromSelection() {
        val s = loaded.setAllSpaces(false).toggleSpace("s3").toggleSpace("s1").setAccess(Access.READ_ONLY)
        assertTrue(s.canAllow)
        assertEquals(Access.READ_ONLY, s.access)
        // In the order the spaces are listed, not the order they were tapped.
        assertEquals(Grant(allSpaces = false, spaceIds = listOf("s1", "s3")), s.grant())
        // Back to all spaces: the selection no longer narrows the grant.
        assertEquals(Grant.ALL, s.setAllSpaces(true).grant())
    }

    @Test
    fun toggleIgnoresUnknownSpace() {
        val s = loaded.setAllSpaces(false).toggleSpace("nope")
        assertTrue(s.selected.isEmpty())
        assertFalse(s.canAllow)
    }

    @Test
    fun certShortFormatting() {
        val hex = "0123456789abcdef" + "ff".repeat(24)
        assertEquals("0123 4567 89ab cdef", ConsentState.shortCert(hex))
        assertEquals("abc", ConsentState.shortCert("abc"))
    }

    @Test
    fun copy() {
        assertEquals("Allow Client to access Anytype?", loaded.title())
        assertEquals(
            "Client will be able to read and change all objects in all your spaces of Alice while it is connected.",
            loaded.body()
        )
        val narrow = loaded.setAllSpaces(false).toggleSpace("s1").toggleSpace("s2").setAccess(Access.READ_ONLY)
        assertEquals(
            "Client will be able to read all objects in 2 spaces of Alice while it is connected.",
            narrow.body()
        )
        assertEquals(
            "Client will be able to read all objects in 1 space of Alice while it is connected.",
            narrow.toggleSpace("s2").body()
        )
        // No space ticked yet: ask for a choice instead of describing access to "0 spaces".
        assertEquals(
            "Choose the spaces of Alice that Client can access.",
            narrow.toggleSpace("s1").toggleSpace("s2").body()
        )
        assertEquals(
            "Choose the spaces that Client can access.",
            narrow.copy(accountName = "").toggleSpace("s1").toggleSpace("s2").body()
        )
        // Any limited approval loses /v1; only full access (all spaces, read and change) keeps it.
        assertNull(loaded.limitNote())
        assertEquals(
            "With specific spaces, the app can use the v2 API and MCP only, and cannot create spaces.",
            loaded.setAllSpaces(false).limitNote()
        )
        assertEquals(
            "With read-only access, the app can use the v2 API and MCP only.",
            loaded.setAccess(Access.READ_ONLY).limitNote()
        )
        assertEquals(
            "With specific spaces, the app can use the v2 API and MCP only, and cannot create spaces.",
            loaded.setAllSpaces(false).setAccess(Access.READ_ONLY).limitNote()
        )
    }

    @Test
    fun bodyOmitsBlankAccountName() {
        assertEquals(
            "Client will be able to read and change all objects in all your spaces while it is connected.",
            loaded.copy(accountName = "").body()
        )
        assertEquals(
            "Client will be able to read and change all objects in all your spaces while it is connected.",
            loaded.copy(accountName = "  ").body()
        )
    }
}

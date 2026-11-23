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
        appLabel = "Client",
        packageName = "com.client",
        certShort = "aabb ccdd eeff 0011",
        accountName = "Alice",
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
    fun reach() {
        assertEquals(ConsentState.Reach.AllSpaces, loaded.reach)
        val narrow = loaded.setAllSpaces(false).toggleSpace("s1").toggleSpace("s2")
        assertEquals(ConsentState.Reach.Spaces(2), narrow.reach)
        assertEquals(ConsentState.Reach.Spaces(1), narrow.toggleSpace("s2").reach)
        // No space ticked yet: the body asks for a choice instead of describing "0 spaces".
        assertEquals(ConsentState.Reach.NoSpaceChosen, narrow.toggleSpace("s1").toggleSpace("s2").reach)
    }

    @Test
    fun limit() {
        // Any limited approval loses /v1; only full access (all spaces, read and change) keeps it.
        assertNull(loaded.limit)
        assertEquals(ConsentState.Limit.SPECIFIC_SPACES, loaded.setAllSpaces(false).limit)
        assertEquals(ConsentState.Limit.READ_ONLY, loaded.setAccess(Access.READ_ONLY).limit)
        assertEquals(ConsentState.Limit.SPECIFIC_SPACES, loaded.setAllSpaces(false).setAccess(Access.READ_ONLY).limit)
    }

    @Test
    fun choicesRoundTrip() {
        val narrow = loaded.setAllSpaces(false).toggleSpace("s3").toggleSpace("s1").setAccess(Access.READ_ONLY)
        assertEquals(ConsentChoices(false, listOf("s1", "s3"), Access.READ_ONLY), narrow.choices)
        assertEquals(narrow, loaded.withChoices(narrow.choices))
        assertEquals(loaded, loaded.withChoices(null))
    }

    @Test
    fun choicesDropSpacesNoLongerListed() {
        val restored = loaded.withChoices(ConsentChoices(false, listOf("gone", "s2"), Access.READ_WRITE))
        assertFalse(restored.allSpaces)
        assertEquals(setOf("s2"), restored.selected)
    }
}

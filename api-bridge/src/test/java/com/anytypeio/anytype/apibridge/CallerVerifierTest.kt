package com.anytypeio.anytype.apibridge

import android.content.pm.PackageManager
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallerVerifierTest {

    private val approvals = AppApprovals(InMemoryDataStore(ApprovalsState()))

    private suspend fun approve(pkg: String, cert: String) = approvals.approve(
        Approval("A", pkg, cert, Access.READ_WRITE, Grant.ALL, 1L)
    )

    private val aabb = byteArrayOf(0xaa.toByte(), 0xbb.toByte())

    @Test
    fun matchingCertIsApproved() = runTest {
        approve("com.x", "aabb")
        val v = CallerVerifier(pmWith(10, "com.x"), approvals) { _, b -> b.contentEquals(aabb) }
        assertEquals(listOf("com.x"), v.verifiedApprovals(10).map { it.packageName })
    }

    @Test
    fun changedCertIsNotApproved() = runTest {
        approve("com.x", "aabb")
        val v = CallerVerifier(pmWith(10, "com.x"), approvals) { _, _ -> false }
        assertTrue(v.verifiedApprovals(10).isEmpty())
    }

    @Test
    fun sharedUidPicksApprovedPackage() = runTest {
        approve("com.b", "aabb")
        val v = CallerVerifier(pmWith(10, "com.a", "com.b"), approvals) { _, _ -> true }
        assertEquals(listOf("com.b"), v.verifiedApprovals(10).map { it.packageName })
    }

    @Test
    fun unknownUidHasNoApprovals() = runTest {
        approve("com.x", "aabb")
        val v = CallerVerifier(pmWith(10, "com.x"), approvals) { _, _ -> true }
        assertTrue(v.verifiedApprovals(99).isEmpty())
    }

    @Test
    fun otherPackageHasNoApprovals() = runTest {
        approve("com.x", "aabb")
        val v = CallerVerifier(pmWith(10, "com.y"), approvals) { _, _ -> true }
        assertTrue(v.verifiedApprovals(10).isEmpty())
    }

    @Test
    fun approvalsUnderEveryAccountAreReturned() = runTest {
        approvals.approve(Approval("A", "com.x", "aabb", Access.READ_WRITE, Grant.ALL, 1L))
        approvals.approve(Approval("B", "com.x", "aabb", Access.READ_ONLY, Grant.ALL, 1L))
        val v = CallerVerifier(pmWith(10, "com.x"), approvals) { _, b -> b.contentEquals(aabb) }
        assertEquals(setOf("A", "B"), v.verifiedApprovals(10).map { it.accountId }.toSet())
    }

    private fun pmWith(uid: Int, vararg pkgs: String): PackageManager {
        val pm = mock<PackageManager>()
        org.mockito.kotlin.whenever(pm.getPackagesForUid(uid)).thenReturn(arrayOf(*pkgs))
        return pm
    }
}

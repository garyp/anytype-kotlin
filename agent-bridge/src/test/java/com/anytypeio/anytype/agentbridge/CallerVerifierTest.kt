package com.anytypeio.anytype.agentbridge

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallerVerifierTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var approvals: AgentApprovals

    @Before
    fun setUp() {
        context.getSharedPreferences("agent_bridge", Context.MODE_PRIVATE).edit().clear().commit()
        approvals = AgentApprovals(context)
    }

    private fun approve(pkg: String, cert: String) = approvals.approve(
        Approval("A", pkg, cert, Access.READ_WRITE, Grant.ALL, 1L)
    )

    @Test
    fun matchingCertIsApproved() {
        approve("com.x", "aabb")
        val v = CallerVerifier(pmWith(10, "com.x"), approvals) { _, b -> b.contentEquals(byteArrayOf(0xaa.toByte(), 0xbb.toByte())) }
        assertEquals("com.x", v.approvedCaller(10, "A")?.packageName)
    }

    @Test
    fun changedCertIsNotApproved() {
        approve("com.x", "aabb")
        val v = CallerVerifier(pmWith(10, "com.x"), approvals) { _, _ -> false }
        assertNull(v.approvedCaller(10, "A"))
    }

    @Test
    fun sharedUidPicksApprovedPackage() {
        approve("com.b", "aabb")
        val v = CallerVerifier(pmWith(10, "com.a", "com.b"), approvals) { _, _ -> true }
        assertEquals("com.b", v.approvedCaller(10, "A")?.packageName)
    }

    @Test
    fun unknownUidIsNull() {
        approve("com.x", "aabb")
        val v = CallerVerifier(pmWith(10, "com.x"), approvals) { _, _ -> true }
        assertNull(v.approvedCaller(99, "A"))
    }

    @Test
    fun approvedAnywhereMatchesAnyAccountWithVerifiedCert() {
        approvals.approve(Approval("B", "com.x", "aabb", Access.READ_ONLY, Grant.ALL, 1L))
        val ok = CallerVerifier(pmWith(10, "com.x"), approvals) { _, b -> b.contentEquals(byteArrayOf(0xaa.toByte(), 0xbb.toByte())) }
        kotlin.test.assertTrue(ok.approvedAnywhere(10))
        val wrongCert = CallerVerifier(pmWith(10, "com.x"), approvals) { _, _ -> false }
        kotlin.test.assertFalse(wrongCert.approvedAnywhere(10))
        val otherPkg = CallerVerifier(pmWith(10, "com.y"), approvals) { _, _ -> true }
        kotlin.test.assertFalse(otherPkg.approvedAnywhere(10))
    }

    private fun pmWith(uid: Int, vararg pkgs: String): PackageManager {
        val pm = mock<PackageManager>()
        org.mockito.kotlin.whenever(pm.getPackagesForUid(uid)).thenReturn(arrayOf(*pkgs))
        return pm
    }
}

package com.anytypeio.anytype.agentbridge

import android.content.pm.PackageManager
import androidx.annotation.RequiresApi
import android.content.pm.PackageManager.CERT_INPUT_SHA256

/**
 * Decides which calling app (by Binder UID) is approved: account + package + signing cert SHA-256.
 *
 * [certCheck] is a test seam; the default asks the platform whether the UID is signed
 * with the given SHA-256 certificate digest.
 */
@RequiresApi(35)
class CallerVerifier(
    private val pm: PackageManager,
    private val approvals: AgentApprovals,
    private val certCheck: (uid: Int, certSha256: ByteArray) -> Boolean = { uid, cert ->
        pm.hasSigningCertificate(uid, cert, CERT_INPUT_SHA256)
    }
) {

    fun approvedCaller(uid: Int, accountId: String): Approval? {
        val packages = pm.getPackagesForUid(uid) ?: return null
        for (pkg in packages) {
            val approval = approvals.find(accountId, pkg) ?: continue
            val digest = fromHex(approval.certSha256) ?: continue
            if (certCheck(uid, digest)) return approval
        }
        return null
    }

    /** Lowercase hex SHA-256 of the app's signing certificate; null for unknown package or multiple signers. */
    fun currentCertSha256(pkg: String): String? {
        val info = try {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val signing = info.signingInfo ?: return null
        if (signing.hasMultipleSigners()) return null
        val sig = signing.signingCertificateHistory?.lastOrNull() ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun fromHex(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}

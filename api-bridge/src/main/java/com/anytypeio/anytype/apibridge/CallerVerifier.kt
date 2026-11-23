package com.anytypeio.anytype.apibridge

import android.content.pm.PackageManager
import android.content.pm.PackageManager.CERT_INPUT_SHA256
import androidx.annotation.RequiresApi
import java.security.MessageDigest

/** Which approvals a calling app (by Binder UID) holds. A test seam over [CallerVerifier]. */
fun interface CallerApprovals {
    /**
     * The UID's approvals, under any account, whose pinned signing cert still verifies. Makes
     * blocking package manager calls: call it off the main thread.
     */
    suspend fun verifiedApprovals(uid: Int): List<Approval>
}

/**
 * Matches a calling UID against the stored approvals: package + signing cert SHA-256, per account.
 *
 * [certCheck] is a test seam; the default asks the platform whether the UID is signed
 * with the given SHA-256 certificate digest.
 */
@RequiresApi(35)
class CallerVerifier(
    private val pm: PackageManager,
    private val approvals: AppApprovals,
    private val certCheck: (uid: Int, certSha256: ByteArray) -> Boolean = { uid, cert ->
        pm.hasSigningCertificate(uid, cert, CERT_INPUT_SHA256)
    }
) : CallerApprovals {

    override suspend fun verifiedApprovals(uid: Int): List<Approval> {
        val packages = pm.getPackagesForUid(uid)?.toSet() ?: return emptyList()
        return approvals.all().filter { a ->
            a.packageName in packages && fromHex(a.certSha256)?.let { certCheck(uid, it) } == true
        }
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

/**
 * Lowercase hex SHA-256 of [pkg]'s signing certificate; null for an unknown package or one with
 * multiple signers.
 */
@RequiresApi(35)
fun currentCertSha256(pm: PackageManager, pkg: String): String? {
    val info = try {
        pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (e: PackageManager.NameNotFoundException) {
        return null
    }
    val signing = info.signingInfo ?: return null
    if (signing.hasMultipleSigners()) return null
    val sig = signing.signingCertificateHistory?.lastOrNull() ?: return null
    val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

package io.anytype.agentbridge.client

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import io.anytype.agentbridge.AgentEndpoint
import io.anytype.agentbridge.IAgentBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Endpoint(val baseUrl: String, val apiKey: String)

/** Test seam over [PackageManager.hasSigningCertificate]. */
internal fun interface SigningCheck {
    fun signedBy(context: Context, pkg: String, sha256: ByteArray): Boolean
}

/**
 * Client for the Anytype agent bridge service. Binds to the bridge service of [targetPackage]
 * and asks it for the local API endpoint (base URL and bearer key).
 *
 * Any installed app can expose the bind action. Pass [expectedCertSha256], the SHA-256 digest of
 * the signing certificate of the Anytype build you trust (64 hex digits; colons and spaces are
 * ignored), and the bridge binds only when [targetPackage] is signed with it. Otherwise
 * [connect] throws [BridgeException.Untrusted], and nothing reaches the impostor. The check
 * needs API 28+; below that a pinned bridge never binds.
 *
 * An instance has a single owner and is not thread-safe beyond [connect], which a mutex
 * serializes.
 */
class AnytypeBridge(
    context: Context,
    val targetPackage: String = DEFAULT_PACKAGE,
    expectedCertSha256: String? = null
) {

    private val context: Context = context.applicationContext ?: context
    private val expectedCert: ByteArray? = expectedCertSha256?.let(::parseSha256)
    private val mutex = Mutex()
    private val lock = Any()

    // The current binding; a callback from an older one is ignored. Guarded by lock.
    private var connection: Conn? = null

    /** A binding to the service; [service] completes on onServiceConnected or fails. */
    private inner class Conn : ServiceConnection {
        val service = CompletableDeferred<IAgentBridge>()

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bridge = IAgentBridge.Stub.asInterface(binder)
            if (bridge != null) {
                service.complete(bridge)
            } else {
                release(this, BridgeException.Failed(IAgentBridge.INTERNAL, "no binder"))
            }
        }

        // The service process died: rebind on the next connect rather than wait for a restart.
        override fun onServiceDisconnected(name: ComponentName?) =
            release(this, BridgeException.Failed(IAgentBridge.INTERNAL, "bridge disconnected"))

        override fun onBindingDied(name: ComponentName?) =
            release(this, BridgeException.Failed(IAgentBridge.INTERNAL, "binding died"))

        override fun onNullBinding(name: ComponentName?) =
            release(this, BridgeException.NotInstalled(targetPackage))
    }

    /**
     * Binds to the bridge service if needed, then asks it for the endpoint; the binding is kept.
     * Throws [BridgeException].
     */
    suspend fun connect(): Endpoint = mutex.withLock {
        val conn = synchronized(lock) { connection } ?: bind()
        val bridge = try {
            conn.service.await()
        } catch (e: Throwable) {
            release(conn, null)
            throw e
        }
        val reply: AgentEndpoint? = try {
            withContext(Dispatchers.IO) { bridge.connect() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            release(conn, null)
            throw BridgeException.Failed(IAgentBridge.INTERNAL, "bridge disconnected")
        } catch (e: RuntimeException) {
            // Any other failure of the call (e.g. a bad parcel): drop the binding, rebind next time.
            release(conn, null)
            throw BridgeException.Failed(IAgentBridge.INTERNAL, "bridge call failed: ${e.javaClass.simpleName}")
        }
        toEndpoint(reply)
    }

    private fun bind(): Conn {
        // Never bind to, and so never hand a request to, a package signed by someone else.
        val pin = expectedCert
        if (pin != null && !signingCheck.signedBy(context, targetPackage, pin)) {
            throw BridgeException.Untrusted(targetPackage)
        }
        val conn = Conn()
        synchronized(lock) { connection = conn }
        val ok = try {
            doBind(Intent(BIND_ACTION).setPackage(targetPackage), conn)
        } catch (e: Exception) {
            // SecurityException or any other refusal: no binding exists, so leave none behind.
            false
        }
        if (!ok) {
            release(conn, null)
            throw BridgeException.NotInstalled(targetPackage)
        }
        return conn
    }

    private fun doBind(intent: Intent, conn: ServiceConnection): Boolean {
        val executor = ContextCompat.getMainExecutor(context)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> context.bindService(
                intent,
                Context.BindServiceFlags.of(
                    (BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES).toLong()
                ),
                executor,
                conn
            )
            Build.VERSION.SDK_INT >= 29 -> context.bindService(
                intent,
                BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES,
                executor,
                conn
            )
            else -> context.bindService(intent, conn, BIND_AUTO_CREATE)
        }
    }

    /**
     * Unbinds [conn] if it is still the current binding, and fails a [connect] that waits for it
     * with [error]. The next [connect] binds afresh.
     */
    private fun release(conn: Conn?, error: BridgeException?) {
        if (conn == null) return
        synchronized(lock) {
            if (connection !== conn) return
            connection = null
        }
        conn.service.completeExceptionally(error ?: BridgeException.NotInstalled(targetPackage))
        try {
            context.unbindService(conn)
        } catch (_: Exception) {
        }
    }

    private fun toEndpoint(reply: AgentEndpoint?): Endpoint {
        val code = reply?.code ?: IAgentBridge.INTERNAL
        if (code == IAgentBridge.APPROVAL_REQUIRED) throw BridgeException.ApprovalRequired()
        if (code != IAgentBridge.OK) throw BridgeException.Failed(code, messageFor(code))
        val baseUrl = reply?.baseUrl
        val apiKey = reply?.apiKey
        if (baseUrl == null || apiKey == null) {
            throw BridgeException.Failed(IAgentBridge.INTERNAL, "malformed reply")
        }
        return Endpoint(baseUrl, apiKey)
    }

    private fun messageFor(code: Int): String = when (code) {
        IAgentBridge.NOT_LOGGED_IN -> "not logged in"
        IAgentBridge.START_FAILED -> "local API failed to start"
        IAgentBridge.API_FAILED -> "local API request failed"
        IAgentBridge.INTERNAL -> "internal error"
        else -> "error $code"
    }

    /** Unbinds from the service and fails a [connect] that waits for the binding. */
    fun close() = release(synchronized(lock) { connection }, null)

    /** Call when the endpoint was rejected (e.g. key rotated): the next [connect] binds afresh. */
    fun markStale() = release(
        synchronized(lock) { connection },
        BridgeException.Failed(IAgentBridge.INTERNAL, "endpoint marked stale")
    )

    /** Intent to launch the approval screen of the Anytype app. */
    fun approvalIntent(): Intent = Intent(APPROVE_ACTION).setPackage(targetPackage)

    /**
     * Options for starting the approval activity: shares the caller identity with the Anytype app
     * on API 34+, so it can see which app asks for approval.
     */
    fun approvalOptions(): ActivityOptionsCompat =
        if (Build.VERSION.SDK_INT >= 34) {
            val bundle = Api34.shareIdentityBundle()
            object : ActivityOptionsCompat() {
                override fun toBundle(): Bundle = bundle
            }
        } else {
            ActivityOptionsCompat.makeBasic()
        }

    @RequiresApi(34)
    private object Api34 {
        fun shareIdentityBundle(): Bundle =
            ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
    }

    companion object {
        const val DEFAULT_PACKAGE = "io.anytype.app"
        const val BIND_ACTION = "io.anytype.agentbridge.BIND"
        const val APPROVE_ACTION = "io.anytype.agentbridge.APPROVE"

        /**
         * Packages that expose the bridge bind action and, when [expectedCertSha256] is not null,
         * are signed with that certificate (SHA-256, hex; colons and spaces are ignored). Any app
         * can expose the action: pass the certificate.
         */
        fun findInstalled(context: Context, expectedCertSha256: String? = null): List<String> {
            val all = context.packageManager
                .queryIntentServices(Intent(BIND_ACTION), PackageManager.GET_RESOLVED_FILTER)
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
            val pin = expectedCertSha256?.let(::parseSha256) ?: return all
            return all.filter { signingCheck.signedBy(context, it, pin) }
        }

        @VisibleForTesting
        @Volatile
        internal var signingCheck: SigningCheck = SigningCheck { context, pkg, sha256 ->
            if (Build.VERSION.SDK_INT < 28) {
                false
            } else {
                try {
                    context.packageManager.hasSigningCertificate(pkg, sha256, PackageManager.CERT_INPUT_SHA256)
                } catch (e: Exception) {
                    false
                }
            }
        }

        /** The platform [signingCheck], restored by tests. */
        internal val platformSigningCheck: SigningCheck = signingCheck

        /** Parses a SHA-256 digest written as 64 hex digits; colons and whitespace are ignored. */
        internal fun parseSha256(hex: String): ByteArray {
            val clean = hex.filterNot { it == ':' || it.isWhitespace() }
            require(clean.length == 64 && clean.all { Character.digit(it, 16) >= 0 }) {
                "expectedCertSha256 must be a SHA-256 digest: 64 hex digits"
            }
            return ByteArray(32) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        }
    }
}

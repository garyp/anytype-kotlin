package io.anytype.apibridge.client

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import io.anytype.apibridge.ApiEndpoint
import io.anytype.apibridge.IApiBridge
import io.anytype.apibridge.IConnectCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The local API endpoint. [toString] leaves [apiKey] out, so logging an endpoint leaks no key. */
data class Endpoint(val baseUrl: String, val apiKey: String) {
    override fun toString(): String = "Endpoint(baseUrl=$baseUrl, apiKey=<redacted>)"
}

/** Test seam over [PackageManager.hasSigningCertificate]. */
internal fun interface SigningCheck {
    fun signedBy(context: Context, pkg: String, sha256: ByteArray): Boolean
}

/**
 * Client for the Anytype API bridge service. Binds to the bridge service of [targetPackage]
 * and asks it for the local API endpoint (base URL and bearer key).
 *
 * Any installed app can expose the bind action. Pass [expectedCertSha256], the SHA-256 digest of
 * the signing certificate of the Anytype build you trust (64 hex digits; colons and spaces are
 * ignored), and the bridge binds only when [targetPackage] is signed with it. Otherwise
 * [connect] throws [BridgeException.Untrusted], and nothing reaches the impostor. The check
 * needs API 28+; below that a pinned bridge never binds.
 *
 * Threading: [connect] and [close] may be called from any thread. All state lives on the main
 * thread, where the bind callbacks arrive too, so the class needs no lock: [connect] switches to
 * the main thread for every state access (at once when already there), and [close] runs there.
 * Concurrent [connect] calls share one binding. [IApiBridge.connect] is oneway and never blocks;
 * the answer arrives through a callback within [REPLY_TIMEOUT_MS], or [connect] gives up. An
 * Anytype whose bridge speaks an incompatible protocol shows up as that timeout.
 */
class AnytypeBridge(
    context: Context,
    val targetPackage: String = DEFAULT_PACKAGE,
    expectedCertSha256: String? = null
) {

    private val context: Context = context.applicationContext ?: context
    private val expectedCert: ByteArray? = expectedCertSha256?.let(::parseSha256)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val main = mainHandler.asCoroutineDispatcher().immediate

    /** How long [connect] waits for the answer of the service; see [REPLY_TIMEOUT_MS]. */
    @VisibleForTesting
    internal var replyTimeoutMs: Long = REPLY_TIMEOUT_MS

    // The current binding; a callback from an older one is ignored. Main thread only.
    private var connection: Conn? = null

    /**
     * A binding to the service. [service] completes on onServiceConnected or fails; [lost]
     * completes when the binding is released, which fails every answer still awaited on it.
     * Main thread only, like every callback of the connection.
     */
    private inner class Conn : ServiceConnection {
        val service = CompletableDeferred<IApiBridge>()
        val lost = Job()

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bridge = IApiBridge.Stub.asInterface(binder)
            if (bridge != null) {
                service.complete(bridge)
            } else {
                release(this, BridgeException.Failed(IApiBridge.INTERNAL, "no binder"))
            }
        }

        // The service process died: rebind on the next connect rather than wait for a restart.
        override fun onServiceDisconnected(name: ComponentName?) =
            release(this, BridgeException.Failed(IApiBridge.INTERNAL, "bridge disconnected"))

        override fun onBindingDied(name: ComponentName?) =
            release(this, BridgeException.Failed(IApiBridge.INTERNAL, "binding died"))

        override fun onNullBinding(name: ComponentName?) =
            release(this, BridgeException.NotInstalled(targetPackage))
    }

    /**
     * Binds to the bridge service if needed, then asks it for the endpoint; the binding is kept.
     * Throws [BridgeException].
     *
     * Waits at most [REPLY_TIMEOUT_MS] (50 s) for the answer, then throws
     * [BridgeException.Failed] with [IApiBridge.INTERNAL]; the connect goes on inside Anytype,
     * and a later call can pick up its result. Cancelling the coroutine stops the wait at once
     * and keeps the binding; [close] drops it.
     */
    suspend fun connect(): Endpoint = withContext(main) {
        val conn = connection ?: bind()
        // Fails only once release() has run, which already dropped the binding.
        val bridge = conn.service.await()
        toEndpoint(requestEndpoint(conn, bridge))
    }

    /** Sends the oneway connect and waits for its callback, for at most [replyTimeoutMs]. */
    private suspend fun requestEndpoint(conn: Conn, bridge: IApiBridge): ApiEndpoint {
        val reply = CompletableDeferred<ApiEndpoint>()
        // The service process died or the binding was closed: no answer will come.
        val onLost = conn.lost.invokeOnCompletion {
            reply.completeExceptionally(BridgeException.Failed(IApiBridge.INTERNAL, "bridge disconnected"))
        }
        try {
            binderCall(conn) { bridge.connect(ReplyCallback(reply)) }
            return withTimeoutOrNull(replyTimeoutMs) { reply.await() }
                ?: throw BridgeException.Failed(
                    IApiBridge.INTERNAL,
                    "no reply within ${replyTimeoutMs / 1000} s: Anytype busy, or its bridge too old or incompatible"
                )
        } finally {
            onLost.dispose()
        }
    }

    /**
     * Completes [reply] with the first answer. Called on a binder thread of this process;
     * [CompletableDeferred] ignores a second answer, and the waiting coroutine resumes on main.
     */
    private class ReplyCallback(private val reply: CompletableDeferred<ApiEndpoint>) : IConnectCallback.Stub() {
        override fun onResult(endpoint: ApiEndpoint?) {
            if (endpoint != null) {
                reply.complete(endpoint)
            } else {
                reply.completeExceptionally(BridgeException.Failed(IApiBridge.INTERNAL, "malformed reply"))
            }
        }
    }

    /** Runs a binder call on [conn]; a dead or failing call drops the binding and fails. */
    private inline fun <T> binderCall(conn: Conn, call: () -> T): T = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        release(conn, null)
        throw BridgeException.Failed(IApiBridge.INTERNAL, "bridge disconnected")
    } catch (e: RuntimeException) {
        // Any other failure of the call (e.g. a bad parcel): drop the binding, rebind next time.
        release(conn, null)
        throw BridgeException.Failed(IApiBridge.INTERNAL, "bridge call failed: ${e.javaClass.simpleName}")
    }

    private fun bind(): Conn {
        // Never bind to, and so never hand a request to, a package signed by someone else.
        val pin = expectedCert
        if (pin != null && !signingCheck.signedBy(context, targetPackage, pin)) {
            throw BridgeException.Untrusted(targetPackage)
        }
        val conn = Conn()
        connection = conn
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

    private fun doBind(intent: Intent, conn: ServiceConnection): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            context.bindService(
                intent,
                BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES,
                ContextCompat.getMainExecutor(context),
                conn
            )
        } else {
            // Without an executor, the callbacks arrive on the main thread too.
            context.bindService(intent, conn, BIND_AUTO_CREATE)
        }

    /**
     * Unbinds [conn] if it is still the current binding, and fails every [connect] that waits on
     * it: one that waits for the binding with [error], one that waits for an answer with
     * "bridge disconnected". The next [connect] binds afresh. Main thread only.
     */
    private fun release(conn: Conn?, error: BridgeException?) {
        if (conn == null || connection !== conn) return
        connection = null
        conn.service.completeExceptionally(error ?: BridgeException.NotInstalled(targetPackage))
        conn.lost.complete()
        try {
            context.unbindService(conn)
        } catch (_: Exception) {
        }
    }

    private fun toEndpoint(reply: ApiEndpoint): Endpoint {
        val code = reply.code
        if (code == IApiBridge.APPROVAL_REQUIRED) throw BridgeException.ApprovalRequired()
        if (code != IApiBridge.OK) throw BridgeException.Failed(code, messageFor(code))
        val baseUrl = reply.baseUrl
        val apiKey = reply.apiKey
        if (baseUrl == null || apiKey == null) {
            throw BridgeException.Failed(IApiBridge.INTERNAL, "malformed reply")
        }
        return Endpoint(baseUrl, apiKey)
    }

    private fun messageFor(code: Int): String = when (code) {
        IApiBridge.NOT_LOGGED_IN -> "not logged in"
        IApiBridge.START_FAILED -> "local API failed to start"
        IApiBridge.API_FAILED -> "local API request failed"
        IApiBridge.INTERNAL -> "internal error"
        else -> "error $code"
    }

    /**
     * Unbinds from the service and fails every [connect] that waits on the binding. The next
     * [connect] binds afresh. Runs at once on the main thread; from another thread it is queued
     * there, ahead of any [connect] called after it.
     */
    fun close() {
        if (Looper.myLooper() === Looper.getMainLooper()) release(connection, null)
        else mainHandler.post { release(connection, null) }
    }

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

        /**
         * How long [connect] waits for the answer of the service. The connect inside Anytype can
         * start an account and heart's listener; the old blocking service gave up after 45 s,
         * and this keeps that budget plus 5 s for the binder round trips and a busy main thread.
         */
        const val REPLY_TIMEOUT_MS = 50_000L

        /** The first [IApiBridge.VERSION] whose connect answers through [IConnectCallback]. */
        const val BIND_ACTION = "io.anytype.apibridge.BIND"
        const val APPROVE_ACTION = "io.anytype.apibridge.APPROVE"

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

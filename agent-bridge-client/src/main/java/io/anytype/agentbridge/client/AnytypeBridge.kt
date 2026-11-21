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
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import io.anytype.agentbridge.IAgentBridge
import io.anytype.agentbridge.IAgentBridgeCallback
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Endpoint(val baseUrl: String, val apiKey: String)

/**
 * Client for the Anytype agent bridge service. Binds to the bridge service of [targetPackage]
 * and asks it for the local API endpoint (base URL and bearer key).
 */
class AnytypeBridge(
    context: Context,
    val targetPackage: String = DEFAULT_PACKAGE
) {

    private val context: Context = context.applicationContext ?: context
    private val lock = Any()

    // Guarded by lock.
    private var service: IAgentBridge? = null
    private var bound = false
    private var connection: ServiceConnection? = null
    private val waiting = mutableListOf<CancellableContinuation<IAgentBridge>>()
    // Requests sent to the service whose callback has not arrived yet.
    private val inFlight = mutableListOf<CancellableContinuation<Endpoint>>()

    /**
     * Binds to the bridge service if needed, then requests the endpoint. Resumes exactly once per call.
     * Throws [BridgeException].
     */
    suspend fun connect(): Endpoint {
        val bridge = awaitService()
        return requestEndpoint(bridge)
    }

    private suspend fun requestEndpoint(bridge: IAgentBridge): Endpoint =
        suspendCancellableCoroutine { cont ->
            val registered = synchronized(lock) {
                if (service === bridge) {
                    inFlight.add(cont)
                    true
                } else {
                    false
                }
            }
            if (!registered) {
                cont.resumeWithException(
                    BridgeException.Failed(IAgentBridgeCallback.INTERNAL, "service disconnected")
                )
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { synchronized(lock) { inFlight.remove(cont) } }
            val callback = object : IAgentBridgeCallback.Stub() {
                override fun onReady(baseUrl: String, apiKey: String) {
                    synchronized(lock) { inFlight.remove(cont) }
                    if (cont.isActive) cont.resume(Endpoint(baseUrl, apiKey))
                }

                override fun onApprovalRequired() {
                    synchronized(lock) { inFlight.remove(cont) }
                    if (cont.isActive) cont.resumeWithException(BridgeException.ApprovalRequired())
                }

                override fun onError(code: Int, message: String?) {
                    synchronized(lock) { inFlight.remove(cont) }
                    if (cont.isActive) {
                        cont.resumeWithException(BridgeException.Failed(code, message.orEmpty()))
                    }
                }
            }
            try {
                bridge.connect(callback)
            } catch (e: Exception) {
                // Dead binder or similar: drop the cached service so the next call rebinds.
                synchronized(lock) { inFlight.remove(cont) }
                releaseAndFail(
                    BridgeException.Failed(IAgentBridgeCallback.INTERNAL, e.message.orEmpty())
                )
                if (cont.isActive) {
                    cont.resumeWithException(
                        BridgeException.Failed(IAgentBridgeCallback.INTERNAL, e.message.orEmpty())
                    )
                }
            }
        }

    private suspend fun awaitService(): IAgentBridge = suspendCancellableCoroutine { cont ->
        var startBind = false
        var cached: IAgentBridge? = null
        synchronized(lock) {
            val current = service
            if (current != null) {
                cached = current
            } else {
                waiting.add(cont)
                if (!bound) {
                    bound = true
                    startBind = true
                }
            }
        }
        cached?.let { cont.resume(it); return@suspendCancellableCoroutine }
        cont.invokeOnCancellation { synchronized(lock) { waiting.remove(cont) } }
        if (startBind) bind()
    }

    private fun bind() {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val stub = IAgentBridge.Stub.asInterface(binder)
                val toResume: List<CancellableContinuation<IAgentBridge>>
                synchronized(lock) {
                    if (connection !== this) return // stale connection
                    service = stub
                    toResume = waiting.toList()
                    waiting.clear()
                }
                toResume.forEach { if (it.isActive) it.resume(stub) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The service process died. Do not wait for an automatic reconnect that may never
                // come (app disabled or uninstalled): fail everything pending and rebind next time.
                if (!isCurrent(this)) return
                releaseAndFail(
                    BridgeException.Failed(IAgentBridgeCallback.INTERNAL, "service disconnected")
                )
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!isCurrent(this)) return
                releaseAndFail(
                    BridgeException.Failed(IAgentBridgeCallback.INTERNAL, "binding died")
                )
            }

            override fun onNullBinding(name: ComponentName?) {
                if (!isCurrent(this)) return
                releaseAndFail(BridgeException.NotInstalled(targetPackage))
            }
        }
        synchronized(lock) { connection = conn }
        val intent = Intent(BIND_ACTION).setPackage(targetPackage)
        val ok = try {
            doBind(intent, conn)
        } catch (e: SecurityException) {
            false
        }
        if (!ok) {
            try {
                context.unbindService(conn)
            } catch (_: Exception) {
            }
            synchronized(lock) {
                connection = null
                bound = false
            }
            failWaiting(BridgeException.NotInstalled(targetPackage))
        }
    }

    private fun doBind(intent: Intent, conn: ServiceConnection): Boolean {
        val executor = ContextCompat.getMainExecutor(context)
        return when {
            Build.VERSION.SDK_INT >= 33 -> context.bindService(
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

    private fun isCurrent(conn: ServiceConnection): Boolean = synchronized(lock) { connection === conn }

    /** Fails pending binds and in-flight requests. */
    private fun failWaiting(e: BridgeException) {
        val toFail: List<CancellableContinuation<IAgentBridge>>
        val requests: List<CancellableContinuation<Endpoint>>
        synchronized(lock) {
            toFail = waiting.toList()
            waiting.clear()
            requests = inFlight.toList()
            inFlight.clear()
        }
        toFail.forEach { if (it.isActive) it.resumeWithException(e) }
        requests.forEach { if (it.isActive) it.resumeWithException(e) }
    }

    /** Drops the connection first (so late callbacks are ignored), then fails all pending calls. */
    private fun releaseAndFail(e: BridgeException) {
        clearService()
        failWaiting(e)
    }

    /** Drops the cached service and releases the binding. */
    private fun clearService() {
        val conn: ServiceConnection?
        synchronized(lock) {
            service = null
            conn = connection
            connection = null
            bound = false
        }
        if (conn != null) {
            try {
                context.unbindService(conn)
            } catch (_: Exception) {
            }
        }
    }

    /** Unbinds from the service and fails any pending [connect] calls. */
    fun close() {
        releaseAndFail(BridgeException.NotInstalled(targetPackage))
    }

    /** Call when the endpoint was rejected (e.g. key rotated): the next [connect] binds afresh. */
    fun markStale() {
        releaseAndFail(BridgeException.Failed(IAgentBridgeCallback.INTERNAL, "endpoint marked stale"))
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

    private object Api34 {
        fun shareIdentityBundle(): Bundle =
            ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
    }

    companion object {
        const val DEFAULT_PACKAGE = "io.anytype.app"
        const val BIND_ACTION = "io.anytype.agentbridge.BIND"
        const val APPROVE_ACTION = "io.anytype.agentbridge.APPROVE"

        /** Packages that expose the bridge bind action. */
        fun findInstalled(context: Context): List<String> =
            context.packageManager
                .queryIntentServices(Intent(BIND_ACTION), PackageManager.GET_RESOLVED_FILTER)
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
    }
}

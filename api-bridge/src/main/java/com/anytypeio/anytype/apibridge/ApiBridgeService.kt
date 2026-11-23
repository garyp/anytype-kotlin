package com.anytypeio.anytype.apibridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import io.anytype.apibridge.ApiEndpoint
import io.anytype.apibridge.IApiBridge
import io.anytype.apibridge.IConnectCallback
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The exported service connected apps bind with [ApiBridge.ACTION_BIND]. The manifest enables it
 * only on API 35+ (`@bool/api_bridge_enabled`).
 *
 * [IApiBridge.connect] is a oneway call: it reads the calling uid on the binder thread and
 * returns at once, and the answer goes to the caller's [IConnectCallback] when it is ready. No
 * binder thread waits for anything: the rest runs in [ApiBridge.backgroundScope]. There, a
 * caller approved under no account is answered APPROVAL_REQUIRED first, before it can wait on
 * the controller mutex or start the account. Calls from one approved uid share the connect in
 * flight ([ApiBridge.inFlightConnects]): a second call while one runs gets the same result, so
 * one uid holds at most one connect, and each extra call costs only a completion handler.
 *
 * The service sets no deadline; the client bounds its own wait. A caller that has gone away is
 * ignored when its answer is ready. Errors travel in [ApiEndpoint.code] only; details stay in the
 * log, and no exception ever crosses the binder.
 */
@RequiresApi(35)
class ApiBridgeService : Service() {

    private val binder = object : IApiBridge.Stub() {
        override fun connect(callback: IConnectCallback?) {
            // Only valid on the binder thread, before any switch.
            val uid = Binder.getCallingUid()
            callback ?: return
            try {
                val controller = ApiBridge.controller(this@ApiBridgeService)
                if (controller == null) {
                    reply(uid, callback, failure(IApiBridge.INTERNAL, NOT_INSTALLED))
                    return
                }
                ApiBridge.backgroundScope.launch {
                    val endpoint = try {
                        // Fast path: a caller approved under no account never reaches the mutex.
                        if (!controller.isApprovedAnywhere(uid)) {
                            failure(IApiBridge.APPROVAL_REQUIRED)
                        } else {
                            endpoint(sharedConnect(controller, uid).await())
                        }
                    } catch (e: Throwable) {
                        // Cancelled with the scope, or a failure the controller let through.
                        failure(IApiBridge.INTERNAL, e.toString())
                    }
                    reply(uid, callback, endpoint)
                }
            } catch (e: Exception) {
                // Never let an exception cross the binder: the caller gets a code, nothing more.
                reply(uid, callback, failure(IApiBridge.INTERNAL, e.toString()))
            }
        }

        override fun version(): Int = IApiBridge.VERSION
    }

    /**
     * The connect in flight for [uid], or a new one. Lazy, so that the removal on completion is
     * registered before it can finish and leave a finished connect in the map.
     */
    private fun sharedConnect(controller: BridgeController, uid: Int): Deferred<BridgeController.ConnectResult> {
        val inFlight = ApiBridge.inFlightConnects
        val deferred = inFlight.computeIfAbsent(uid) {
            ApiBridge.backgroundScope.async(start = CoroutineStart.LAZY) { controller.connect(uid) }
        }
        deferred.invokeOnCompletion { inFlight.remove(uid, deferred) }
        deferred.start()
        return deferred
    }

    private fun endpoint(r: BridgeController.ConnectResult): ApiEndpoint = when (r) {
        is BridgeController.ConnectResult.Ready -> ApiEndpoint(IApiBridge.OK, r.baseUrl, r.apiKey)
        BridgeController.ConnectResult.ApprovalRequired -> failure(IApiBridge.APPROVAL_REQUIRED)
        is BridgeController.ConnectResult.Error -> failure(r.code, r.message)
    }

    /** Sends the answer; a caller that has gone away is only logged. */
    private fun reply(uid: Int, callback: IConnectCallback, endpoint: ApiEndpoint) {
        try {
            callback.onResult(endpoint)
        } catch (e: Exception) {
            Log.i(TAG, "connect from uid $uid: caller gone before the answer")
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        ApiBridge.activeBindings.incrementAndGet()
        return binder
    }

    /** A new bind with an intent whose clients had all gone, while the service stayed alive. */
    override fun onRebind(intent: Intent?) {
        ApiBridge.activeBindings.incrementAndGet()
    }

    /**
     * Called once the last client bound with an intent [Intent.filterEquals] to [intent] has gone,
     * not when every client has: an app needs no approval to bind with a different intent. The
     * listener therefore closes only when [ApiBridge.activeBindings] reaches zero, and the
     * controller re-checks that under its mutex, so a client that binds before the close runs
     * keeps its listener.
     *
     * Returns true so that a later bind with the same intent calls [onRebind]. With false the
     * system hands out the cached binder silently, and the count would miss that binding.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        if (ApiBridge.activeBindings.decrementAndGet() <= 0) closeIfIdle()
        return true
    }

    override fun onDestroy() {
        // A bound-only service is destroyed only after its last binding of any intent has gone.
        ApiBridge.activeBindings.set(0)
        closeIfIdle()
        super.onDestroy()
    }

    private fun closeIfIdle() {
        val controller = ApiBridge.controller(this) ?: return
        // Not a service scope: the system destroys the service right after the last unbind.
        ApiBridge.backgroundScope.launch(ApiBridge.unbindDispatcher) { controller.onAllUnbound() }
    }

    /** Details ([why]) stay in the log; the third-party caller gets the code alone. */
    private fun failure(code: Int, why: String? = null): ApiEndpoint {
        if (why != null) Log.w(TAG, "connect failed with code $code: $why")
        return ApiEndpoint(code, null, null)
    }

    private companion object {
        const val TAG = "ApiBridge"
        const val NOT_INSTALLED = "bridge not installed"
    }
}

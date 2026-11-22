package com.anytypeio.anytype.apibridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import io.anytype.apibridge.ApiEndpoint
import io.anytype.apibridge.IApiBridge
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The exported service connected apps bind with [ApiBridge.ACTION_BIND]. The manifest enables it
 * only on API 35+ (`@bool/api_bridge_enabled`).
 *
 * [IApiBridge.connect] blocks the binder thread until the endpoint is ready, but for at most
 * [ApiBridge.connectTimeoutMs] (45 s): the connect runs in [ApiBridge.backgroundScope], and a
 * caller still waiting at the deadline gets INTERNAL while the connect finishes under the
 * controller mutex. Calls from one uid share the connect in flight, so a caller cannot pile up
 * binder threads behind the mutex. Errors travel in [ApiEndpoint.code] only; details stay in
 * the log.
 */
@RequiresApi(35)
class ApiBridgeService : Service() {

    private val binder = object : IApiBridge.Stub() {
        override fun connect(): ApiEndpoint {
            // Only valid on the binder thread, before any switch.
            val uid = Binder.getCallingUid()
            return try {
                answer(uid)
            } catch (e: Throwable) {
                // Never let an exception cross the binder: the caller gets a code, nothing more.
                Log.w(TAG, "connect from uid $uid failed", e)
                failure(IApiBridge.INTERNAL)
            }
        }

        override fun version(): Int = IApiBridge.VERSION
    }

    private fun answer(uid: Int): ApiEndpoint {
        val controller = ApiBridge.controller(this)
        if (controller == null) {
            Log.w(TAG, "connect from uid $uid: $NOT_INSTALLED")
            return failure(IApiBridge.INTERNAL)
        }
        // Fast path, before anything that waits: a caller approved under no account must not
        // tie up a binder thread behind the mutex or an account start.
        if (!controller.isApprovedAnywhere(uid)) return failure(IApiBridge.APPROVAL_REQUIRED)
        val r = awaitConnect(controller, uid) ?: run {
            Log.w(TAG, "connect from uid $uid timed out after ${ApiBridge.connectTimeoutMs} ms")
            return failure(IApiBridge.INTERNAL)
        }
        return when (r) {
            is BridgeController.ConnectResult.Ready -> ApiEndpoint(IApiBridge.OK, r.baseUrl, r.apiKey)
            BridgeController.ConnectResult.ApprovalRequired -> failure(IApiBridge.APPROVAL_REQUIRED)
            is BridgeController.ConnectResult.Error -> {
                // Details stay in the log; the third-party caller gets the code alone.
                Log.w(TAG, "connect from uid $uid failed: ${r.code} ${genericMessage(r.code)}: ${r.message}")
                failure(r.code)
            }
        }
    }

    /**
     * Joins the connect in flight for [uid], or starts one, and waits for it on this binder thread
     * for at most [ApiBridge.connectTimeoutMs]. Null on timeout.
     */
    private fun awaitConnect(controller: BridgeController, uid: Int): BridgeController.ConnectResult? {
        val inFlight = ApiBridge.inFlightConnects
        val deferred = inFlight.computeIfAbsent(uid) {
            ApiBridge.backgroundScope.async(start = CoroutineStart.LAZY) { controller.connect(uid) }
        }
        deferred.invokeOnCompletion { inFlight.remove(uid, deferred) }
        deferred.start()
        return runBlocking { withTimeoutOrNull(ApiBridge.connectTimeoutMs) { deferred.await() } }
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

    private fun failure(code: Int) = ApiEndpoint(code, null, null)

    private fun genericMessage(code: Int) = when (code) {
        IApiBridge.NOT_LOGGED_IN -> "not logged in"
        IApiBridge.START_FAILED -> "account start failed"
        IApiBridge.API_FAILED -> "local API call failed"
        else -> "internal error"
    }

    private companion object {
        const val TAG = "ApiBridge"
        const val NOT_INSTALLED = "bridge not installed"
    }
}

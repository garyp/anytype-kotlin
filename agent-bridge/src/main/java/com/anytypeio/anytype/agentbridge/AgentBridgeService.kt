package com.anytypeio.anytype.agentbridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import io.anytype.agentbridge.AgentEndpoint
import io.anytype.agentbridge.IAgentBridge
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The exported service agent apps bind with [AgentBridge.ACTION_BIND]. The manifest enables it
 * only on API 35+ (`@bool/agent_bridge_enabled`).
 *
 * [IAgentBridge.connect] blocks the binder thread until the endpoint is ready, but for at most
 * [AgentBridge.connectTimeoutMs] (45 s): the connect runs in [AgentBridge.backgroundScope], and a
 * caller still waiting at the deadline gets INTERNAL while the connect finishes under the
 * controller mutex. Calls from one uid share the connect in flight, so a caller cannot pile up
 * binder threads behind the mutex. Errors travel in [AgentEndpoint.code] only; details stay in
 * the log.
 */
@RequiresApi(35)
class AgentBridgeService : Service() {

    private val binder = object : IAgentBridge.Stub() {
        override fun connect(): AgentEndpoint {
            // Only valid on the binder thread, before any switch.
            val uid = Binder.getCallingUid()
            return try {
                answer(uid)
            } catch (e: Throwable) {
                // Never let an exception cross the binder: the caller gets a code, nothing more.
                Log.w(TAG, "connect from uid $uid failed", e)
                failure(IAgentBridge.INTERNAL)
            }
        }

        override fun version(): Int = IAgentBridge.VERSION
    }

    private fun answer(uid: Int): AgentEndpoint {
        val controller = AgentBridge.controller(this)
        if (controller == null) {
            Log.w(TAG, "connect from uid $uid: $NOT_INSTALLED")
            return failure(IAgentBridge.INTERNAL)
        }
        // Fast path, before anything that waits: a caller approved under no account must not
        // tie up a binder thread behind the mutex or an account start.
        if (!controller.isApprovedAnywhere(uid)) return failure(IAgentBridge.APPROVAL_REQUIRED)
        val r = awaitConnect(controller, uid) ?: run {
            Log.w(TAG, "connect from uid $uid timed out after ${AgentBridge.connectTimeoutMs} ms")
            return failure(IAgentBridge.INTERNAL)
        }
        return when (r) {
            is BridgeController.ConnectResult.Ready -> AgentEndpoint(IAgentBridge.OK, r.baseUrl, r.apiKey)
            BridgeController.ConnectResult.ApprovalRequired -> failure(IAgentBridge.APPROVAL_REQUIRED)
            is BridgeController.ConnectResult.Error -> {
                // Details stay in the log; the third-party caller gets the code alone.
                Log.w(TAG, "connect from uid $uid failed: ${r.code} ${genericMessage(r.code)}: ${r.message}")
                failure(r.code)
            }
        }
    }

    /**
     * Joins the connect in flight for [uid], or starts one, and waits for it on this binder thread
     * for at most [AgentBridge.connectTimeoutMs]. Null on timeout.
     */
    private fun awaitConnect(controller: BridgeController, uid: Int): BridgeController.ConnectResult? {
        val inFlight = AgentBridge.inFlightConnects
        val deferred = inFlight.computeIfAbsent(uid) {
            AgentBridge.backgroundScope.async(start = CoroutineStart.LAZY) { controller.connect(uid) }
        }
        deferred.invokeOnCompletion { inFlight.remove(uid, deferred) }
        deferred.start()
        return runBlocking { withTimeoutOrNull(AgentBridge.connectTimeoutMs) { deferred.await() } }
    }

    override fun onBind(intent: Intent?): IBinder {
        AgentBridge.activeBindings.incrementAndGet()
        return binder
    }

    /** A new bind with an intent whose clients had all gone, while the service stayed alive. */
    override fun onRebind(intent: Intent?) {
        AgentBridge.activeBindings.incrementAndGet()
    }

    /**
     * Called once the last client bound with an intent [Intent.filterEquals] to [intent] has gone,
     * not when every client has: an app needs no approval to bind with a different intent. The
     * listener therefore closes only when [AgentBridge.activeBindings] reaches zero, and the
     * controller re-checks that under its mutex, so a client that binds before the close runs
     * keeps its listener.
     *
     * Returns true so that a later bind with the same intent calls [onRebind]. With false the
     * system hands out the cached binder silently, and the count would miss that binding.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        if (AgentBridge.activeBindings.decrementAndGet() <= 0) closeIfIdle()
        return true
    }

    override fun onDestroy() {
        // A bound-only service is destroyed only after its last binding of any intent has gone.
        AgentBridge.activeBindings.set(0)
        closeIfIdle()
        super.onDestroy()
    }

    private fun closeIfIdle() {
        val controller = AgentBridge.controller(this) ?: return
        // Not a service scope: the system destroys the service right after the last unbind.
        AgentBridge.backgroundScope.launch(AgentBridge.unbindDispatcher) { controller.onAllUnbound() }
    }

    private fun failure(code: Int) = AgentEndpoint(code, null, null)

    private fun genericMessage(code: Int) = when (code) {
        IAgentBridge.NOT_LOGGED_IN -> "not logged in"
        IAgentBridge.START_FAILED -> "account start failed"
        IAgentBridge.API_FAILED -> "local API call failed"
        else -> "internal error"
    }

    private companion object {
        const val TAG = "AgentBridge"
        const val NOT_INSTALLED = "bridge not installed"
    }
}

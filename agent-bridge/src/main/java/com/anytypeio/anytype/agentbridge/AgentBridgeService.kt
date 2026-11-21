package com.anytypeio.anytype.agentbridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RequiresApi
import io.anytype.agentbridge.IAgentBridge
import io.anytype.agentbridge.IAgentBridgeCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The exported service agent apps bind with [AgentBridge.ACTION_BIND]. The manifest enables it
 * only on API 35+ (`@bool/agent_bridge_enabled`).
 */
@RequiresApi(35)
class AgentBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binder = object : IAgentBridge.Stub() {
        override fun connect(cb: IAgentBridgeCallback?) {
            cb ?: return
            // Only valid on the binder thread, before any switch.
            val uid = Binder.getCallingUid()
            scope.launch { deliver(cb, uid) }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        AgentBridge.activeBindings.incrementAndGet()
        return binder
    }

    /**
     * Called once the last client bound with an intent [Intent.filterEquals] to [intent] has gone,
     * not when every client has: an app needs no approval to bind with a different intent. The
     * listener therefore closes only when [AgentBridge.activeBindings] reaches zero, and the
     * controller re-checks that under its mutex, so a client that binds before the close runs
     * keeps its listener.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        if (AgentBridge.activeBindings.decrementAndGet() <= 0) closeIfIdle()
        return false
    }

    override fun onDestroy() {
        // A bound-only service is destroyed only after its last binding of any intent has gone.
        AgentBridge.activeBindings.set(0)
        closeIfIdle()
        scope.cancel()
        super.onDestroy()
    }

    private fun closeIfIdle() {
        val controller = AgentBridge.controller(this) ?: return
        // Not the service scope: the system destroys the service right after the last unbind.
        AgentBridge.backgroundScope.launch(AgentBridge.unbindDispatcher) { controller.onAllUnbound() }
    }

    private suspend fun deliver(cb: IAgentBridgeCallback, uid: Int) {
        val controller = AgentBridge.controller(this)
        if (controller == null) {
            call { cb.onError(IAgentBridgeCallback.INTERNAL, NOT_INSTALLED) }
            return
        }
        when (val r = controller.connect(uid)) {
            is BridgeController.ConnectResult.Ready -> call { cb.onReady(r.baseUrl, r.apiKey) }
            BridgeController.ConnectResult.ApprovalRequired -> call { cb.onApprovalRequired() }
            is BridgeController.ConnectResult.Error -> {
                // Details stay in the log; the third-party caller gets a generic message.
                Log.w(TAG, "connect from uid $uid failed: ${r.code} ${r.message}")
                call { cb.onError(r.code, genericMessage(r.code)) }
            }
        }
    }

    private inline fun call(block: () -> Unit) {
        try {
            block()
        } catch (e: RemoteException) {
            // The client went away; nothing to tell it.
        }
    }

    private fun genericMessage(code: Int) = when (code) {
        IAgentBridgeCallback.NOT_LOGGED_IN -> "not logged in"
        IAgentBridgeCallback.START_FAILED -> "account start failed"
        IAgentBridgeCallback.API_FAILED -> "local API call failed"
        else -> "internal error"
    }

    private companion object {
        const val TAG = "AgentBridge"
        const val NOT_INSTALLED = "bridge not installed"
    }
}

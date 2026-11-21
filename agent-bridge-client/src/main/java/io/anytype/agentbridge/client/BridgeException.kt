package io.anytype.agentbridge.client

sealed class BridgeException(message: String?) : Exception(message) {
    class ApprovalRequired : BridgeException("Approval required")
    class NotInstalled(val pkg: String) : BridgeException("Anytype bridge not available: $pkg")
    class Failed(val code: Int, val msg: String) : BridgeException("Bridge failed ($code): $msg")
}

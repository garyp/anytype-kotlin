package io.anytype.agentbridge.client

sealed class BridgeException(message: String?) : Exception(message) {
    class ApprovalRequired : BridgeException("Approval required")
    class NotInstalled(val pkg: String) : BridgeException("Anytype bridge not available: $pkg")

    /** [pkg] is not signed with the certificate the agent pinned; the bridge did not bind. */
    class Untrusted(val pkg: String) : BridgeException("Not signed with the expected certificate: $pkg")
    class Failed(val code: Int, val msg: String) : BridgeException("Bridge failed ($code): $msg")
}

# Applied to every app that depends on :agent-bridge-client.
# The AIDL stubs are looked up by Binder across apps and must keep their names.
-keep class io.anytype.agentbridge.** { *; }
-keep class io.anytype.agentbridge.client.BridgeException { *; }
-keep class io.anytype.agentbridge.client.BridgeException$* { *; }

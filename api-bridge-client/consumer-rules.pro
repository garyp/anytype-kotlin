# Applied to every app that depends on :api-bridge-client.
# The AIDL stubs are looked up by Binder across apps and must keep their names.
-keep class io.anytype.apibridge.** { *; }
-keep class io.anytype.apibridge.client.BridgeException { *; }
-keep class io.anytype.apibridge.client.BridgeException$* { *; }

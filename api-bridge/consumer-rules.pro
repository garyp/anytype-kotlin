# Applied to every app that depends on :api-bridge.
# The AIDL stubs and the components named in the manifest must survive shrinking.
-keep class io.anytype.apibridge.** { *; }
-keep class com.anytypeio.anytype.apibridge.ApiBridgeService
-keep class com.anytypeio.anytype.apibridge.consent.ConnectApprovalActivity
-keep class com.anytypeio.anytype.apibridge.access.ConnectedAppsActivity

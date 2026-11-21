# Applied to every app that depends on :agent-bridge.
# The AIDL stubs and the components named in the manifest must survive shrinking.
-keep class io.anytype.agentbridge.** { *; }
-keep class com.anytypeio.anytype.agentbridge.AgentBridgeService
-keep class com.anytypeio.anytype.agentbridge.consent.AgentApprovalActivity
-keep class com.anytypeio.anytype.agentbridge.access.AgentAccessActivity

package io.anytype.agentbridge;

oneway interface IAgentBridgeCallback {
    const int NOT_LOGGED_IN = 1;
    const int START_FAILED = 2;
    const int API_FAILED = 3;
    const int INTERNAL = 4;
    void onReady(String baseUrl, String apiKey);
    void onApprovalRequired();
    void onError(int code, String message);
}

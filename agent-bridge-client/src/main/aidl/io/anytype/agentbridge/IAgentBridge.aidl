package io.anytype.agentbridge;

import io.anytype.agentbridge.IAgentBridgeCallback;

oneway interface IAgentBridge {
    const int VERSION = 1;
    void connect(IAgentBridgeCallback cb);
}

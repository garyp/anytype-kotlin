package io.anytype.agentbridge;

import io.anytype.agentbridge.AgentEndpoint;

interface IAgentBridge {
    const int VERSION = 2;
    const int OK = 0;
    const int NOT_LOGGED_IN = 1;
    const int START_FAILED = 2;
    const int API_FAILED = 3;
    const int INTERNAL = 4;
    const int APPROVAL_REQUIRED = 5;

    /** Blocks until the endpoint is ready. The result code is OK or one of the error codes above. */
    AgentEndpoint connect();

    /**
     * Returns VERSION. Appended after connect, so the transaction codes of earlier methods stay
     * the same; new methods go after this one.
     */
    int version();
}

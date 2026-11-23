package io.anytype.apibridge;

import io.anytype.apibridge.IConnectCallback;

interface IApiBridge {
    /** 3: connect answers through a callback. 2 (never released): connect blocked and returned it. */
    const int VERSION = 3;
    const int OK = 0;
    const int NOT_LOGGED_IN = 1;
    const int START_FAILED = 2;
    const int API_FAILED = 3;
    const int INTERNAL = 4;
    const int APPROVAL_REQUIRED = 5;

    /**
     * Returns at once. The service answers through callback.onResult exactly once, when the
     * endpoint is ready or the connect has failed: OK or one of the error codes above. The
     * service sets no deadline; the caller decides how long to wait.
     */
    oneway void connect(IConnectCallback callback);

    /**
     * Returns VERSION. Appended after connect, so the transaction codes of earlier methods stay
     * the same; new methods go after this one. A client calls it first to detect a bridge too old
     * for it.
     */
    int version();
}

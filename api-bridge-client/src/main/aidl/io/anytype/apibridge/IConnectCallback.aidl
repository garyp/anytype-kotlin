package io.anytype.apibridge;

import io.anytype.apibridge.ApiEndpoint;

/** Receives the answer of [IApiBridge.connect]. Called once, on a binder thread of the caller. */
oneway interface IConnectCallback {
    /** The result code is OK, with the base URL and key set, or one of the error codes. */
    void onResult(in ApiEndpoint endpoint);
}

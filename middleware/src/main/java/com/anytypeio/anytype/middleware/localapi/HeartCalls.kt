package com.anytypeio.anytype.middleware.localapi

import service.Service

/**
 * Seam over the gomobile entry points of the heart RPCs behind the local JSON API.
 * Requests and responses are protobuf-encoded bytes.
 */
interface HeartCalls {
    fun changeJsonApiAddr(req: ByteArray): ByteArray
    fun listApps(req: ByteArray): ByteArray
    fun createApp(req: ByteArray): ByteArray
    fun revokeApp(req: ByteArray): ByteArray
}

object GomobileHeartCalls : HeartCalls {
    override fun changeJsonApiAddr(req: ByteArray): ByteArray = Service.accountChangeJsonApiAddr(req)
    override fun listApps(req: ByteArray): ByteArray = Service.accountLocalLinkListApps(req)
    override fun createApp(req: ByteArray): ByteArray = Service.accountLocalLinkCreateApp(req)
    override fun revokeApp(req: ByteArray): ByteArray = Service.accountLocalLinkRevokeApp(req)
}

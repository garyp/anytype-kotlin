package com.anytypeio.anytype.middleware.localapi

import anytype.Event
import anytype.Rpc
import anytype.model.Account
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class LocalApiControlTest {

    private class FakeCalls : HeartCalls {
        var changeReq: Rpc.Account.ChangeJsonApiAddr.Request? = null
        var changeResp = Rpc.Account.ChangeJsonApiAddr.Response()
        var createReq: Rpc.Account.LocalLink.CreateApp.Request? = null
        var createResp = Rpc.Account.LocalLink.CreateApp.Response(appKey = "key")
        var listResp = Rpc.Account.LocalLink.ListApps.Response(
            app = listOf(Account.Auth.AppInfo(appHash = "h", appName = "bridge", appKey = "key", createdAt = 7))
        )
        var revokeReq: Rpc.Account.LocalLink.RevokeApp.Request? = null
        var revokeResp = Rpc.Account.LocalLink.RevokeApp.Response()

        override fun changeJsonApiAddr(req: ByteArray): ByteArray {
            changeReq = Rpc.Account.ChangeJsonApiAddr.Request.ADAPTER.decode(req)
            return Rpc.Account.ChangeJsonApiAddr.Response.ADAPTER.encode(changeResp)
        }

        override fun listApps(req: ByteArray): ByteArray =
            Rpc.Account.LocalLink.ListApps.Response.ADAPTER.encode(listResp)

        override fun createApp(req: ByteArray): ByteArray {
            createReq = Rpc.Account.LocalLink.CreateApp.Request.ADAPTER.decode(req)
            return Rpc.Account.LocalLink.CreateApp.Response.ADAPTER.encode(createResp)
        }

        override fun revokeApp(req: ByteArray): ByteArray {
            revokeReq = Rpc.Account.LocalLink.RevokeApp.Request.ADAPTER.decode(req)
            return Rpc.Account.LocalLink.RevokeApp.Response.ADAPTER.encode(revokeResp)
        }
    }

    private val calls = FakeCalls()
    private val control = LocalApiControl(calls)

    @Test
    fun setListenAddrReturnsStatusAddress() {
        calls.changeResp = Rpc.Account.ChangeJsonApiAddr.Response(
            status = Event.Account.JsonApiStatus(success = true, listenAddr = "127.0.0.1:31009")
        )
        assertEquals("127.0.0.1:31009", control.setListenAddr("127.0.0.1:0"))
        assertEquals("127.0.0.1:0", calls.changeReq?.listenAddr)
    }

    @Test
    fun setListenAddrThrowsOnFailedBind() {
        calls.changeResp = Rpc.Account.ChangeJsonApiAddr.Response(
            status = Event.Account.JsonApiStatus(
                success = false, listenAddr = "127.0.0.1:1", error = "address in use"
            )
        )
        val e = assertFailsWith<LocalApiException> { control.setListenAddr("127.0.0.1:1") }
        assertTrue(e.message!!.contains("address in use"))
    }

    @Test
    fun setListenAddrEmptyReturnsNull() {
        calls.changeResp = Rpc.Account.ChangeJsonApiAddr.Response(status = null)
        assertNull(control.setListenAddr(""))
    }

    @Test
    fun createAppSendsAllSpacesWhenNull() {
        val app = control.createApp("bridge", readWrite = false, spaceIds = null)
        val info = calls.createReq!!.app!!
        assertEquals("bridge", info.appName)
        assertEquals(Account.Auth.LocalApiScope.JsonAPI, info.scope)
        assertTrue(info.grant!!.allSpaces)
        assertTrue(info.grant!!.spaceIds.isEmpty())
        assertEquals(Account.Auth.AppGrant.Perm.Read, info.grant!!.perm)
        assertEquals(LocalApiApp("h", "bridge", "key", 7), app)
    }

    @Test
    fun createAppSendsSpaceIdsAndReadPerm() {
        control.createApp("bridge", readWrite = true, spaceIds = listOf("a", "b"))
        val grant = calls.createReq!!.app!!.grant!!
        assertEquals(listOf("a", "b"), grant.spaceIds)
        assertEquals(false, grant.allSpaces)
        assertEquals(Account.Auth.AppGrant.Perm.ReadWrite, grant.perm)
    }

    @Test
    fun createAppRejectsEmptySpaceList() {
        assertFailsWith<IllegalArgumentException> {
            control.createApp("bridge", readWrite = false, spaceIds = emptyList())
        }
    }

    @Test
    fun createAppReturnsListedEntryMatchedByKey() {
        calls.createResp = Rpc.Account.LocalLink.CreateApp.Response(appKey = "new")
        calls.listResp = Rpc.Account.LocalLink.ListApps.Response(
            app = listOf(
                Account.Auth.AppInfo(appHash = "h1", appName = "bridge", appKey = "old", createdAt = 1),
                Account.Auth.AppInfo(appHash = "h2", appName = "bridge", appKey = "new", createdAt = 99)
            )
        )
        assertEquals(LocalApiApp("h2", "bridge", "new", 99), control.createApp("bridge", false, null))
    }

    @Test
    fun createAppThrowsWhenCreatedKeyNotListed() {
        calls.createResp = Rpc.Account.LocalLink.CreateApp.Response(appKey = "new")
        val e = assertFailsWith<LocalApiException> { control.createApp("bridge", false, null) }
        assertTrue(e.message!!.contains("created but could not be resolved"))
    }

    @Test
    fun listAppsMapsFields() {
        calls.listResp = Rpc.Account.LocalLink.ListApps.Response(
            app = listOf(
                Account.Auth.AppInfo(appHash = "h", appName = "n", appKey = "k", createdAt = 42)
            )
        )
        assertEquals(listOf(LocalApiApp("h", "n", "k", 42)), control.listApps())
    }

    @Test
    fun revokeNotFoundIsSuccess() {
        calls.revokeResp = Rpc.Account.LocalLink.RevokeApp.Response(
            error = Rpc.Account.LocalLink.RevokeApp.Response.Error(
                code = Rpc.Account.LocalLink.RevokeApp.Response.Error.Code.NOT_FOUND
            )
        )
        control.revokeApp("gone")
        assertEquals("gone", calls.revokeReq?.appHash)
    }

    @Test
    fun errorCodeBecomesException() {
        calls.createResp = Rpc.Account.LocalLink.CreateApp.Response(
            error = Rpc.Account.LocalLink.CreateApp.Response.Error(
                code = Rpc.Account.LocalLink.CreateApp.Response.Error.Code.BAD_INPUT,
                description = "empty name"
            )
        )
        val e = assertFailsWith<LocalApiException> { control.createApp("", false, null) }
        assertTrue(e.message!!.contains("empty name"))
    }
}

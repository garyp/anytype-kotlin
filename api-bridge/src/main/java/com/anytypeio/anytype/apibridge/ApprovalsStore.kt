package com.anytypeio.anytype.apibridge

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Everything the bridge persists: approvals, pending key revocations and denial cooldowns.
 * Stored as JSON by [ApprovalsSerializer] in [APPROVALS_FILE].
 */
@Serializable
data class ApprovalsState(
    /** The format of the file. Bump it on an incompatible change, and branch on it when reading. */
    val version: Int = SCHEMA_VERSION,
    val approvals: List<Approval> = emptyList(),
    val pending: Set<PendingRevoke> = emptySet(),
    /** Package name to the end of its denial cooldown, epoch millis. */
    val deniedUntil: Map<String, Long> = emptyMap()
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * JSON through kotlinx.serialization, decoded entry by entry: a corrupt approval, pending revoke
 * or cooldown is skipped, never the entries around it. Losing a pending revoke would leave a
 * revoked app's key valid, so one bad entry must not take the rest of the file with it.
 *
 * A file that is not a JSON object at all throws [CorruptionException]; the store's
 * [ReplaceFileCorruptionHandler] then starts from an empty state instead of failing every read.
 * The trade-off: the pending revokes in such a file are lost, and the keys they named stay valid
 * until the user revokes the app again. Failing every connect and every screen instead would
 * leave the user no way to revoke anything at all.
 */
internal object ApprovalsSerializer : Serializer<ApprovalsState> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue = ApprovalsState()

    override suspend fun readFrom(input: InputStream): ApprovalsState {
        val parsed = try {
            json.parseToJsonElement(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("approvals file is not JSON", e)
        }
        val root = parsed as? JsonObject
            ?: throw CorruptionException("approvals file is not a JSON object")
        return ApprovalsState(
            approvals = (root["approvals"] as? JsonArray).decodeEach(Approval.serializer()),
            pending = (root["pending"] as? JsonArray).decodeEach(PendingRevoke.serializer()).toSet(),
            deniedUntil = (root["deniedUntil"] as? JsonObject).orEmpty().mapNotNull { (pkg, until) ->
                until.decodeOrNull(Long.serializer())?.let { pkg to it }
            }.toMap()
        )
    }

    override suspend fun writeTo(t: ApprovalsState, output: OutputStream) {
        output.write(json.encodeToString(ApprovalsState.serializer(), t).encodeToByteArray())
    }

    private fun <T> JsonArray?.decodeEach(serializer: KSerializer<T>): List<T> =
        orEmpty().mapNotNull { it.decodeOrNull(serializer) }

    /** Null for an entry that does not decode, or breaks an invariant such as [Grant]'s. */
    private fun <T> JsonElement.decodeOrNull(serializer: KSerializer<T>): T? = try {
        json.decodeFromJsonElement(serializer, this)
    } catch (e: IllegalArgumentException) {
        // SerializationException is one too.
        Log.w(TAG, "skipping a corrupt approvals entry", e)
        null
    }

    private const val TAG = "ApiBridge"
}

/** Under the app's files directory, in `datastore/`. Excluded from backup and device transfer. */
internal const val APPROVALS_FILE = "api_bridge_approvals.json"

/**
 * The approvals [DataStore] on [file]. DataStore allows one instance per file in a process:
 * production code gets its store from [ApiBridge.approvals], never from here.
 */
internal fun approvalsDataStore(file: File, scope: CoroutineScope): DataStore<ApprovalsState> =
    DataStoreFactory.create(
        serializer = ApprovalsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { e ->
            Log.e("ApiBridge", "approvals file unreadable, starting empty", e)
            ApprovalsState()
        },
        scope = scope,
        produceFile = { file }
    )

internal fun approvalsDataStore(context: Context, scope: CoroutineScope): DataStore<ApprovalsState> =
    approvalsDataStore(context.dataStoreFile(APPROVALS_FILE), scope)

package com.anytypeio.anytype.apibridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent approvals, pending key revocations and denial cooldowns.
 * Storage: prefs file `api_bridge`, key `state`, JSON.
 */
class AppApprovals(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private class State(
        val approvals: MutableList<Approval> = mutableListOf(),
        val pending: MutableSet<PendingRevoke> = linkedSetOf(),
        val deniedUntil: MutableMap<String, Long> = mutableMapOf()
    )

    fun find(accountId: String, pkg: String): Approval? =
        read().approvals.firstOrNull { it.accountId == accountId && it.packageName == pkg }

    fun all(): List<Approval> = read().approvals.toList()

    /** Replaces the package's approval; its earlier keys are queued for revocation. */
    fun approve(a: Approval) = update {
        approvals.removeAll { it.accountId == a.accountId && it.packageName == a.packageName }
        approvals.add(a)
        pending.add(PendingRevoke(a.accountId, a.packageName))
    }

    /** Removes the package's approval and queues its keys for revocation. */
    fun revoke(accountId: String, pkg: String) = update {
        approvals.removeAll { it.accountId == accountId && it.packageName == pkg }
        pending.add(PendingRevoke(accountId, pkg))
    }

    /** Queues every key of [pkg] under [accountId] for revocation; the approval stays. */
    fun queueRevoke(accountId: String, pkg: String) = update {
        pending.add(PendingRevoke(accountId, pkg))
    }

    fun pendingRevokes(accountId: String): Set<String> =
        read().pending.filter { it.accountId == accountId }.map { it.packageName }.toSet()

    fun clearPendingRevoke(accountId: String, pkg: String) = update {
        pending.remove(PendingRevoke(accountId, pkg))
    }

    fun deny(pkg: String) = update { deniedUntil[pkg] = clock() + DENIAL_COOLDOWN_MS }

    fun isInDenialCooldown(pkg: String): Boolean {
        val until = read().deniedUntil[pkg] ?: return false
        return until > clock()
    }

    private fun update(block: State.() -> Unit) = synchronized(LOCK) {
        val s = read()
        s.block()
        write(s)
    }

    private fun read(): State = synchronized(LOCK) { readLocked() }

    /**
     * Parses each section, and each entry, on its own: a bad entry is skipped, never the rest.
     * Losing a pending revoke would leave a revoked app's key valid.
     */
    private fun readLocked(): State {
        val s = State()
        val raw = prefs.getString(KEY, null) ?: return s
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            // Unreadable blob: start empty rather than crash the bridge.
            return s
        }
        o.optJSONArray("approvals")?.let { arr ->
            for (i in 0 until arr.length()) {
                skipBad {
                    val j = arr.getJSONObject(i)
                    val g = j.getJSONObject("grant")
                    val ids = g.getJSONArray("spaceIds")
                    s.approvals.add(
                        Approval(
                            accountId = j.getString("accountId"),
                            packageName = j.getString("packageName"),
                            certSha256 = j.getString("certSha256"),
                            access = Access.valueOf(j.getString("access")),
                            grant = Grant(
                                g.getBoolean("allSpaces"),
                                List(ids.length()) { ids.getString(it) }
                            ),
                            approvedAt = j.getLong("approvedAt")
                        )
                    )
                }
            }
        }
        o.optJSONArray("pending")?.let { arr ->
            for (i in 0 until arr.length()) {
                skipBad {
                    val j = arr.getJSONObject(i)
                    s.pending.add(PendingRevoke(j.getString("accountId"), j.getString("packageName")))
                }
            }
        }
        o.optJSONObject("deniedUntil")?.let { d ->
            d.keys().forEach { k -> skipBad { s.deniedUntil[k] = d.getLong(k) } }
        }
        return s
    }

    private inline fun skipBad(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // A corrupt entry: skip it, keep the others.
        }
    }

    private fun write(s: State) {
        val o = JSONObject()
        o.put("approvals", JSONArray().also { arr ->
            s.approvals.forEach { a ->
                arr.put(JSONObject().apply {
                    put("accountId", a.accountId)
                    put("packageName", a.packageName)
                    put("certSha256", a.certSha256)
                    put("access", a.access.name)
                    put("grant", JSONObject().apply {
                        put("allSpaces", a.grant.allSpaces)
                        put("spaceIds", JSONArray(a.grant.spaceIds))
                    })
                    put("approvedAt", a.approvedAt)
                })
            }
        })
        o.put("pending", JSONArray().also { arr ->
            s.pending.forEach {
                arr.put(JSONObject().put("accountId", it.accountId).put("packageName", it.packageName))
            }
        })
        o.put("deniedUntil", JSONObject().also { d -> s.deniedUntil.forEach { (k, v) -> d.put(k, v) } })
        prefs.edit().putString(KEY, o.toString()).commit()
    }

    companion object {
        /** Process-wide: several instances share one prefs blob, so every read-modify-write serializes here. */
        private val LOCK = Any()
        private const val PREFS = "api_bridge"
        private const val KEY = "state"
        const val DENIAL_COOLDOWN_MS = 30_000L
    }
}

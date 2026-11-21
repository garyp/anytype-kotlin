package com.anytypeio.anytype.agentbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent approvals, pending key revocations and denial cooldowns.
 * Storage: prefs file `agent_bridge`, key `state`, JSON.
 */
class AgentApprovals(
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

    @Synchronized
    fun find(accountId: String, pkg: String): Approval? =
        read().approvals.firstOrNull { it.accountId == accountId && it.packageName == pkg }

    @Synchronized
    fun all(): List<Approval> = read().approvals.toList()

    @Synchronized
    fun approve(a: Approval) = update {
        approvals.removeAll { it.accountId == a.accountId && it.packageName == a.packageName }
        approvals.add(a)
        pending.add(PendingRevoke(a.accountId, a.packageName))
    }

    @Synchronized
    fun revoke(accountId: String, pkg: String) = update {
        approvals.removeAll { it.accountId == accountId && it.packageName == pkg }
        pending.add(PendingRevoke(accountId, pkg))
    }

    @Synchronized
    fun pendingRevokes(accountId: String): Set<String> =
        read().pending.filter { it.accountId == accountId }.map { it.packageName }.toSet()

    @Synchronized
    fun clearPendingRevoke(accountId: String, pkg: String) = update {
        pending.remove(PendingRevoke(accountId, pkg))
    }

    @Synchronized
    fun deny(pkg: String) = update { deniedUntil[pkg] = clock() + DENIAL_COOLDOWN_MS }

    @Synchronized
    fun isInDenialCooldown(pkg: String): Boolean {
        val until = read().deniedUntil[pkg] ?: return false
        return until > clock()
    }

    private fun update(block: State.() -> Unit) {
        val s = read()
        s.block()
        write(s)
    }

    private fun read(): State {
        val s = State()
        val raw = prefs.getString(KEY, null) ?: return s
        try {
            val o = JSONObject(raw)
            o.optJSONArray("approvals")?.let { arr ->
                for (i in 0 until arr.length()) {
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
            o.optJSONArray("pending")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    s.pending.add(PendingRevoke(j.getString("accountId"), j.getString("packageName")))
                }
            }
            o.optJSONObject("deniedUntil")?.let { d ->
                d.keys().forEach { s.deniedUntil[it] = d.getLong(it) }
            }
        } catch (e: Exception) {
            // Corrupt state: start empty rather than crash the bridge.
            return State()
        }
        return s
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
        private const val PREFS = "agent_bridge"
        private const val KEY = "state"
        const val DENIAL_COOLDOWN_MS = 30_000L
    }
}

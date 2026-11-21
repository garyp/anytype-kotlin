package com.anytypeio.anytype.agentbridge.access

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.anytypeio.anytype.agentbridge.Access
import com.anytypeio.anytype.agentbridge.AgentApprovals
import com.anytypeio.anytype.agentbridge.AgentBridge
import com.anytypeio.anytype.agentbridge.Approval
import com.anytypeio.anytype.agentbridge.KeyNames
import com.anytypeio.anytype.agentbridge.consent.ConsentState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists the agent apps the user approved for the running account and revokes them
 * (`APPLICATION_PREFERENCES`). Revoking drops the approval, then deletes the apps' keys from the
 * local API when it can: heart rejects a revoked key at once, which is what cuts off an agent
 * that is connected right now. When it cannot, the pending revoke stays queued and the next
 * connect for that account sweeps the keys.
 *
 * [AgentApprovals] commits to disk under a process-wide lock and the gateway makes blocking
 * calls, so all of it, and the package lookups, run on [Dispatchers.IO].
 */
@RequiresApi(35)
class AgentAccessActivity : ComponentActivity() {

    private class Row(
        val approval: Approval,
        val label: String,
        val icon: ImageBitmap?
    )

    private class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val rows: List<Row> = emptyList(),
        val busy: Set<String> = emptySet()
    )

    private var ui by mutableStateOf(UiState())
    private lateinit var approvals: AgentApprovals
    private var accountId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No other app may draw over a screen whose buttons remove access, and no screenshots.
        window.setHideOverlayWindows(true)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        approvals = AgentApprovals(applicationContext)
        setContentView(ComposeView(this).apply {
            filterTouchesWhenObscured = true
            setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
            setContent {
                AccessTheme {
                    AccessContent(ui, ::onRevoke)
                }
            }
        })
        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        val launcher = AgentBridge.launcher
        if (launcher == null || AgentBridge.gateway == null) {
            ui = UiState(loading = false, error = NOT_READY)
            return
        }
        val account = try {
            withContext(Dispatchers.IO) { launcher.runningAccount() ?: launcher.ensureStarted() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ui = UiState(loading = false, error = SIGN_IN_FIRST)
            return
        }
        accountId = account.id
        refresh()
    }

    private suspend fun refresh() {
        val acc = accountId ?: return
        val px = (ICON_DP * resources.displayMetrics.density).toInt()
        val rows = withContext(Dispatchers.IO) {
            approvals.all().filter { it.accountId == acc }.sortedBy { it.packageName }.map {
                Row(it, labelOf(it.packageName), iconOf(it.packageName, px))
            }
        }
        ui = UiState(loading = false, rows = rows, busy = ui.busy)
    }

    private fun onRevoke(row: Row) {
        val acc = accountId ?: return
        val pkg = row.approval.packageName
        if (pkg in ui.busy) return
        ui = UiState(loading = false, rows = ui.rows, busy = ui.busy + pkg)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { revoke(acc, pkg) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "revoke of $pkg failed", e)
            }
            ui = UiState(loading = false, rows = ui.rows, busy = ui.busy - pkg)
            refresh()
        }
    }

    /**
     * The approval goes first, so a failure below never leaves the app approved. Keys are
     * revoked only while the account runs; otherwise the pending entry stays for the next connect.
     */
    private fun revoke(accountId: String, pkg: String) {
        approvals.revoke(accountId, pkg)
        val gateway = AgentBridge.gateway ?: return
        val launcher = AgentBridge.launcher ?: return
        if (launcher.runningAccount()?.id != accountId) return
        try {
            var failed = false
            val prefix = KeyNames.prefix(pkg)
            gateway.listApps().filter { it.appName.startsWith(prefix) }.forEach {
                try {
                    gateway.revokeApp(it.appHash)
                } catch (e: Exception) {
                    Log.w(TAG, "revoke of a key failed", e)
                    failed = true
                }
            }
            if (!failed) approvals.clearPendingRevoke(accountId, pkg)
        } catch (e: Exception) {
            Log.w(TAG, "listing keys failed", e)
        }
    }

    private fun iconOf(pkg: String, px: Int): ImageBitmap? = try {
        packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
    } catch (e: Exception) {
        null
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            .ifBlank { pkg }
    } catch (e: Exception) {
        pkg
    }

    @Composable
    private fun AccessContent(state: UiState, onRevoke: (Row) -> Unit) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(24.dp))
                Text("App access", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                    state.rows.isEmpty() -> Text(EMPTY, style = MaterialTheme.typography.bodyLarge)
                    else -> LazyColumn(Modifier.widthIn(max = 600.dp)) {
                        items(state.rows, key = { it.approval.packageName }) { row ->
                            ApprovalRow(row, enabled = row.approval.packageName !in state.busy, onRevoke)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ApprovalRow(row: Row, enabled: Boolean, onRevoke: (Row) -> Unit) {
        val a = row.approval
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            row.icon?.let {
                Image(it, contentDescription = null, modifier = Modifier.size(ICON_DP.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(row.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${a.packageName} · ${ConsentState.shortCert(a.certSha256)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${accessText(a.access)} · ${grantText(a)} · ${dateText(a.approvedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { onRevoke(row) },
                enabled = enabled,
                modifier = Modifier.testTag(revokeTag(a.packageName))
            ) { Text("Revoke") }
        }
    }

    companion object {
        internal const val EMPTY = "No apps have access to Anytype."
        internal const val SIGN_IN_FIRST = "Sign in to Anytype first"
        internal const val NOT_READY = "Anytype isn't ready. Try again in a moment."
        private const val TAG = "AgentAccess"
        private const val ICON_DP = 40

        internal fun revokeTag(pkg: String) = "revoke:$pkg"

        private fun accessText(a: Access) = if (a == Access.READ_WRITE) "Read and change" else "Read only"

        private fun grantText(a: Approval) =
            if (a.grant.allSpaces) "All spaces"
            else a.grant.spaceIds.size.let { if (it == 1) "1 space" else "$it spaces" }

        private fun dateText(ms: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    }
}

@RequiresApi(35)
@Composable
private fun AccessTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

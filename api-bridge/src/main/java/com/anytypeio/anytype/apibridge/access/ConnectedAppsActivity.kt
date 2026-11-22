package com.anytypeio.anytype.apibridge.access

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.anytypeio.anytype.apibridge.Access
import com.anytypeio.anytype.apibridge.AppApprovals
import com.anytypeio.anytype.apibridge.ApiBridge
import com.anytypeio.anytype.apibridge.Approval
import com.anytypeio.anytype.apibridge.BridgeController
import com.anytypeio.anytype.apibridge.KeyNames
import com.anytypeio.anytype.apibridge.consent.ConsentState
import com.anytypeio.anytype.core_ui.R
import com.anytypeio.anytype.core_ui.extensions.contentWidthDp
import com.anytypeio.anytype.core_ui.views.ButtonSize
import com.anytypeio.anytype.core_ui.views.ButtonWarning
import com.anytypeio.anytype.core_ui.views.Caption1Medium
import com.anytypeio.anytype.core_ui.views.Caption1Regular
import com.anytypeio.anytype.core_ui.views.PreviewTitle1Medium
import com.anytypeio.anytype.core_ui.views.Title1
import com.anytypeio.anytype.core_ui.views.UxSmallTextRegular
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists the apps the user approved for the running account and revokes them
 * (`APPLICATION_PREFERENCES`). Revoking drops the approval, then deletes the apps' keys from the
 * local API when it can: heart rejects a revoked key at once, which is what cuts off an app
 * that is connected right now. When the account is not running, the pending revoke stays
 * queued and the next connect for that account sweeps the keys. When a key revoke fails, the
 * screen says so and the bridge retries in the background while the account runs.
 *
 * [AppApprovals] commits to disk under a process-wide lock and the gateway makes blocking
 * calls, so all of it, and the package lookups, run on [Dispatchers.IO].
 */
@RequiresApi(35)
class ConnectedAppsActivity : ComponentActivity() {

    private class Row(
        val approval: Approval,
        val label: String,
        val icon: ImageBitmap?
    )

    private class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val rows: List<Row> = emptyList(),
        val busy: Set<String> = emptySet(),
        /** Shown above the list, e.g. when a revoke has not completed yet. */
        val notice: String? = null
    )

    private var ui by mutableStateOf(UiState())
    private lateinit var approvals: AppApprovals
    private var accountId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge to edge, with system bar icons that contrast with the light or dark background.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // No other app may draw over a screen whose buttons remove access, and no screenshots.
        window.setHideOverlayWindows(true)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        approvals = AppApprovals(applicationContext)
        setContentView(ComposeView(this).apply {
            filterTouchesWhenObscured = true
            setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
            setContent {
                AccessContent(ui, ::onRevoke, onBack = ::finish)
            }
        })
        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        val launcher = ApiBridge.launcher
        if (launcher == null || ApiBridge.gateway == null) {
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
        ui = UiState(loading = false, rows = rows, busy = ui.busy, notice = ui.notice)
    }

    private fun onRevoke(row: Row) {
        val acc = accountId ?: return
        val pkg = row.approval.packageName
        if (pkg in ui.busy) return
        ui = UiState(loading = false, rows = ui.rows, busy = ui.busy + pkg, notice = ui.notice)
        lifecycleScope.launch {
            val complete = try {
                revoke(acc, pkg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "revoke of $pkg failed", e)
                false
            }
            ui = UiState(
                loading = false,
                rows = ui.rows,
                busy = ui.busy - pkg,
                notice = if (complete) ui.notice else REVOKE_RETRYING
            )
            refresh()
        }
    }

    /**
     * Goes through the [com.anytypeio.anytype.apibridge.BridgeController], which serializes
     * it with connects: a connect already past its approval check cannot mint a key after the
     * revoke. Without a controller (bridge not installed) only the approval is removed and the
     * pending revoke stays queued. Returns false when a key revoke failed and is being retried.
     */
    private suspend fun revoke(accountId: String, pkg: String): Boolean {
        val controller = ApiBridge.controller(applicationContext)
        if (controller == null) {
            withContext(Dispatchers.IO) { approvals.revoke(accountId, pkg) }
            return true
        }
        return controller.revokeApp(accountId, pkg) != BridgeController.RevokeOutcome.RETRYING
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
    private fun AccessContent(state: UiState, onRevoke: (Row) -> Unit, onBack: () -> Unit) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colorResource(R.color.background_primary))
                // Android 15+ draws apps edge to edge: keep the content clear of the system bars.
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(Modifier.widthIn(max = contentWidthDp()).fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                    Image(
                        painterResource(R.drawable.ic_default_top_back),
                        contentDescription = "Back",
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 4.dp)
                            .clickable(role = Role.Button, onClick = onBack)
                            .padding(10.dp)
                            .size(24.dp)
                    )
                    Text(
                        "Connected apps",
                        Modifier.padding(horizontal = 56.dp),
                        style = Title1,
                        color = colorResource(R.color.text_primary),
                        maxLines = 1
                    )
                }
                state.notice?.let {
                    Text(
                        it,
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                        style = UxSmallTextRegular,
                        color = colorResource(R.color.palette_system_red)
                    )
                }
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colorResource(R.color.glyph_active))
                    }
                    state.error != null -> Message(state.error, colorResource(R.color.palette_system_red))
                    state.rows.isEmpty() -> Message(EMPTY, colorResource(R.color.text_secondary))
                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        item {
                            Text(
                                "Apps with access",
                                Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                                style = Caption1Medium,
                                color = colorResource(R.color.text_secondary)
                            )
                        }
                        items(state.rows, key = { it.approval.packageName }) { row ->
                            ApprovalRow(row, enabled = row.approval.packageName !in state.busy, onRevoke)
                        }
                        item {
                            Text(
                                FOOTER,
                                Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                                style = Caption1Regular,
                                color = colorResource(R.color.text_secondary)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Message(text: String, color: androidx.compose.ui.graphics.Color) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
            style = UxSmallTextRegular,
            color = color,
            textAlign = TextAlign.Center
        )
    }

    @Composable
    private fun ApprovalRow(row: Row, enabled: Boolean, onRevoke: (Row) -> Unit) {
        val a = row.approval
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            row.icon?.let {
                Image(it, contentDescription = null, modifier = Modifier.size(ICON_DP.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(row.label, style = PreviewTitle1Medium, color = colorResource(R.color.text_primary))
                Text(
                    "${accessText(a.access)} · ${grantText(a)} · ${dateText(a.approvedAt)}",
                    style = Caption1Regular,
                    color = colorResource(R.color.text_secondary)
                )
                Text(
                    "${a.packageName} · ${ConsentState.shortCert(a.certSha256)}",
                    style = Caption1Regular,
                    color = colorResource(R.color.text_secondary)
                )
            }
            Spacer(Modifier.width(8.dp))
            ButtonWarning(
                text = "Revoke",
                onClick = { onRevoke(row) },
                size = ButtonSize.Small,
                isEnabled = enabled,
                modifier = Modifier.testTag(revokeTag(a.packageName))
            )
        }
    }

    companion object {
        internal const val EMPTY = "No apps have access to Anytype."
        internal const val FOOTER =
            "Connected apps can use Anytype's local API while they are connected. Revoking an app removes its access."
        internal const val SIGN_IN_FIRST = "Sign in to Anytype first"
        internal const val NOT_READY = "Anytype isn't ready. Try again in a moment."
        internal const val REVOKE_RETRYING =
            "Couldn't fully revoke access yet. Anytype keeps retrying while it runs."
        private const val TAG = "ConnectedApps"
        private const val ICON_DP = 40

        internal fun revokeTag(pkg: String) = "revoke:$pkg"

        private fun accessText(a: Access) = if (a == Access.READ_WRITE) "Read and change" else "Read only"

        private fun grantText(a: Approval) =
            if (a.grant.allSpaces) "All spaces"
            else a.grant.spaceIds.size.let { if (it == 1) "1 space" else "$it spaces" }

        private fun dateText(ms: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    }
}

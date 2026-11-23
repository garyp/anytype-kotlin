package com.anytypeio.anytype.apibridge.access

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.anytypeio.anytype.apibridge.Access
import com.anytypeio.anytype.apibridge.ApiBridge
import com.anytypeio.anytype.apibridge.AppApprovals
import com.anytypeio.anytype.apibridge.Approval
import com.anytypeio.anytype.apibridge.appIcon
import com.anytypeio.anytype.apibridge.consent.ConsentState
import com.anytypeio.anytype.apibridge.safeAppLabel
import com.anytypeio.anytype.core_ui.R
import com.anytypeio.anytype.core_ui.extensions.contentWidthDp
import com.anytypeio.anytype.core_ui.foundation.EmptyState
import com.anytypeio.anytype.core_ui.foundation.Section
import com.anytypeio.anytype.core_ui.foundation.noRippleThrottledClickable
import com.anytypeio.anytype.core_ui.views.ButtonSize
import com.anytypeio.anytype.core_ui.views.ButtonWarningLoading
import com.anytypeio.anytype.core_ui.views.Caption1Regular
import com.anytypeio.anytype.core_ui.views.PreviewTitle1Regular
import com.anytypeio.anytype.core_ui.views.Title1
import com.anytypeio.anytype.localization.R as Loc
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * A revoke completes even when the user leaves the screen before it is done.
 *
 * The gateway makes blocking calls, so they, and the package lookups, run on [Dispatchers.IO];
 * [AppApprovals] does its own I/O.
 *
 * An AppCompatActivity, so it follows Anytype's own Light/Dark setting.
 */
@RequiresApi(35)
class ConnectedAppsActivity : AppCompatActivity() {

    private class AppRow(
        val approval: Approval,
        val label: String,
        val icon: ImageBitmap?
    )

    private data class UiState(
        val loading: Boolean = true,
        /** Why the list is unavailable; null when it loaded. */
        @StringRes val error: Int? = null,
        val rows: List<AppRow> = emptyList(),
        /** Packages whose revoke is running. */
        val busy: Set<String> = emptySet(),
        /** The latest revoke that finished left a key behind, which the bridge keeps retrying. */
        val revokeIncomplete: Boolean = false
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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        approvals = ApiBridge.approvals(applicationContext)
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
            ui = ui.copy(loading = false, error = Loc.string.api_bridge_not_ready)
            return
        }
        val account = try {
            withContext(Dispatchers.IO) { launcher.runningAccount() ?: launcher.ensureStarted() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ui = ui.copy(loading = false, error = Loc.string.api_bridge_sign_in_first)
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
                AppRow(it, packageManager.safeAppLabel(it.packageName), packageManager.appIcon(it.packageName, px))
            }
        }
        ui = ui.copy(loading = false, rows = rows)
    }

    private fun onRevoke(row: AppRow) {
        val acc = accountId ?: return
        val pkg = row.approval.packageName
        if (pkg in ui.busy) return
        ui = ui.copy(busy = ui.busy + pkg)
        lifecycleScope.launch {
            val complete = try {
                revoke(acc, pkg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "revoke of $pkg failed", e)
                false
            }
            ui = ui.copy(busy = ui.busy - pkg, revokeIncomplete = !complete)
            refresh()
        }
    }

    /**
     * Goes through the [com.anytypeio.anytype.apibridge.BridgeController], which serializes
     * it with connects: a connect already past its approval check cannot mint a key after the
     * revoke. Without a controller (bridge not installed) only the approval is removed and the
     * pending revoke stays queued. Either way it is not cancellable: leaving the screen (Back,
     * a rotation) right after the tap must not skip it. Returns false when a key revoke failed
     * and is being retried.
     */
    private suspend fun revoke(accountId: String, pkg: String): Boolean = withContext(NonCancellable) {
        val controller = ApiBridge.controller(applicationContext)
        if (controller == null) {
            approvals.revoke(accountId, pkg)
            true
        } else {
            controller.revokeApp(accountId, pkg)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AccessContent(state: UiState, onRevoke: (AppRow) -> Unit, onBack: () -> Unit) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = colorResource(id = R.color.background_primary),
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(48.dp)
                ) {
                    Box(
                        Modifier
                            .width(56.dp)
                            .fillMaxHeight()
                            .noRippleThrottledClickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_default_top_back),
                            contentDescription = stringResource(Loc.string.content_desc_back_button)
                        )
                    }
                    Text(
                        stringResource(Loc.string.api_bridge_connected_apps),
                        Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
                        style = Title1,
                        color = colorResource(id = R.color.text_primary),
                        maxLines = 1
                    )
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Above the list, so it stays visible when revoking the last app empties it.
                if (state.revokeIncomplete) {
                    Text(
                        stringResource(Loc.string.api_bridge_revoke_retrying),
                        Modifier.widthIn(max = contentWidthDp()).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        style = Caption1Regular,
                        color = colorResource(R.color.palette_system_red)
                    )
                }
                when {
                    state.loading -> CircularProgressIndicator(
                        Modifier.padding(top = 48.dp),
                        color = colorResource(R.color.glyph_active)
                    )
                    state.error != null -> EmptyState(
                        modifier = Modifier.padding(top = 48.dp).widthIn(max = contentWidthDp()),
                        title = stringResource(Loc.string.api_bridge_unavailable),
                        description = stringResource(state.error),
                        icon = R.drawable.ic_popup_alert_56
                    )
                    state.rows.isEmpty() -> EmptyState(
                        modifier = Modifier.padding(top = 48.dp).widthIn(max = contentWidthDp()),
                        title = stringResource(Loc.string.api_bridge_empty_title),
                        description = stringResource(Loc.string.api_bridge_empty_description),
                        icon = R.drawable.ic_popup_lock_56
                    )
                    else -> LazyColumn(
                        Modifier
                            .widthIn(max = contentWidthDp())
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        item {
                            Section(title = stringResource(Loc.string.api_bridge_apps_with_access), textPaddingStart = 0.dp)
                        }
                        items(state.rows, key = { it.approval.packageName }) { row ->
                            ApprovalCard(row, busy = row.approval.packageName in state.busy, onRevoke)
                            Spacer(Modifier.height(8.dp))
                        }
                        item {
                            Text(
                                stringResource(Loc.string.api_bridge_footer),
                                Modifier.padding(top = 8.dp, bottom = 24.dp),
                                style = Caption1Regular,
                                color = colorResource(R.color.text_secondary)
                            )
                        }
                    }
                }
            }
        }
    }

    /** One app, drawn as a bordered settings item like the ones in space settings. */
    @Composable
    private fun ApprovalCard(row: AppRow, busy: Boolean, onRevoke: (AppRow) -> Unit) {
        val a = row.approval
        Row(
            Modifier
                .fillMaxWidth()
                .border(
                    shape = RoundedCornerShape(16.dp),
                    width = 0.5.dp,
                    color = colorResource(id = R.color.shape_primary)
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            row.icon?.let {
                Image(it, contentDescription = null, modifier = Modifier.size(ICON_DP.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(row.label, style = PreviewTitle1Regular, color = colorResource(R.color.text_primary))
                Text(
                    summary(a),
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
            ButtonWarningLoading(
                text = stringResource(Loc.string.api_bridge_revoke),
                onClick = { onRevoke(row) },
                size = ButtonSize.Small,
                loading = busy,
                modifierButton = Modifier.testTag(revokeTag(a.packageName))
            )
        }
    }

    /** "Read and change · All spaces · 14 Nov 2026". */
    @Composable
    private fun summary(a: Approval): String {
        val access = stringResource(
            if (a.access == Access.READ_WRITE) Loc.string.api_bridge_access_read_write
            else Loc.string.api_bridge_access_read_only
        )
        val grant = if (a.grant.allSpaces) {
            stringResource(Loc.string.api_bridge_all_spaces)
        } else {
            val n = a.grant.spaceIds.size
            pluralStringResource(Loc.plurals.api_bridge_space_count, n, n)
        }
        val date = remember(a.approvedAt) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(a.approvedAt)) }
        return stringResource(Loc.string.api_bridge_app_summary, access, grant, date)
    }

    companion object {
        private const val TAG = "ConnectedApps"
        private const val ICON_DP = 40

        internal fun revokeTag(pkg: String) = "revoke:$pkg"
    }
}

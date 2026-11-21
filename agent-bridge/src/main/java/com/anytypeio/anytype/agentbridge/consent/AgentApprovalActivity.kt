package com.anytypeio.anytype.agentbridge.consent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.lifecycleScope
import com.anytypeio.anytype.agentbridge.Access
import com.anytypeio.anytype.agentbridge.AccountRef
import com.anytypeio.anytype.agentbridge.AgentApprovals
import com.anytypeio.anytype.agentbridge.AgentBridge
import com.anytypeio.anytype.agentbridge.Approval
import com.anytypeio.anytype.agentbridge.CallerVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Signing cert SHA-256 (lowercase hex) of an installed package; null when unknown or ambiguous. */
fun interface CertLookup {
    fun certSha256(context: Context, pkg: String): String?
}

/**
 * The consent screen an agent app starts for result with [ACTION_APPROVE] and
 * `ActivityOptions.setShareIdentityEnabled(true)`. Allow stores an [Approval] and returns
 * RESULT_OK; everything else returns RESULT_CANCELED.
 *
 * The caller is only ever the one the system reports ([CallerIdentity]); Intent extras are ignored.
 * [AgentApprovals] commits to disk under a process-wide lock, so every call to it, and the
 * certificate lookup, runs on [Dispatchers.IO].
 */
@RequiresApi(35)
class AgentApprovalActivity : ComponentActivity() {

    private var state by mutableStateOf(ConsentState(loading = true))
    private lateinit var approvals: AgentApprovals
    private var caller: Caller? = null
    private var cert: String? = null
    private var account: AccountRef? = null
    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before any content: no other app may draw over this screen, and no screenshots.
        window.setHideOverlayWindows(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(RESULT_CANCELED)

        approvals = AgentApprovals(applicationContext)
        val c = callerIdentity.resolve(this)
        if (c == null || c.packageName == packageName) {
            reject(UNVERIFIED)
            return
        }
        caller = c

        setContentView(ComposeView(this).apply {
            protect()
            setContent {
                ConsentTheme {
                    ConsentDialog(
                        state = state,
                        onToggleSpace = { state = state.toggleSpace(it) },
                        onAllSpaces = { state = state.setAllSpaces(it) },
                        onAccess = { state = state.setAccess(it) },
                        onAllow = ::onAllow,
                        onDeny = ::onDeny
                    )
                }
            }
        })
        lifecycleScope.launch { load(c) }
    }

    private suspend fun load(c: Caller) {
        val pkg = c.packageName
        val app = applicationContext
        val (certSha, cooldown, label) = withContext(Dispatchers.IO) {
            Triple(certLookup.certSha256(app, pkg), approvals.isInDenialCooldown(pkg), labelOf(pkg))
        }
        if (certSha == null) return reject(UNVERIFIED)
        if (cooldown) return finish()
        cert = certSha

        val launcher = AgentBridge.launcher
        val directory = AgentBridge.spaces
        val loaded = if (launcher == null || directory == null) null else try {
            withContext(Dispatchers.IO) {
                val acc = launcher.ensureStarted()
                acc to directory.activeSpaces()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (loaded == null) {
            state = state.copy(loading = false, error = SIGN_IN_FIRST)
            return reject(SIGN_IN_FIRST)
        }
        val (acc, spaces) = loaded
        account = acc
        state = ConsentState(
            loading = false,
            accountName = acc.name,
            appLabel = label,
            packageName = pkg,
            certShort = ConsentState.shortCert(certSha),
            spaces = spaces
        )
    }

    @VisibleForTesting
    internal fun onAllow() {
        val s = state
        val pkg = caller?.packageName ?: return
        val certSha = cert ?: return
        val acc = account ?: return
        if (decided || !s.canAllow) return
        decided = true
        state = s.copy(loading = true)
        val approval = Approval(acc.id, pkg, certSha, s.access, s.grant(), System.currentTimeMillis())
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { approvals.approve(approval) }
                setResult(RESULT_OK)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(FAILED)
            }
            finish()
        }
    }

    @VisibleForTesting
    internal fun onDeny() {
        val pkg = caller?.packageName ?: return finish()
        if (decided) return
        decided = true
        state = state.copy(loading = true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { approvals.deny(pkg) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The cooldown is best effort; the answer is still no.
            }
            finish()
        }
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            .ifBlank { pkg }
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun reject(message: String) {
        toast(message)
        finish()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val ACTION_APPROVE = "io.anytype.agentbridge.APPROVE"

        internal const val SIGN_IN_FIRST = "Sign in to Anytype first"
        internal const val UNVERIFIED = "Anytype couldn't verify the app that asked for access"
        internal const val FAILED = "Something went wrong. Try again."

        private val defaultCertLookup = CertLookup { context, pkg ->
            CallerVerifier(context.packageManager, AgentApprovals(context)).currentCertSha256(pkg)
        }

        @VisibleForTesting
        @Volatile
        internal var callerIdentity: CallerIdentity = CallerIdentity.Default

        @VisibleForTesting
        @Volatile
        internal var certLookup: CertLookup = defaultCertLookup

        @VisibleForTesting
        internal fun resetForTest() {
            callerIdentity = CallerIdentity.Default
            certLookup = defaultCertLookup
        }
    }
}

/** Drops touches while another window covers this view, and hides it from accessibility services that do not declare isAccessibilityTool. */
@RequiresApi(35)
private fun View.protect() {
    filterTouchesWhenObscured = true
    setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
}

@RequiresApi(35)
@Composable
private fun ConsentTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

@RequiresApi(35)
@Composable
private fun ConsentDialog(
    state: ConsentState,
    onToggleSpace: (String) -> Unit,
    onAllSpaces: (Boolean) -> Unit,
    onAccess: (Access) -> Unit,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    Dialog(
        onDismissRequest = onDeny,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn
        )
    ) {
        // The dialog is its own window: protect its root view and window as well.
        val view = LocalView.current
        DisposableEffect(view) {
            view.protect()
            (view.parent as? DialogWindowProvider)?.window?.setHideOverlayWindows(true)
            onDispose { }
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 560.dp)
        ) {
            if (state.loading && state.appLabel.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                ConsentContent(state, onToggleSpace, onAllSpaces, onAccess, onAllow, onDeny)
            }
        }
    }
}

@Composable
private fun ConsentContent(
    state: ConsentState,
    onToggleSpace: (String) -> Unit,
    onAllSpaces: (Boolean) -> Unit,
    onAccess: (Access) -> Unit,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    val enabled = !state.loading
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(state.title(), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(state.body(), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "${state.packageName} · ${state.certShort}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(16.dp))
        Text("Access", style = MaterialTheme.typography.titleSmall)
        ChoiceRow("Read and change", state.access == Access.READ_WRITE, enabled) { onAccess(Access.READ_WRITE) }
        ChoiceRow("Read only", state.access == Access.READ_ONLY, enabled) { onAccess(Access.READ_ONLY) }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(state.allSpaces, enabled, Role.Switch) { onAllSpaces(it) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("All spaces", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = state.allSpaces, onCheckedChange = null, enabled = enabled)
        }
        if (!state.allSpaces) {
            state.spaces.forEach { space ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(space.id in state.selected, enabled, Role.Checkbox) { onToggleSpace(space.id) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = space.id in state.selected, onCheckedChange = null, enabled = enabled)
                    Spacer(Modifier.width(12.dp))
                    Text(space.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                ConsentState.NARROWED_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDeny, enabled = enabled) { Text("Don't allow") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onAllow,
                enabled = state.canAllow,
                modifier = Modifier.semantics { role = Role.Button }
            ) { Text("Allow") }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, enabled, Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

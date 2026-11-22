package com.anytypeio.anytype.apibridge.consent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.anytypeio.anytype.apibridge.Access
import com.anytypeio.anytype.apibridge.AccountRef
import com.anytypeio.anytype.apibridge.AppApprovals
import com.anytypeio.anytype.apibridge.ApiBridge
import com.anytypeio.anytype.apibridge.Approval
import com.anytypeio.anytype.apibridge.CallerVerifier
import com.anytypeio.anytype.apibridge.access.ConnectedAppsActivity
import com.anytypeio.anytype.core_ui.R
import com.anytypeio.anytype.core_ui.extensions.contentWidthDp
import com.anytypeio.anytype.core_ui.views.BodyRegular
import com.anytypeio.anytype.core_ui.views.ButtonPrimary
import com.anytypeio.anytype.core_ui.views.ButtonSecondary
import com.anytypeio.anytype.core_ui.views.ButtonSize
import com.anytypeio.anytype.core_ui.views.Caption1Medium
import com.anytypeio.anytype.core_ui.views.Caption1Regular
import com.anytypeio.anytype.core_ui.views.HeadlineHeading
import com.anytypeio.anytype.core_ui.views.UxSmallTextRegular
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Signing cert SHA-256 (lowercase hex) of an installed package; null when unknown or ambiguous. */
fun interface CertLookup {
    fun certSha256(context: Context, pkg: String): String?
}

/**
 * The consent screen a connected app starts for result with [ACTION_APPROVE] and
 * `ActivityOptions.setShareIdentityEnabled(true)`. Allow stores an [Approval] and returns
 * RESULT_OK; everything else returns RESULT_CANCELED.
 *
 * The caller is only ever the one the system reports ([CallerIdentity]); Intent extras are ignored.
 * [AppApprovals] commits to disk under a process-wide lock, so every call to it, and the
 * certificate lookup, runs on [Dispatchers.IO].
 */
@RequiresApi(35)
class ConnectApprovalActivity : ComponentActivity() {

    private var state by mutableStateOf(ConsentState(loading = true))
    private lateinit var approvals: AppApprovals
    private var caller: Caller? = null
    private var cert: String? = null
    private var account: AccountRef? = null
    private var decided = false

    private var icon by mutableStateOf<ImageBitmap?>(null)

    /**
     * Allow only works once the loaded content has been on screen, in a focused window, for
     * [armDelayMs]: the requesting app is in the foreground and can open this screen right under
     * a finger that is about to tap where Allow appears.
     */
    private var armed by mutableStateOf(false)

    /** The latest touch that went down on Allow; checked and cleared by [onAllow]. */
    private var allowDown: AllowDown? = null

    private class AllowDown(val obscured: Boolean, val whileArmed: Boolean)

    /** The dialog window's root view (the Compose DialogLayout). */
    @VisibleForTesting
    internal var dialogRoot: View? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before any content: no other app may draw over this screen, and no screenshots.
        window.setHideOverlayWindows(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(RESULT_CANCELED)

        approvals = AppApprovals(applicationContext)
        val c = callerIdentity.resolve(this)
        if (c == null || c.packageName == packageName) {
            reject(UNVERIFIED)
            return
        }
        caller = c

        setContentView(ComposeView(this).apply {
            protect()
            setContent {
                ConsentDialog(
                    state = state,
                    ui = ConsentUi(
                        icon = icon,
                        armed = armed,
                        armDelayMs = armDelayMs,
                        onArmed = { armed = it },
                        onDialogRoot = { dialogRoot = it },
                        onAllowDown = ::onAllowDown,
                        onToggleSpace = { state = state.toggleSpace(it) },
                        onAllSpaces = { state = state.setAllSpaces(it) },
                        onAccess = { state = state.setAccess(it) },
                        onAllow = ::onAllow,
                        onDeny = ::onDeny,
                        onManageAccess = ::onManageAccess
                    )
                )
            }
        })
        lifecycleScope.launch { load(c) }
    }

    private suspend fun load(c: Caller) {
        val pkg = c.packageName
        val app = applicationContext
        val iconPx = (ICON_DP * resources.displayMetrics.density).toInt()
        val (certSha, cooldown, label) = withContext(Dispatchers.IO) {
            Triple(certLookup.certSha256(app, pkg), approvals.isInDenialCooldown(pkg), labelOf(pkg))
        }
        val appIcon = withContext(Dispatchers.IO) { iconOf(pkg, iconPx) }
        if (certSha == null) return reject(UNVERIFIED)
        if (cooldown) return finish()
        cert = certSha

        val launcher = ApiBridge.launcher
        val directory = ApiBridge.spaces
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
        icon = appIcon
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
        if (decided || !s.canAllow || !armed) return
        // A tap must have gone down on an armed Allow, with no window over it. Clicks without a
        // touch (keyboard, accessibility tools) have no record and pass.
        val down = allowDown
        allowDown = null
        if (down != null && (down.obscured || !down.whileArmed)) return
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

    private fun onManageAccess() {
        startActivity(android.content.Intent(this, ConnectedAppsActivity::class.java))
    }

    private fun onAllowDown(ev: MotionEvent) {
        val obscured = ev.flags and
            (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
        allowDown = AllowDown(obscured = obscured, whileArmed = armed)
    }

    /** Null when the package or its icon is unavailable: the header then shows no icon. */
    private fun iconOf(pkg: String, px: Int): ImageBitmap? = try {
        packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
    } catch (e: Exception) {
        null
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
        const val ACTION_APPROVE = "io.anytype.apibridge.APPROVE"

        internal const val SIGN_IN_FIRST = "Sign in to Anytype first"
        internal const val UNVERIFIED = "Anytype couldn't verify the app that asked for access"
        internal const val FAILED = "Something went wrong. Try again."
        internal const val MANAGE_ACCESS = "Manage connected apps"
        internal const val ICON_TAG = "connected_app_icon"
        private const val ICON_DP = 56
        private const val ARM_DELAY_MS = 750L

        private val defaultCertLookup = CertLookup { context, pkg ->
            CallerVerifier(context.packageManager, AppApprovals(context)).currentCertSha256(pkg)
        }

        @VisibleForTesting
        @Volatile
        internal var callerIdentity: CallerIdentity = CallerIdentity.Default

        @VisibleForTesting
        @Volatile
        internal var certLookup: CertLookup = defaultCertLookup

        @VisibleForTesting
        @Volatile
        internal var armDelayMs: Long = ARM_DELAY_MS

        @VisibleForTesting
        internal fun resetForTest() {
            callerIdentity = CallerIdentity.Default
            certLookup = defaultCertLookup
            armDelayMs = ARM_DELAY_MS
        }
    }
}

/** Drops touches while another window covers this view, and hides it from accessibility services that do not declare isAccessibilityTool. */
@RequiresApi(35)
private fun View.protect() {
    filterTouchesWhenObscured = true
    setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
}


private class ConsentUi(
    val icon: ImageBitmap?,
    val armed: Boolean,
    val armDelayMs: Long,
    val onArmed: (Boolean) -> Unit,
    val onDialogRoot: (View) -> Unit,
    val onAllowDown: (MotionEvent) -> Unit,
    val onToggleSpace: (String) -> Unit,
    val onAllSpaces: (Boolean) -> Unit,
    val onAccess: (Access) -> Unit,
    val onAllow: () -> Unit,
    val onDeny: () -> Unit,
    val onManageAccess: () -> Unit
)

/**
 * An Anytype bottom sheet, drawn inside a full-window Compose [Dialog]: the dialog window is what
 * carries the protections below, so the sheet look must not come from a different window
 * (such as Material's ModalBottomSheet). Tapping outside does nothing; Back means Don't allow.
 */
@RequiresApi(35)
@Composable
private fun ConsentDialog(state: ConsentState, ui: ConsentUi) {
    Dialog(
        onDismissRequest = ui.onDeny,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // The dialog is its own window. AndroidComposeView (LocalView) never consults
        // filterTouchesWhenObscured, so the filter goes on its parent, the DialogLayout, whose
        // ViewGroup.dispatchTouchEvent drops obscured events, and on the window's decor view.
        val view = LocalView.current
        DisposableEffect(view) {
            val dialogLayout = view.parent as? View
            val window = (dialogLayout as? DialogWindowProvider)?.window
            view.protect()
            dialogLayout?.protect()
            window?.decorView?.protect()
            window?.setHideOverlayWindows(true)
            ui.onDialogRoot(dialogLayout ?: view)
            onDispose { }
        }
        val shown = state.appLabel.isNotEmpty()
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(shown, focused) {
            ui.onArmed(false)
            if (shown && focused) {
                delay(ui.armDelayMs)
                ui.onArmed(true)
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .widthIn(max = contentWidthDp())
                    .fillMaxWidth()
                    .clip(SheetShape)
                    .background(colorResource(R.color.background_secondary))
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                if (!shown) {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colorResource(R.color.glyph_active))
                    }
                } else {
                    ConsentContent(state, ui)
                }
            }
        }
    }
}

@RequiresApi(35)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConsentContent(state: ConsentState, ui: ConsentUi) {
    val enabled = !state.loading
    // Scrolls when the window is short (a phone in landscape is about 411dp tall).
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 24.dp, bottom = 16.dp)
    ) {
        ui.icon?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(56.dp)
                    .testTag(ConnectApprovalActivity.ICON_TAG)
            )
            Spacer(Modifier.height(16.dp))
        }
        CenteredText(state.title(), HeadlineHeading, colorResource(R.color.text_primary))
        Spacer(Modifier.height(8.dp))
        CenteredText(state.body(), UxSmallTextRegular, colorResource(R.color.text_primary))
        Spacer(Modifier.height(6.dp))
        CenteredText("${state.packageName} · ${state.certShort}", Caption1Regular, colorResource(R.color.text_secondary))
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            CenteredText(it, UxSmallTextRegular, colorResource(R.color.palette_system_red))
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Access")
        Group(Modifier.selectableGroup()) {
            AccessRow("Read and change", state.access == Access.READ_WRITE, enabled) { ui.onAccess(Access.READ_WRITE) }
            RowDivider()
            AccessRow("Read only", state.access == Access.READ_ONLY, enabled) { ui.onAccess(Access.READ_ONLY) }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Spaces")
        Group {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(state.allSpaces, enabled, Role.Switch) { ui.onAllSpaces(it) }
                    .heightIn(min = RowHeight)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("All spaces", Modifier.weight(1f), style = BodyRegular, color = colorResource(R.color.text_primary))
                Switch(
                    checked = state.allSpaces,
                    onCheckedChange = null,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = colorResource(R.color.color_accent_80))
                )
            }
            if (!state.allSpaces) {
                state.spaces.forEach { space ->
                    RowDivider()
                    val checked = space.id in state.selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(checked, enabled, Role.Checkbox) { ui.onToggleSpace(space.id) }
                            .heightIn(min = RowHeight)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(space.name, Modifier.weight(1f), style = BodyRegular, color = colorResource(R.color.text_primary))
                        RowIcon(if (checked) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_default)
                    }
                }
            }
        }
        if (!state.allSpaces) {
            Spacer(Modifier.height(8.dp))
            Text(
                ConsentState.NARROWED_NOTE,
                Modifier.padding(horizontal = 20.dp),
                style = Caption1Regular,
                color = colorResource(R.color.text_secondary)
            )
        }

        Spacer(Modifier.height(20.dp))
        ButtonPrimary(
            text = "Allow",
            onClick = ui.onAllow,
            enabled = state.canAllow && ui.armed,
            size = ButtonSize.Large,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .semantics { role = Role.Button }
                // Sees every DOWN on Allow without consuming it; onAllow refuses a tap whose
                // DOWN was obscured or came before Allow was armed.
                .pointerInteropFilter { ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) ui.onAllowDown(ev)
                    false
                }
        )
        Spacer(Modifier.height(8.dp))
        ButtonSecondary(
            text = "Don't allow",
            onClick = ui.onDeny,
            enabled = enabled,
            size = ButtonSize.LargeSecondary,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            ConnectApprovalActivity.MANAGE_ACCESS,
            Modifier
                .align(Alignment.CenterHorizontally)
                .clickable(enabled = enabled, role = Role.Button, onClick = ui.onManageAccess)
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            style = Caption1Medium,
            color = colorResource(R.color.text_primary)
        )
    }
}

private val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
private val GroupShape = RoundedCornerShape(12.dp)
private val RowHeight = 52.dp

@Composable
private fun CenteredText(text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        style = style,
        color = color,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
        style = Caption1Medium,
        color = colorResource(R.color.text_secondary)
    )
}

/** A bordered group of rows, as in Anytype's settings lists. */
@Composable
private fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(GroupShape)
            .border(BorderStroke(1.dp, colorResource(R.color.shape_primary)), GroupShape),
        content = content
    )
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .padding(start = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(colorResource(R.color.shape_secondary))
    )
}

@Composable
private fun RowIcon(@DrawableRes id: Int) {
    Image(painterResource(id), contentDescription = null, modifier = Modifier.size(24.dp))
}

@Composable
private fun AccessRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, enabled, Role.RadioButton, onClick = onClick)
            .heightIn(min = RowHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = BodyRegular, color = colorResource(R.color.text_primary))
        if (selected) RowIcon(R.drawable.ic_option_checked)
    }
}

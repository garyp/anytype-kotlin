package com.anytypeio.anytype.apibridge.consent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.lifecycleScope
import com.anytypeio.anytype.apibridge.Access
import com.anytypeio.anytype.apibridge.AccountRef
import com.anytypeio.anytype.apibridge.ApiBridge
import com.anytypeio.anytype.apibridge.AppApprovals
import com.anytypeio.anytype.apibridge.Approval
import com.anytypeio.anytype.apibridge.access.ConnectedAppsActivity
import com.anytypeio.anytype.apibridge.appIcon
import com.anytypeio.anytype.apibridge.currentCertSha256
import com.anytypeio.anytype.apibridge.safeAppLabel
import com.anytypeio.anytype.core_ui.R
import com.anytypeio.anytype.core_ui.extensions.contentWidthDp
import com.anytypeio.anytype.core_ui.foundation.AlertDescription
import com.anytypeio.anytype.core_ui.foundation.AlertTitle
import com.anytypeio.anytype.core_ui.foundation.Divider
import com.anytypeio.anytype.core_ui.foundation.Section
import com.anytypeio.anytype.core_ui.views.BodyRegular
import com.anytypeio.anytype.core_ui.views.ButtonPrimaryLoading
import com.anytypeio.anytype.core_ui.views.ButtonSecondary
import com.anytypeio.anytype.core_ui.views.ButtonSize
import com.anytypeio.anytype.core_ui.views.Caption1Medium
import com.anytypeio.anytype.core_ui.views.Caption1Regular
import com.anytypeio.anytype.core_ui.widgets.dv.DragHandle
import com.anytypeio.anytype.localization.R as Loc
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
 * package lookups, run on [Dispatchers.IO].
 *
 * An AppCompatActivity, so it follows Anytype's own Light/Dark setting
 * (`AppCompatDelegate.setDefaultNightMode`).
 */
@RequiresApi(35)
class ConnectApprovalActivity : AppCompatActivity() {

    /** Null until the caller is verified and the account and its spaces have loaded. */
    private var state by mutableStateOf<ConsentState?>(null)

    /** The user's answer while it is being saved; every control is disabled from then on. */
    private var answer by mutableStateOf<Answer?>(null)

    private var icon by mutableStateOf<ImageBitmap?>(null)

    /**
     * Allow only works once the loaded content has been on screen, in a focused window, for
     * [armDelayMs]: the requesting app is in the foreground and can open this screen right under
     * a finger that is about to tap where Allow appears.
     */
    private var armed by mutableStateOf(false)

    private lateinit var approvals: AppApprovals
    private var caller: Caller? = null
    private var cert: String? = null
    private var account: AccountRef? = null

    /** The choices from before a recreation (a rotation, say), applied once the content loads. */
    private var restored: ConsentChoices? = null

    /** The latest touch that went down on Allow; checked and cleared by [onAllow]. */
    private var allowDown: AllowDown? = null

    private class AllowDown(val obscured: Boolean, val whileArmed: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before any content: no other app may draw over this screen, and no screenshots.
        window.setHideOverlayWindows(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(RESULT_CANCELED)

        approvals = AppApprovals(applicationContext)
        val c = callerIdentity.resolve(this)
        if (c == null || c.packageName == packageName) {
            reject(Loc.string.api_bridge_unverified_caller)
            return
        }
        caller = c
        restored = ConsentChoices.readFrom(savedInstanceState)

        // The activity window shows nothing of its own: the sheet is a window above it.
        setContentView(ComposeView(this).apply {
            setContent {
                ConsentSheet(
                    state = state,
                    ui = ConsentUi(
                        icon = icon,
                        armed = armed,
                        answer = answer,
                        armDelayMs = armDelayMs,
                        onArmed = { armed = it },
                        onAllowDown = ::onAllowDown,
                        onToggleSpace = { id -> update { it.toggleSpace(id) } },
                        onAllSpaces = { b -> update { it.setAllSpaces(b) } },
                        onAccess = { a -> update { it.setAccess(a) } },
                        onAllow = ::onAllow,
                        onDeny = ::onDeny,
                        onManageAccess = ::onManageAccess
                    )
                )
            }
        })
        lifecycleScope.launch { load(c) }
    }

    private inline fun update(f: (ConsentState) -> ConsentState) {
        if (answer == null) state = state?.let(f)
    }

    private sealed interface Loaded {
        data object Unverified : Loaded
        data object InCooldown : Loaded
        data object NotSignedIn : Loaded
        class Ready(
            val cert: String,
            val account: AccountRef,
            val content: ConsentState,
            val icon: ImageBitmap?
        ) : Loaded
    }

    private suspend fun load(c: Caller) {
        val iconPx = (ICON_DP * resources.displayMetrics.density).toInt()
        when (val loaded = withContext(Dispatchers.IO) { loadBlocking(c.packageName, iconPx) }) {
            Loaded.Unverified -> reject(Loc.string.api_bridge_unverified_caller)
            Loaded.InCooldown -> finish()
            Loaded.NotSignedIn -> reject(Loc.string.api_bridge_sign_in_first)
            is Loaded.Ready -> {
                cert = loaded.cert
                account = loaded.account
                icon = loaded.icon
                state = loaded.content.withChoices(restored)
                restored = null
            }
        }
    }

    /** Verifies the caller's certificate first: nothing of an unverified app is read or decoded. */
    private suspend fun loadBlocking(pkg: String, iconPx: Int): Loaded {
        val certSha = certLookup.certSha256(applicationContext, pkg) ?: return Loaded.Unverified
        if (approvals.isInDenialCooldown(pkg)) return Loaded.InCooldown
        val launcher = ApiBridge.launcher ?: return Loaded.NotSignedIn
        val directory = ApiBridge.spaces ?: return Loaded.NotSignedIn
        val (acc, spaces) = try {
            launcher.ensureStarted() to directory.activeSpaces()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Loaded.NotSignedIn
        }
        val content = ConsentState(
            appLabel = packageManager.safeAppLabel(pkg),
            packageName = pkg,
            certShort = ConsentState.shortCert(certSha),
            accountName = acc.name,
            spaces = spaces
        )
        return Loaded.Ready(certSha, acc, content, packageManager.appIcon(pkg, iconPx))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Before the content loads, the choices are still the ones from before the last recreation.
        (state?.choices ?: restored)?.writeTo(outState)
    }

    private fun onAllow() {
        val s = state ?: return
        val pkg = caller?.packageName ?: return
        val certSha = cert ?: return
        val acc = account ?: return
        if (answer != null || !s.canAllow || !armed) return
        // A tap must have gone down on an armed Allow, with no window over it. Clicks without a
        // touch (keyboard, accessibility tools) have no record and pass.
        val down = allowDown
        allowDown = null
        if (down != null && (down.obscured || !down.whileArmed)) return
        answer = Answer.ALLOW
        val approval = Approval(acc.id, pkg, certSha, s.access, s.grant(), System.currentTimeMillis())
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { approvals.approve(approval) }
                setResult(RESULT_OK)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(Loc.string.api_bridge_approve_failed)
            }
            finish()
        }
    }

    private fun onDeny() {
        val pkg = caller?.packageName ?: return finish()
        if (answer != null) return
        answer = Answer.DENY
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
        startActivity(Intent(this, ConnectedAppsActivity::class.java))
    }

    private fun onAllowDown(ev: MotionEvent) {
        val obscured = ev.flags and
            (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
        allowDown = AllowDown(obscured = obscured, whileArmed = armed)
    }

    private fun reject(@StringRes message: Int) {
        toast(message)
        finish()
    }

    private fun toast(@StringRes message: Int) = Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show()

    companion object {
        const val ACTION_APPROVE = "io.anytype.apibridge.APPROVE"

        internal const val ICON_TAG = "connected_app_icon"
        private const val ICON_DP = 56
        private const val ARM_DELAY_MS = 750L

        private val defaultCertLookup = CertLookup { context, pkg ->
            currentCertSha256(context.packageManager, pkg)
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

/**
 * Drops touches while another window covers this view, and hides it from accessibility services
 * that do not declare isAccessibilityTool. Both hold for every view under it as well: a
 * ViewGroup that drops a touch never passes it on, and a view inherits data sensitivity from its
 * ancestors.
 */
@RequiresApi(35)
private fun View.protect() {
    filterTouchesWhenObscured = true
    setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
}

/** The user's answer, from the tap until the activity finishes. */
private enum class Answer { ALLOW, DENY }

private class ConsentUi(
    val icon: ImageBitmap?,
    val armed: Boolean,
    val answer: Answer?,
    val armDelayMs: Long,
    val onArmed: (Boolean) -> Unit,
    val onAllowDown: (MotionEvent) -> Unit,
    val onToggleSpace: (String) -> Unit,
    val onAllSpaces: (Boolean) -> Unit,
    val onAccess: (Access) -> Unit,
    val onAllow: () -> Unit,
    val onDeny: () -> Unit,
    val onManageAccess: () -> Unit
)

/**
 * Anytype's modal bottom sheet. Swiping it away, Back and a tap on the scrim all mean
 * Don't allow. The sheet is its own window, so it gets the secure policy itself, and its root
 * view carries the obscured-touch filter; the activity window under it already hides overlays
 * and is FLAG_SECURE.
 */
@RequiresApi(35)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsentSheet(state: ConsentState?, ui: ConsentUi) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        // A tall sheet (a phone in landscape, many spaces) stops below the status bar.
        modifier = Modifier.statusBarsPadding(),
        onDismissRequest = ui.onDeny,
        sheetState = sheetState,
        sheetMaxWidth = contentWidthDp(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = colorResource(id = R.color.background_secondary),
        scrimColor = colorResource(id = R.color.modal_screen_outside_background),
        dragHandle = { DragHandle() },
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)
    ) {
        // The compose view (LocalView) never consults filterTouchesWhenObscured, so the filter
        // goes on the root view of the sheet's window, which every touch passes first.
        val view = LocalView.current
        DisposableEffect(view) {
            view.rootView.protect()
            onDispose { }
        }
        val loaded = state != null
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(loaded, focused) {
            ui.onArmed(false)
            if (loaded && focused) {
                delay(ui.armDelayMs)
                ui.onArmed(true)
            }
        }
        if (state == null) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colorResource(R.color.glyph_active))
            }
        } else {
            ConsentContent(state, ui)
        }
    }
}

@RequiresApi(35)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConsentContent(state: ConsentState, ui: ConsentUi) {
    val enabled = ui.answer == null
    // Scrolls when the window is short (a phone in landscape is about 411dp tall).
    Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Spacer(Modifier.height(18.dp))
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
        AlertTitle(stringResource(Loc.string.api_bridge_consent_title, state.appLabel))
        Spacer(Modifier.height(8.dp))
        AlertDescription(consentBody(state))
        Spacer(Modifier.height(4.dp))
        if (state.accountName.isNotBlank()) {
            Caption(stringResource(Loc.string.api_bridge_consent_account, state.accountName))
        }
        Caption("${state.packageName} · ${state.certShort}")

        Section(title = stringResource(Loc.string.api_bridge_consent_access), textPaddingStart = 20.dp)
        Column(Modifier.selectableGroup()) {
            AccessRow(stringResource(Loc.string.api_bridge_access_read_write), state.access == Access.READ_WRITE, enabled) {
                ui.onAccess(Access.READ_WRITE)
            }
            Divider()
            AccessRow(stringResource(Loc.string.api_bridge_access_read_only), state.access == Access.READ_ONLY, enabled) {
                ui.onAccess(Access.READ_ONLY)
            }
        }
        Divider()

        Section(title = stringResource(Loc.string.api_bridge_consent_spaces), textPaddingStart = 20.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(state.allSpaces, enabled, Role.Switch) { ui.onAllSpaces(it) }
                .height(52.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(Loc.string.api_bridge_all_spaces),
                Modifier.weight(1f),
                style = BodyRegular,
                color = colorResource(R.color.text_primary)
            )
            Switch(
                checked = state.allSpaces,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = colorResource(R.color.color_accent_80))
            )
        }
        if (!state.allSpaces) {
            state.spaces.forEach { space ->
                Divider()
                val checked = space.id in state.selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(checked, enabled, Role.Checkbox) { ui.onToggleSpace(space.id) }
                        .height(52.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(space.name, Modifier.weight(1f), style = BodyRegular, color = colorResource(R.color.text_primary))
                    Image(
                        painterResource(if (checked) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_default),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        Divider()
        state.limit?.let { limit ->
            Text(
                stringResource(
                    when (limit) {
                        ConsentState.Limit.SPECIFIC_SPACES -> Loc.string.api_bridge_consent_note_spaces
                        ConsentState.Limit.READ_ONLY -> Loc.string.api_bridge_consent_note_read_only
                    }
                ),
                Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                style = Caption1Regular,
                color = colorResource(R.color.text_secondary)
            )
        }

        Spacer(Modifier.height(20.dp))
        // While Allow saves, it keeps its colors and shows the app's loading dots.
        val saving = ui.answer == Answer.ALLOW
        ButtonPrimaryLoading(
            text = stringResource(Loc.string.api_bridge_consent_allow),
            onClick = ui.onAllow,
            enabled = saving || (enabled && state.canAllow && ui.armed),
            loading = saving,
            size = ButtonSize.Large,
            modifierBox = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            modifierButton = Modifier
                .fillMaxWidth()
                // Sees every DOWN on Allow without consuming it; onAllow refuses a tap whose
                // DOWN was obscured or came before Allow was armed.
                .pointerInteropFilter { ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) ui.onAllowDown(ev)
                    false
                }
        )
        Spacer(Modifier.height(8.dp))
        ButtonSecondary(
            text = stringResource(Loc.string.api_bridge_consent_deny),
            onClick = ui.onDeny,
            enabled = enabled,
            size = ButtonSize.LargeSecondary,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Loc.string.api_bridge_manage_connected_apps),
            Modifier
                .align(Alignment.CenterHorizontally)
                .clickable(enabled = enabled, role = Role.Button, onClick = ui.onManageAccess)
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            style = Caption1Medium,
            color = colorResource(R.color.text_secondary)
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun consentBody(state: ConsentState): String {
    val app = state.appLabel
    val readWrite = state.access == Access.READ_WRITE
    return when (val reach = state.reach) {
        ConsentState.Reach.NoSpaceChosen ->
            stringResource(Loc.string.api_bridge_consent_body_choose_spaces, app)
        ConsentState.Reach.AllSpaces -> stringResource(
            if (readWrite) Loc.string.api_bridge_consent_body_all_read_write
            else Loc.string.api_bridge_consent_body_all_read_only,
            app
        )
        is ConsentState.Reach.Spaces -> pluralStringResource(
            if (readWrite) Loc.plurals.api_bridge_consent_body_spaces_read_write
            else Loc.plurals.api_bridge_consent_body_spaces_read_only,
            reach.count,
            app,
            reach.count
        )
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        style = Caption1Regular,
        color = colorResource(R.color.text_secondary),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun AccessRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, enabled, Role.RadioButton, onClick = onClick)
            .height(52.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = BodyRegular, color = colorResource(R.color.text_primary))
        if (selected) {
            Image(painterResource(R.drawable.ic_option_checked), contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

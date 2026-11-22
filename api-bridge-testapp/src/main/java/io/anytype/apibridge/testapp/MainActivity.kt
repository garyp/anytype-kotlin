package io.anytype.apibridge.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.anytype.apibridge.client.AnytypeBridge
import io.anytype.apibridge.client.BridgeException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * Optional pinned signing certificate of the Anytype build (SHA-256 hex), from the
     * [EXTRA_PINNED_CERT] extra; null when absent or invalid. With it, only packages signed with
     * that certificate are listed and bound.
     */
    private var pinnedCert: String? = null
    private var pinError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_PINNED_CERT)?.takeIf { it.isNotBlank() }?.let { pin ->
            try {
                // Validates the format up front, so a bad pin is reported, not thrown on bind.
                AnytypeBridge.findInstalled(this, pin)
                pinnedCert = pin
            } catch (e: IllegalArgumentException) {
                pinError = "ignoring pinned cert: ${e.message}"
            }
        }
        setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) { FlowScreen() } }
        }
    }

    @Composable
    private fun FlowScreen() {
        val scope = rememberCoroutineScope()
        val targets = remember { AnytypeBridge.findInstalled(this, pinnedCert) }
        // Prefer the debug build (*.dev) when several Anytype builds are installed.
        var selected by remember {
            mutableStateOf(targets.firstOrNull { it.endsWith(".dev") } ?: targets.firstOrNull())
        }
        val log = remember {
            mutableStateListOf<String>().apply {
                pinError?.let { add(it) }
                if (pinnedCert != null) add("pinned cert: only matching Anytype builds are listed")
            }
        }
        fun addLog(line: String) { log.add(line) }

        var bridge by remember { mutableStateOf<AnytypeBridge?>(null) }
        fun bridgeFor(pkg: String): AnytypeBridge {
            val current = bridge
            if (current != null && current.targetPackage == pkg) return current
            current?.close()
            return AnytypeBridge(applicationContext, pkg, pinnedCert).also { bridge = it }
        }

        val approval = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            addLog("approval screen closed (result ${it.resultCode})")
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (targets.isEmpty()) Text("No Anytype app with the API bridge is installed.")
            targets.forEach { pkg ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected == pkg) { selected = pkg },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected == pkg, onClick = { selected = pkg })
                    Text(pkg)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = selected != null, onClick = {
                    val b = bridgeFor(selected!!)
                    scope.launch {
                        try {
                            val e = b.connect()
                            addLog("connected: ${e.baseUrl}")
                        } catch (e: BridgeException.ApprovalRequired) {
                            addLog("approval required: tap Approve")
                        } catch (e: BridgeException) {
                            addLog("connect failed: ${e.message}")
                        }
                    }
                }) { Text("Connect") }
                Button(enabled = selected != null, onClick = {
                    val b = bridgeFor(selected!!)
                    approval.launch(b.approvalIntent(), b.approvalOptions())
                }) { Text("Approve") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = selected != null, onClick = {
                    val b = bridgeFor(selected!!)
                    scope.launch { FlowRunner(b, ::addLog).runAll() }
                }) { Text("Run flow") }
                Button(onClick = {
                    bridge?.close()
                    bridge = null
                    addLog("unbound")
                }) { Text("Unbind") }
            }
            LogView(log, Modifier.weight(1f))
        }
    }
}

/**
 * Intent extra with the SHA-256 of the Anytype build's signing certificate, e.g.
 * `adb shell am start -n io.anytype.apibridge.testapp/.MainActivity --es pinned_cert <hex>`.
 */
const val EXTRA_PINNED_CERT = "pinned_cert"

/** The log as ONE Text node (lines joined), so a test can assert on the tag `log` directly. */
@Composable
fun LogView(lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(lines.joinToString("\n"), Modifier.testTag("log"))
    }
}

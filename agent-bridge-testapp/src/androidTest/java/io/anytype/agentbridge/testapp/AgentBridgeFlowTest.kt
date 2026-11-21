package io.anytype.agentbridge.testapp

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.anytype.agentbridge.client.AnytypeBridge
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End to end on a device with the Anytype debug app (io.anytype.app.dev) installed and signed in:
 * Approve -> tap Allow on the consent screen -> Run flow -> expect `ALL OK` in the log.
 */
@RunWith(AndroidJUnit4::class)
class AgentBridgeFlowTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun approveAndRunFlow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val installed = AnytypeBridge.findInstalled(instrumentation.targetContext)
        assumeTrue("No Anytype app with the agent bridge installed", installed.isNotEmpty())
        val pkg = installed.firstOrNull { it.endsWith(".dev") } ?: installed.first()
        val device = UiDevice.getInstance(instrumentation)

        rule.onNodeWithText("Approve").performClick()

        // The Allow button is disabled for ~750 ms after the dialog shows, so wait until it is
        // enabled. The consent UI belongs to the Anytype app, not to the test app.
        val allow = device.wait(Until.findObject(By.pkg(pkg).text("Allow").enabled(true)), 30_000)
        assertNotNull("Consent dialog with enabled Allow not shown by $pkg", allow)
        allow.click()

        // Back in the test app.
        device.wait(Until.hasObject(By.pkg(instrumentation.targetContext.packageName)), 10_000)
        rule.waitForIdle()
        rule.onNodeWithText("Run flow").performClick()

        rule.waitUntil(timeoutMillis = 120_000) {
            runCatching { rule.onNodeWithTag("log").assertTextContains("ALL OK", substring = true) }.isSuccess ||
                runCatching { rule.onNodeWithTag("log").assertTextContains("FAIL", substring = true) }.isSuccess
        }
        rule.onNodeWithTag("log").assertTextContains("ALL OK", substring = true)
    }
}

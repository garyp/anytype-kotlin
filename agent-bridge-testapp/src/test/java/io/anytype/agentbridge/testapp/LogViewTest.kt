package io.anytype.agentbridge.testapp

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Proves the selector the instrumented test uses: onNodeWithTag("log") sees the log text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogViewTest {
    @get:Rule val rule = createComposeRule()

    @Test fun logTagExposesAllLines() {
        rule.setContent { LogView(listOf("OK connect", "ALL OK")) }
        rule.onNodeWithTag("log").assertTextContains("ALL OK", substring = true)
        rule.onNodeWithTag("log").assertTextContains("OK connect", substring = true)
    }
}

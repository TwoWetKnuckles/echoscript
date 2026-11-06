package org.echoscript.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class OfflineInterfaceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun acquireAnalyzeAndTranscribeFromTheInstalledInterface() {
        fun waitFor(text: String, timeout: Long = 30000) {
            ui.waitUntil(timeoutMillis = timeout) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
        fun click(text: String) { ui.onNodeWithText(text).performScrollTo().performClick() }
        waitFor("Choose a case or import a recording.")
        ui.onNodeWithText("Choose audio file").assertIsDisplayed()
        ui.onNodeWithText("Export").assertIsDisplayed()
        click("Load synthetic speech sample")
        waitFor("Synthetic speech is ready in Analyze.")
        click("Analyze audio")
        waitFor("Waveform, spectral overview and candidate speech regions are ready.")
        ui.onNodeWithText("Transcript").assertIsDisplayed().performClick()
        click("Transcribe active audio on device")
        waitFor("Draft transcript saved. Review the words, timestamps and speaker groups.", 120000)
        ui.onNodeWithText("Examiner transcript text").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Export").assertIsDisplayed().performClick()
        ui.onNodeWithText("Case package").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Active audio WAV").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Transcript and report formats").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }
}

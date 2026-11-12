package org.echoscript.app

import android.app.Activity
import android.app.Application
import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OfflineInterfaceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String, timeout: Long = 120000) {
        ui.waitUntil(timeoutMillis = timeout) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun click(text: String, navigation: Boolean = false) {
        waitFor(text, 30000)
        val node = ui.onNodeWithText(text)
        if (!navigation) node.performScrollTo()
        ui.waitUntil(timeoutMillis = 30000) { ui.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        node.assertIsDisplayed().performClick()
    }
    private fun document(action: String, file: File, block: () -> Unit) {
        // The platform picker result is controlled. Processing, speech, storage,
        // UI callbacks, encryption, exports and package verification remain real.
        Intents.init()
        try {
            intending(hasAction(action)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
            block()
        } finally { Intents.release() }
    }
    private fun screenshot(name: String) {
        ui.waitForIdle()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val folder = File(output).apply { mkdirs() }
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(folder, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }

    private fun recreateWithoutPersistingDraft(draft: String) {
        val application = ui.activity.application
        val saved = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {
                if (activity !is MainActivity) return
                val parcel = Parcel.obtain()
                try { parcel.writeBundle(state); saved.set(parcel.marshall()) }
                finally { parcel.recycle() }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(observer)
        try {
            ui.activityRule.scenario.recreate()
            val bytes = checkNotNull(saved.get()) { "No actual saved-instance callback observed" }
            for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
                val needle = draft.toByteArray(encoding)
                assertFalse("Unsaved review text reached a saved-instance bundle", (0..bytes.size - needle.size).any { offset ->
                    needle.indices.all { bytes[offset + it] == needle[it] }
                })
            }
        } finally { application.unregisterActivityLifecycleCallbacks(observer) }
    }

    @Test fun actualDevicePickerImportsContentUriAndCancellationIsVisible() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val filename = "ECHOSCRIPT-SYNTHETIC-PICKER.bin"
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val vault = EvidenceVault(context)
        val baseline = vault.listCases().cases.map { it.getString("caseId") }.toSet()
        try {
            context.assets.open("synthetic-speech.wav").use { input -> checkNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            click("Choose audio file")
            // No Espresso Intents interception: exercise actual DocumentsUI,
            // the Downloads provider, a content URI and Android's grant/result.
            var file = device.wait(Until.findObject(By.text(filename)), 10000)
            if (file == null) {
                val roots = device.findObject(By.desc("Show roots")) ?: device.findObject(By.desc("Show navigation drawer"))
                checkNotNull(roots) { "System file picker navigation unavailable" }.click()
                checkNotNull(device.wait(Until.findObject(By.text("Downloads")), 10000)).click()
                file = device.wait(Until.findObject(By.text(filename)), 10000)
            }
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it) }
            if (output != null) { output.mkdirs(); assertTrue(device.takeScreenshot(File(output, "ui-real-picker.png"))) }
            checkNotNull(file) { "Synthetic Downloads document not visible in actual picker" }.click()
            waitFor("Selected recording")
            waitFor(filename)
            ui.onNodeWithText("Selected recording").assertIsDisplayed()
            ui.onNodeWithText(filename).assertIsDisplayed()
            assertEquals(0, vault.listCases().cases.count { it.getString("caseId") !in baseline })
            // Rotation must not drop the pending provider URI or its visible action.
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("Selected recording").assertIsDisplayed()
            ui.onNodeWithText(filename).assertIsDisplayed()
            click("Import selected audio", navigation = true)
            waitFor("Original preserved. Waveform and speech candidates are ready.")
            ui.onNodeWithText("Imported recording").performScrollTo().assertIsDisplayed()
            assertTrue(ui.onAllNodesWithText(filename).fetchSemanticsNodes().isNotEmpty())
            ui.onNodeWithTag("waveform").performScrollTo().assertIsDisplayed()
            val imported = vault.listCases().cases.single { it.getString("caseId") !in baseline }
            assertEquals("9d17a3309b89246c14ecc5a7b9a0f9bf398de0d93824037ddaff4412b35af2bd", imported.items("artifacts").first { it.getString("type") == "SOURCE" }.getString("sha256"))
            assertTrue(vault.verify(imported).contains("agree"))
            screenshot("ui-real-picker-imported.png")
            click("Cases", navigation = true)
            click("Choose audio file")
            assertTrue(device.wait(Until.hasObject(By.desc("Show roots")), 10000) || device.hasObject(By.text("Downloads")))
            device.pressBack()
            waitFor("No recording was selected. Choose a file or try the other file picker.")
            assertEquals(1, vault.listCases().cases.count { it.getString("caseId") !in baseline })
        } finally {
            resolver.delete(uri, null, null)
            for (case in vault.listCases().cases.filter { it.getString("caseId") !in baseline })
                assertTrue(File(context.filesDir, "EvidenceVault/cases/${case.getString("caseId")}").deleteRecursively())
        }
    }

    @Test fun effectPresetsRegionsAndRestorableRecipe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = EvidenceVault(context)
        val before = vault.listCases().cases.map { it.getString("caseId") }.toSet()
        click("Load synthetic speech sample")
        waitFor("Original preserved. Waveform and speech candidates are ready.")
        val case = vault.listCases().cases.single { it.getString("caseId") !in before }
        try {
            ui.onNodeWithTag("waveform").performScrollTo().performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width * .2f, height * .5f), androidx.compose.ui.geometry.Offset(width * .6f, height * .5f), 600)
            }
            click("Speech band")
            ui.onNodeWithText("Speech band").assertIsSelected()
            click("Speech band")
            ui.onNodeWithText("Speech band").assertIsNotSelected()
            click("Hum 60 Hz")
            ui.onNodeWithText("Selection start ms").performScrollTo().performTextReplacement("200")
            ui.onNodeWithText("Selection end ms").performScrollTo().performTextReplacement("1000")
            closeSoftKeyboard()
            click("Add selection with these effects")
            ui.onNodeWithText("1: 200–1000 ms").performScrollTo().assertIsDisplayed()
            click("Render enhanced revision")
            waitFor("Effect plan rendered. Earlier audio and settings remain in revision history.")
            val saved = vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }
            val rendered = saved.items("artifacts").last { it.getString("type") == "ENHANCED_AUDIO" }
            assertEquals(200L, rendered.operationParameters().getLong("startMs"))
            assertEquals(1000L, rendered.operationParameters().getLong("endMs"))
            assertTrue(rendered.operationParameters().getJSONObject("effectRack").getJSONArray("effects").length() >= 14)
            assertEquals(1, rendered.operationParameters().getJSONObject("workbenchPlan").getJSONArray("regions").length())
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("1: 200–1000 ms").performScrollTo().assertIsDisplayed()
            click("Audio revision history")
            screenshot("ui-effect-history.png")
            click("Undo to parent")
            waitFor("Active revision changed. Earlier versions remain in the case.")
            assertEquals(rendered.getJSONArray("parents").getString(0), vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }.getString("activeArtifact"))
            click("Redo revision ${rendered.getInt("revision")}")
            waitFor("Active revision changed. Earlier versions remain in the case.")
            assertEquals(rendered.getString("id"), vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }.getString("activeArtifact"))
            assertTrue(vault.verify(vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }).contains("agree"))
        } finally { assertTrue(File(context.filesDir,"EvidenceVault/cases/${case.getString("caseId")}").deleteRecursively()) }
    }

    @Test fun appearanceAndExaminerSettingsSurviveRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ui.waitUntil(timeoutMillis = 30000) { ui.onAllNodes(hasContentDescription("Settings") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Settings").performClick()
        click("Light")
        ui.onNodeWithText("Examiner identifier").performScrollTo().performTextReplacement("SYNTHETIC_QA")
        closeSoftKeyboard()
        click("Save examiner")
        waitFor("Examiner identifier saved on this device.")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Examiner identifier").performScrollTo().assertTextContains("SYNTHETIC_QA")
        assertEquals("Light", context.getSharedPreferences("echoscript_preferences", 0).getString("appearance", null))
        screenshot("ui-settings-light.png")
        click("Dark")
        ui.onNodeWithText("Examiner identifier").performScrollTo().performTextReplacement("EXAMINER")
        closeSoftKeyboard()
        click("Save examiner")
        waitFor("Examiner identifier saved on this device.")
    }

    @Test fun sevenInterfaceWorkflowsFromFileIntakeThroughVerifiedTransfer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = EvidenceVault(context)
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it) } ?: context.cacheDir
        repeat(7) { run ->
            println("SYNTHETIC INTERFACE WORKFLOW ${run + 1}/7 START")
            if (run > 0) click("Cases", navigation = true)
            ui.onNodeWithText("Choose audio file").performScrollTo().assertIsDisplayed()
            if (run == 0) screenshot("ui-cases.png")
            val before = vault.listCases().cases.map { it.getString("caseId") }.toSet()
            val source = File(context.cacheDir, "SYNTHETIC UI RUN ${run + 1} NOT EVIDENCE.wav")
            context.assets.open("synthetic-speech.wav").use { input -> source.outputStream().use { input.copyTo(it) } }
            document(Intent.ACTION_OPEN_DOCUMENT, source) {
                click("Choose audio file")
                waitFor("Selected recording")
                click("Import selected audio", navigation = true)
                waitFor("Original preserved. Waveform and speech candidates are ready.")
            }
            val case = vault.listCases().cases.single { it.getString("caseId") !in before }
            val id = case.getString("caseId")
            assertEquals("9d17a3309b89246c14ecc5a7b9a0f9bf398de0d93824037ddaff4412b35af2bd", case.items("artifacts").first().getString("sha256"))
            ui.onNodeWithTag("waveform").performScrollTo().assertIsDisplayed()
            ui.onNodeWithTag("spectrogram").performScrollTo().assertIsDisplayed()
            if (run == 0) screenshot("ui-analysis.png")
            click("Render & transcribe")
            waitFor("Draft transcript saved. Review the words, timestamps and speaker groups.")
            if (run == 0) screenshot("ui-transcript.png")
            click("Edit and verify segment")
            ui.onNodeWithText("Examiner speaker label").performScrollTo().performTextReplacement("Synthetic examiner ${run + 1}")
            closeSoftKeyboard()
            if (run == 0) {
                click("Export", navigation = true)
                ui.onNodeWithText("Case package").performScrollTo().assertIsNotEnabled()
                click("Transcript", navigation = true)
                recreateWithoutPersistingDraft("Synthetic examiner 1")
                screenshot("ui-review-after-rotation.png")
                waitFor("Examiner speaker label", 30000)
                ui.onNodeWithText("Examiner speaker label").performScrollTo().assertTextContains("Synthetic examiner 1")
            }
            click("I listened to and verified this segment.")
            click("Save examiner revision")
            waitFor("This segment is marked as reviewed by the examiner.")
            ui.onNodeWithContentDescription("Audit").performClick()
            click("Verify all hashes and custody records")
            waitFor("Source and derivative hashes, authenticated decryption and audit linkage agree. Independent custody and signatures require separate verification.")
            click("Export", navigation = true)
            click("Create device-signed checkpoint")
            ui.waitUntil(timeoutMillis = 30000) { ui.onAllNodesWithText("Device checkpoint saved.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val signed = vault.listCases().cases.single { it.getString("caseId") == id }
            val fingerprint = signed.items("artifacts").last { it.getJSONObject("operation").getString("algorithm") == org.echoscript.security.SignedCheckpoint.ALGORITHM }.operationParameters().getString("keySha256")
            click("I understand and will protect exported files.")
            val testPassword = "SYNTHETIC test transfer password 42"
            ui.onNodeWithText("New transfer passphrase").performScrollTo().performTextReplacement(testPassword)
            ui.onNodeWithText("Confirm transfer passphrase").performScrollTo().performTextReplacement("SYNTHETIC mismatched password")
            closeSoftKeyboard()
            ui.onNodeWithText("Protected case package").performScrollTo().assertIsNotEnabled()
            ui.onNodeWithText("Confirm transfer passphrase").performScrollTo().performTextReplacement(testPassword)
            closeSoftKeyboard()
            if (run == 0) {
                ui.onNodeWithContentDescription("Show New transfer passphrase").performScrollTo().performClick()
                ui.onNodeWithContentDescription("Hide New transfer passphrase").assertIsDisplayed()
                recreateWithoutPersistingDraft(testPassword)
                ui.onNodeWithContentDescription("Show New transfer passphrase").performScrollTo().assertIsDisplayed()
            }
            val protectedArchive = File(context.cacheDir, "synthetic-ui-protected-run-${run + 1}.echocase.enc")
            document(Intent.ACTION_CREATE_DOCUMENT, protectedArchive) {
                click("Protected case package")
                ui.waitUntil(timeoutMillis = 30000) { protectedArchive.exists() && protectedArchive.length() > 100 && ui.onAllNodesWithText("Export saved. SHA-256 ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
            ui.runOnIdle {
                val model = androidx.lifecycle.ViewModelProvider(ui.activity)[VaultModel::class.java]
                assertEquals("", model.exportPassphrase); assertEquals("", model.exportPassphraseConfirmation)
            }
            if (run == 0) {
                ui.onNodeWithText("Protect a case transfer").performScrollTo()
                screenshot("ui-protected-export.png")
            }
            val protectedHash = EvidenceVault.sha(protectedArchive.readBytes())
            val archive = File(context.cacheDir, "synthetic-ui-run-${run + 1}.echocase")
            document(Intent.ACTION_CREATE_DOCUMENT, archive) {
                click("Case package")
                ui.waitUntil(timeoutMillis = 30000) { archive.exists() && archive.length() > 100 && ui.onAllNodesWithText("Export saved. SHA-256 ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
            assertTrue(File(context.filesDir, "EvidenceVault/cases/$id").deleteRecursively())
            click("Cases", navigation = true)
            click("Transfer case")
            ui.onNodeWithText("Independently received SHA-256 (optional)").performScrollTo().performTextReplacement(protectedHash)
            ui.onNodeWithText("Transfer passphrase (protected packages)").performScrollTo().performTextReplacement(testPassword)
            closeSoftKeyboard()
            document(Intent.ACTION_OPEN_DOCUMENT, protectedArchive) {
                click("Choose .echocase package")
                waitFor("Case transferred into the encrypted vault. The independent package hash matched.")
            }
            val imported = vault.listCases().cases.single { it.getString("caseId") == id }
            assertTrue(vault.verify(imported).contains("agree"))
            assertEquals(fingerprint, vault.verifyCheckpoints(imported).single().keySha256)
            ui.runOnIdle { assertEquals("", androidx.lifecycle.ViewModelProvider(ui.activity)[VaultModel::class.java].transferPassphrase) }
            assertEquals("HUMAN_VERIFIED", OfflineWorkflow(context, vault).readTranscript(imported, checkNotNull(imported.latestTranscript())).items("segments").first().getString("verification"))
            archive.copyTo(File(output, "synthetic-ui-run-${run + 1}.echocase"), overwrite = true)
            protectedArchive.copyTo(File(output, "synthetic-ui-protected-run-${run + 1}.echocase.enc"), overwrite = true)
            File(output, "synthetic-ui-protected-run-${run + 1}.key-sha256.txt").writeText(fingerprint + "\n")
            assertTrue(File(context.filesDir, "EvidenceVault/cases/$id").deleteRecursively())
            archive.delete(); protectedArchive.delete(); source.delete()
            println("SYNTHETIC INTERFACE WORKFLOW ${run + 1}/7 PASS")
        }
    }
}

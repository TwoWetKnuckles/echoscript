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
import androidx.compose.ui.semantics.SemanticsProperties
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
            screenshot("ui-selected-ready.png")
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

    @Test fun emptyPickerResultIsVisibleAndDoesNotCreateACase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val before = EvidenceVault(context).listCases().cases.map { it.getString("caseId") }.toSet()
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent()))
            click("Choose audio file")
            val message = "The file picker returned no recording. Try Choose with another file picker."
            waitFor(message)
            ui.onNodeWithText(message).assertIsDisplayed()
            assertEquals(before, EvidenceVault(context).listCases().cases.map { it.getString("caseId") }.toSet())
            click("Dismiss", navigation = true)
        } finally { Intents.release() }
    }

    @Test fun unreadableSelectedFileOffersRetryAndClear() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "SYNTHETIC-UNREADABLE.wav")
        source.writeText("synthetic")
        try {
            document(Intent.ACTION_OPEN_DOCUMENT, source) {
                click("Choose audio file")
                waitFor("Selected recording")
                assertTrue(source.delete())
                click("Import selected audio", navigation = true)
                waitFor("Retry import")
                ui.onNodeWithText("Retry import").assertIsDisplayed().assertIsEnabled()
                click("Clear", navigation = true)
                ui.onNodeWithText("Selected recording").assertDoesNotExist()
            }
        } finally { source.delete() }
    }

    @Test fun planPreviewsPreserveBasicWorkAndProtectedExport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = EvidenceVault(context)
        val before = vault.listCases().cases.map { it.getString("caseId") }.toSet()
        click("Load synthetic speech sample")
        waitFor("Original preserved. Waveform and speech candidates are ready.")
        val case = vault.listCases().cases.single { it.getString("caseId") !in before }
        val id = case.getString("caseId")
        val sourceHash = case.items("artifacts").first().getString("sha256")
        val archive = File(context.cacheDir, "synthetic-free-preview.echocase.enc")
        try {
            ui.onNodeWithContentDescription("Settings").performClick()
            click("Free preview")
            click("Analyze", navigation = true)
            click("Scan full recording spectrum · Premium")
            waitFor("Full-recording spectrum is Premium in the Free preview. Switch previews in Settings; no payment is set up.")
            var saved = vault.listCases().cases.single { it.getString("caseId") == id }
            assertFalse(saved.items("artifacts").any { it.getJSONObject("operation").getString("algorithm") == "continuous-overview-stft" })
            ui.onNodeWithText("Selection start ms").performScrollTo().performTextReplacement("0")
            ui.onNodeWithText("Selection end ms").performScrollTo().performTextReplacement("1000")
            closeSoftKeyboard()
            click("Create clip from selection")
            waitFor("Effect plan rendered. Earlier audio and settings remain in revision history.")
            saved = vault.listCases().cases.single { it.getString("caseId") == id }
            assertTrue(saved.items("artifacts").any { it.getString("type") == "ENHANCED_AUDIO" })
            assertEquals(sourceHash, saved.items("artifacts").first().getString("sha256"))
            click("Export", navigation = true)
            click("I understand and will protect exported files.")
            ui.onNodeWithText("New transfer passphrase").performScrollTo().performTextReplacement("SYNTHETIC test password 42")
            ui.onNodeWithText("Confirm transfer passphrase").performScrollTo().performTextReplacement("SYNTHETIC test password 42")
            closeSoftKeyboard()
            document(Intent.ACTION_CREATE_DOCUMENT, archive) {
                click("Protected case package")
                ui.waitUntil(timeoutMillis = 30000) { archive.exists() && archive.length() > 100 && ui.onAllNodesWithText("Export saved. SHA-256 ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
            ui.onNodeWithContentDescription("Settings").performClick()
            click("One-time preview")
            click("Analyze", navigation = true)
            click("Scan full recording spectrum")
            waitFor("Full-recording spectral overview saved. Use detailed inspection for finer time and frequency resolution.")
            saved = vault.listCases().cases.single { it.getString("caseId") == id }
            assertTrue(saved.items("artifacts").any { it.getJSONObject("operation").getString("algorithm") == "continuous-overview-stft" })
            assertEquals(sourceHash, saved.items("artifacts").first().getString("sha256"))
            assertTrue(vault.verify(saved).contains("agree"))
        } finally {
            archive.delete()
            assertTrue(File(context.filesDir,"EvidenceVault/cases/$id").deleteRecursively())
        }
    }

    @Test fun detailedSpectrumInspectsAndZoomsWithoutChangingOriginal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = EvidenceVault(context)
        val before = vault.listCases().cases.map { it.getString("caseId") }.toSet()
        click("Load synthetic speech sample")
        waitFor("Original preserved. Waveform and speech candidates are ready.")
        val case = vault.listCases().cases.single { it.getString("caseId") !in before }
        val sourceHash = case.items("artifacts").first().getString("sha256")
        try {
            click("Scan full recording spectrum")
            waitFor("Full-recording spectral overview saved. Use detailed inspection for finer time and frequency resolution.")
            ui.onNodeWithTag("spectrogram").performScrollTo().assertTextContains("overlapping windows cover the recording", substring = true)
            val fullSaved = vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }
            assertTrue(fullSaved.items("artifacts").any { it.getJSONObject("operation").getString("algorithm") == "continuous-overview-stft" })
            ui.onNodeWithText("Selection start ms").performScrollTo().performTextReplacement("0")
            ui.onNodeWithText("Selection end ms").performScrollTo().performTextReplacement("5000")
            closeSoftKeyboard()
            click("Open detailed spectrum")
            click("256")
            click("64 samples")
            click("Inspect selected interval")
            waitFor("Choose a shorter selection or a larger hop for this sample rate and channel count")
            ui.onNodeWithText("Selection start ms").performScrollTo().performTextReplacement("200")
            ui.onNodeWithText("Selection end ms").performScrollTo().performTextReplacement("1000")
            closeSoftKeyboard()
            click("1024")
            click("512 samples")
            click("Inspect selected interval")
            waitFor("Detailed spectrum saved for the selected interval.")
            ui.onNodeWithTag("detailed-spectrum").performScrollTo().assertIsDisplayed()
            var saved = vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }
            val first = saved.items("artifacts").last { it.getJSONObject("operation").getString("algorithm") == "windowed-stft-inspection" }
            assertEquals(200L, first.operationParameters().getLong("startMs"))
            assertEquals(1000L, first.operationParameters().getLong("endMs"))
            assertEquals(1024, first.operationParameters().getInt("fftSize"))
            assertEquals(512, first.operationParameters().getInt("hopSize"))
            click("Zoom in")
            ui.waitUntil(timeoutMillis = 120000) {
                vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }.items("artifacts")
                    .count { it.getJSONObject("operation").getString("algorithm") == "windowed-stft-inspection" } == 2
            }
            saved = vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }
            val zoomed = saved.items("artifacts").last { it.getJSONObject("operation").getString("algorithm") == "windowed-stft-inspection" }
            assertEquals(400L, zoomed.operationParameters().getLong("startMs"))
            assertEquals(800L, zoomed.operationParameters().getLong("endMs"))
            ui.waitUntil(timeoutMillis = 30000) { ui.onAllNodesWithText("400–800 ms", substring = true).fetchSemanticsNodes().isNotEmpty() }
            click("Select a frequency band by dragging on the spectrum")
            ui.onNodeWithTag("detailed-spectrum").performScrollTo().performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width * .25f, height * .55f), androidx.compose.ui.geometry.Offset(width * .75f, height * .85f), 600)
            }
            waitFor("Frequency isolation enabled in draft. Render to apply the selected band.")
            click("Measure selected noise floor")
            waitFor("Noise-floor measurement saved. Review the threshold before rendering.")
            ui.waitUntil(timeoutMillis = 30000) { ui.onAllNodesWithText("Measured gate threshold:", substring = true).fetchSemanticsNodes().isNotEmpty() }
            saved = vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }
            val firstNoise = saved.items("artifacts").last { it.getJSONObject("operation").getString("algorithm") == "broadband-noise-floor-estimate" }
            ui.onNodeWithText("Noise gate threshold dBFS (−90 to −10)").performScrollTo().performTextReplacement("-30")
            closeSoftKeyboard()
            ui.onAllNodesWithText("Measured gate threshold:", substring = true).assertCountEquals(0)
            ui.activityRule.scenario.recreate()
            waitFor("Measure selected noise floor")
            ui.onAllNodesWithText("Measured gate threshold:", substring = true).assertCountEquals(0)
            ui.onNodeWithText("Noise gate threshold dBFS (−90 to −10)").performScrollTo().assertTextContains("-30", substring = true)
            click("Measure selected noise floor")
            ui.waitUntil(timeoutMillis = 120000) {
                vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }.items("artifacts")
                    .count { it.getJSONObject("operation").getString("algorithm") == "broadband-noise-floor-estimate" } == 2
            }
            ui.waitUntil(timeoutMillis = 30000) { ui.onAllNodesWithText("Measured gate threshold:", substring = true).fetchSemanticsNodes().isNotEmpty() }
            saved = vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }
            val noise = saved.items("artifacts").last { it.getJSONObject("operation").getString("algorithm") == "broadband-noise-floor-estimate" }
            assertNotEquals(firstNoise.getString("id"), noise.getString("id"))
            click("Render enhanced revision")
            waitFor("Effect plan rendered. Earlier audio and settings remain in revision history.")
            saved = vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }
            val rendered = saved.items("artifacts").last { it.getString("type") == "ENHANCED_AUDIO" }
            val params = rendered.operationParameters(); val rack = params.getJSONObject("effectRack")
            assertTrue("drag start was ${params.getLong("startMs")} ms",params.getLong("startMs") in 495L..505L)
            assertTrue("drag end was ${params.getLong("endMs")} ms",params.getLong("endMs") in 695L..705L)
            assertTrue("band low was ${rack.getDouble("bandLowHz")} Hz",rack.getDouble("bandLowHz") in 1700.0..2000.0)
            assertTrue("band high was ${rack.getDouble("bandHighHz")} Hz",rack.getDouble("bandHighHz") in 5300.0..5700.0)
            assertTrue(rack.getJSONArray("effects").let { effects -> (0 until effects.length()).map(effects::getJSONObject).any { it.getString("id") == "frequency" && it.getBoolean("enabled") } })
            assertTrue(rack.getJSONArray("effects").let { effects -> (0 until effects.length()).map(effects::getJSONObject).any { it.getString("id") == "noise" && it.getBoolean("enabled") } })
            assertEquals(noise.getString("id"),rack.getJSONObject("noiseReference").getString("artifactId"))
            assertEquals(noise.getString("sha256"),rack.getJSONObject("noiseReference").getString("sha256"))
            assertEquals(noise.getString("id"),rendered.getJSONArray("parents").getString(1))
            click("Show A/B comparison")
            click("Revision 2")
            click("Compare waveform and clipping")
            waitFor("Comparison is ready. Review the overlay and near-full-scale counts.")
            ui.onNodeWithTag("clipping-comparison").performScrollTo().assertIsDisplayed()
            saved = vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }
            assertEquals(rendered.getString("id"),saved.getString("activeArtifact"))
            assertEquals(sourceHash, saved.items("artifacts").first().getString("sha256"))
            assertTrue(vault.verify(saved).contains("agree"))
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it).apply { mkdirs() } } ?: context.cacheDir
            val archive = File(output, "synthetic-detail-spectrum.echocase")
            val archiveHash = archive.outputStream().use { vault.exportPackage(saved, it, "SYNTHETIC_QA") }
            val folder = File(context.filesDir,"EvidenceVault/cases/${case.getString("caseId")}")
            assertTrue(folder.deleteRecursively())
            val reopened = archive.inputStream().use { vault.importPackage(it, "SYNTHETIC_QA", archiveHash, { false }, {}) }
            assertTrue(vault.verify(reopened).contains("agree"))
            assertTrue(reopened.items("artifacts").any { it.getJSONObject("operation").getString("algorithm") == "windowed-stft-inspection" })
            assertTrue(reopened.items("artifacts").any { it.getString("id") == noise.getString("id") && it.getString("sha256") == noise.getString("sha256") })
            assertEquals(sourceHash, reopened.items("artifacts").first().getString("sha256"))
        } finally { assertTrue(File(context.filesDir,"EvidenceVault/cases/${case.getString("caseId")}").deleteRecursively()) }
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
            fun selectionValue(label:String) = ui.onNodeWithText(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text.toLong()
            val firstStart=selectionValue("Selection start ms"); val firstEnd=selectionValue("Selection end ms")
            ui.onNodeWithTag("waveform").performScrollTo().performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width * .2f, height * .5f), androidx.compose.ui.geometry.Offset(width * .1f, height * .5f), 600)
            }
            assertTrue("Dragging the left handle should extend the selection",selectionValue("Selection start ms") < firstStart)
            assertEquals("The opposite selection edge must remain fixed",firstEnd,selectionValue("Selection end ms"))
            screenshot("ui-workbench-timeline.png")
            ui.onNodeWithTag("effects-overview").performScrollTo().assertIsDisplayed()
            screenshot("ui-effects-overview.png")
            click("Filters")
            click("Low hum isolation")
            ui.onNodeWithContentDescription("Low hum isolation enabled").performScrollTo().assertIsDisplayed()
            screenshot("ui-workbench-effects.png")
            click("Restore")
            click("Denoise")
            ui.onNodeWithContentDescription("Denoise enabled").performScrollTo().assertIsDisplayed()
            click("Gain & EQ")
            ui.onNodeWithContentDescription("Preamp / gain enabled").performScrollTo().performClick()
            ui.onNodeWithText("Preamp / gain strength (−100 to 100)").performScrollTo().performTextReplacement("101")
            ui.onNodeWithText("Enter a whole number from −100 to 100. The last valid value remains in the draft.").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Preamp / gain strength (−100 to 100)").performTextReplacement("0")
            closeSoftKeyboard()
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
            if (run == 0) {
                click("Edit and verify segment")
                val note = "SYNTHETIC_NOTE_SEARCH separate from speech"
                ui.onNodeWithText("Examiner note (not spoken words)").performScrollTo().performTextReplacement(note)
                closeSoftKeyboard()
                recreateWithoutPersistingDraft(note)
                ui.onNodeWithText("Examiner note (not spoken words)").performScrollTo().assertTextContains(note)
                click("Save examiner revision")
                waitFor("Edits saved as a separate revision.")
                val annotated = vault.listCases().cases.single { it.getString("caseId") == id }
                val saved = OfflineWorkflow(context, vault).readTranscript(annotated, checkNotNull(annotated.latestTranscript()))
                assertEquals(note, saved.items("segments").first().getString("examinerNote"))
                assertEquals("HUMAN_VERIFIED", saved.items("segments").first().getString("verification"))
                ui.onNodeWithText("Find transcript text or notes").performScrollTo().performTextReplacement("SYNTHETIC_NOTE_SEARCH")
                closeSoftKeyboard()
                ui.onNodeWithText("1: Note: SYNTHETIC_NOTE_SEARCH separate from speech").performScrollTo().assertIsDisplayed()
                ui.onNodeWithText("Find transcript text or notes").performScrollTo().performTextReplacement("")
                closeSoftKeyboard()
            }
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

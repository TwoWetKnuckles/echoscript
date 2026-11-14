package org.echoscript.app

import android.app.Application
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AudioIntakeTest {
    @Test fun developmentCandidateUsesSeparatePackageAndVisibleTestLabel() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("org.echoscript.app.candidate06r2", app.packageName)
        assertNotEquals("org.echoscript.app.candidate06", app.packageName)
        assertEquals("EchoScript 0.6 Candidate 2", app.applicationInfo.loadLabel(app.packageManager).toString())
        if (InstrumentationRegistry.getArguments().getString("verifyCoexistence") == "true") {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(device.executeShellCommand("pm path org.echoscript.app.candidate06").trim().startsWith("package:"))
            assertTrue(device.executeShellCommand("pm path org.echoscript.app.candidate06r2").trim().startsWith("package:"))
            assertEquals("SYNTHETIC_OLD_PREVIEW_DATA", device.executeShellCommand("run-as org.echoscript.app.candidate06 cat files/synthetic-preview-isolation.txt").trim())
        }
    }

    @Test fun providerResultSupportsDataAndSingleClipButRejectsAmbiguousSelection() {
        val uri = Uri.parse("content://synthetic.test/document/audio")
        assertEquals(uri, selectedAudioUri(Intent().setData(uri)))
        val clip = ClipData.newRawUri("Synthetic", uri)
        assertEquals(uri, selectedAudioUri(Intent().apply { clipData = clip }))
        assertEquals(uri, selectedAudioUri(Intent().putExtra(Intent.EXTRA_STREAM, uri)))
        assertEquals(uri, selectedAudioUri(Intent().setData(uri).apply { clipData = clip }))
        assertEquals(uri, selectedAudioUri(Intent().setData(uri).putExtra(Intent.EXTRA_STREAM, uri).apply { clipData = clip }))
        assertThrows(IllegalArgumentException::class.java) {
            selectedAudioUri(Intent().setData(Uri.parse("content://synthetic.test/document/other")).apply { clipData = clip })
        }
        assertThrows(IllegalArgumentException::class.java) {
            selectedAudioUri(Intent().setData(uri).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://synthetic.test/document/other")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            selectedAudioUri(Intent().putExtra(Intent.EXTRA_STREAM, Uri.parse("content://synthetic.test/document/other")).apply { clipData = clip })
        }
        assertThrows(IllegalArgumentException::class.java) {
            selectedAudioUri(Intent().putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri, Uri.parse("content://synthetic.test/document/second"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            selectedAudioUri(Intent().putExtra(Intent.EXTRA_STREAM, "not-a-document-uri"))
        }
        clip.addItem(ClipData.Item(Uri.parse("content://synthetic.test/document/second")))
        assertThrows(IllegalArgumentException::class.java) { selectedAudioUri(Intent().apply { clipData = clip }) }
        assertNull(selectedAudioUri(Intent()))
        assertEquals("*/*", audioPickerIntent(Intent.ACTION_OPEN_DOCUMENT).type)
        assertTrue(audioPickerIntent(Intent.ACTION_GET_CONTENT).hasCategory(Intent.CATEGORY_OPENABLE))
    }

    @Test fun interruptedSelectionIsReportedWithoutSavingItsDocumentPath() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val preferences = app.getSharedPreferences("echoscript_workspace", 0)
        val sensitivePath = "SYNTHETIC-PRIVATE-DOCUMENT-NAME.wav"
        val uri = Uri.parse("content://synthetic.test/document/$sensitivePath")
        var first: VaultModel? = null
        var reopened: VaultModel? = null
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val model = VaultModel(app)
                first = model
                model.selectAudio(uri, "")
                assertTrue(model.state.value.audioSelected)
                reopened = VaultModel(app)
            }
            val deadline = System.nanoTime() + 30_000_000_000L
            while ((reopened?.state?.value?.ready != true || first?.state?.value?.ready != true) && System.nanoTime() < deadline) Thread.sleep(20)
            val restored = checkNotNull(reopened).state.value
            assertTrue(restored.ready)
            assertTrue(checkNotNull(first).state.value.message.contains("Tap Import selected audio"))
            assertFalse(restored.audioSelected)
            assertTrue(restored.recoveryWarnings.any { it.contains("selection or import was interrupted") })
            assertFalse(preferences.all.values.any { it.toString().contains(sensitivePath) })
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                first?.let { model -> model.clearSelectedAudio(); model.viewModelScope.cancel() }
                reopened?.viewModelScope?.cancel()
            }
        }
    }

    @Test fun pickerInterruptedBeforeResultIsReportedAndCancellationClearsOnlyItsMarker() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val preferences = app.getSharedPreferences("echoscript_workspace", 0)
        var first: VaultModel? = null
        var reopened: VaultModel? = null
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                preferences.edit().remove("pendingAudioSelection").commit()
                val model = VaultModel(app); first = model
                model.markAudioPickerOpen()
                assertTrue(preferences.getBoolean("pendingAudioSelection", false))
                reopened = VaultModel(app)
            }
            val deadline = System.nanoTime() + 30_000_000_000L
            while ((first?.state?.value?.ready != true || reopened?.state?.value?.ready != true) && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(checkNotNull(reopened).state.value.recoveryWarnings.contains(INTERRUPTED_AUDIO_SELECTION))
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val model = checkNotNull(first)
                model.pickerNotice("No recording was selected.")
                assertFalse(preferences.getBoolean("pendingAudioSelection", false))
                model.selectAudio(Uri.parse("content://synthetic.test/document/previous"), "")
                model.markAudioPickerOpen()
                model.pickerNotice("The replacement picker was cancelled.")
                assertTrue(model.state.value.audioSelected)
                assertTrue(preferences.getBoolean("pendingAudioSelection", false))
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                first?.let { it.clearSelectedAudio(); it.viewModelScope.cancel() }
                reopened?.viewModelScope?.cancel()
                preferences.edit().remove("pendingAudioSelection").commit()
            }
        }
    }

    @Test fun memoryFailureReportsProblemAndKeepsSelectionForRetry() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        lateinit var model: VaultModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model = VaultModel(app) }
        try {
            val readyDeadline = System.nanoTime() + 30_000_000_000L
            while (!model.state.value.ready && System.nanoTime() < readyDeadline) Thread.sleep(20)
            assertTrue(model.state.value.ready)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model.selectAudio(Uri.parse("content://synthetic.test/document/memory-test.wav"), "")
                model.runJob("Synthetic memory failure") { throw OutOfMemoryError("synthetic test") }
            }
            val deadline = System.nanoTime() + 30_000_000_000L
            while ((!model.state.value.failed || model.state.value.job != null) && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(model.state.value.failed)
            assertNull(model.state.value.job)
            assertTrue(model.state.value.message.contains("memory"))
            assertTrue(model.state.value.audioSelected)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.clearSelectedAudio(); model.viewModelScope.cancel() }
        }
    }

    @Test fun selectionDeliveredBeforeVaultReadyIsImportedAndUnreadableSelectionIsVisible() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val resolver = app.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ECHOSCRIPT-SYNTHETIC-EARLY.bin")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val caseRoot = File(app.filesDir, "EvidenceVault/cases")
        val existingFolders = caseRoot.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        var heldModel: VaultModel? = null
        try {
            app.assets.open("synthetic-speech.wav").use { input -> checkNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            lateinit var model: VaultModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                // Main is held here, so initialization cannot publish ready yet.
                model = VaultModel(app)
                heldModel = model
                assertFalse(model.state.value.ready)
                model.selectAudio(uri, "Synthetic early picker result")
                assertTrue(model.state.value.audioSelected)
                model.importSelectedAudio("SYNTHETIC_QA")
                assertTrue(model.state.value.importPending)
            }
            val deadline = System.nanoTime() + 120_000_000_000L
            while ((model.state.value.importPending || !model.state.value.ready || model.state.value.job != null ||
                        (model.state.value.audioSelected && !model.state.value.failed)) && System.nanoTime() < deadline) Thread.sleep(20)
            val state = model.state.value
            assertFalse(state.message, state.failed)
            assertFalse(state.audioSelected)
            assertEquals("ECHOSCRIPT-SYNTHETIC-EARLY.bin", state.importName)
            val case = state.cases.single { it.getString("caseId") == state.selected }
            assertEquals("9d17a3309b89246c14ecc5a7b9a0f9bf398de0d93824037ddaff4412b35af2bd", case.items("artifacts").first { it.getString("type") == "SOURCE" }.getString("sha256"))
            assertEquals(state.selected, state.importedCaseId)
            assertNotNull(state.analysis)
            assertTrue(File(app.filesDir, "EvidenceVault/cases/${state.selected}").deleteRecursively())
            resolver.delete(uri, null, null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model.selectAudio(uri, "Synthetic deleted document")
                model.importSelectedAudio("SYNTHETIC_QA")
            }
            val failedDeadline = System.nanoTime() + 15_000_000_000L
            while ((model.state.value.importPending || model.state.value.job != null) && System.nanoTime() < failedDeadline) Thread.sleep(20)
            assertTrue("Unreadable selection must not silently return to an idle screen", model.state.value.failed)
            assertTrue("An unreadable selection remains available for retry or clearing", model.state.value.audioSelected)
            assertTrue(model.state.value.message.isNotBlank())
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.clearSelectedAudio() }
            assertFalse(model.state.value.audioSelected)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { heldModel?.cancel(); heldModel?.viewModelScope?.cancel() }
            resolver.delete(uri, null, null)
            caseRoot.listFiles().orEmpty().filter { it.name !in existingFolders }.forEach { it.deleteRecursively() }
        }
    }
}

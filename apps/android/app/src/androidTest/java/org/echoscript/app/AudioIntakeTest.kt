package org.echoscript.app

import android.app.Application
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AudioIntakeTest {
    @Test fun providerResultSupportsDataAndSingleClipButRejectsAmbiguousSelection() {
        val uri = Uri.parse("content://synthetic.test/document/audio")
        assertEquals(uri, selectedAudioUri(Intent().setData(uri)))
        val clip = ClipData.newRawUri("Synthetic", uri)
        assertEquals(uri, selectedAudioUri(Intent().apply { clipData = clip }))
        assertEquals(uri, selectedAudioUri(Intent().putExtra(Intent.EXTRA_STREAM, uri)))
        assertEquals(uri, selectedAudioUri(Intent().setData(uri).apply { clipData = clip }))
        assertThrows(IllegalArgumentException::class.java) {
            selectedAudioUri(Intent().setData(Uri.parse("content://synthetic.test/document/other")).apply { clipData = clip })
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
        lateinit var reopened: VaultModel
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val model = VaultModel(app)
                first = model
                model.selectAudio(uri, "")
                assertTrue(model.state.value.audioSelected)
                reopened = VaultModel(app)
            }
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!reopened.state.value.ready && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(reopened.state.value.ready)
            assertFalse(reopened.state.value.audioSelected)
            assertTrue(reopened.state.value.recoveryWarnings.any { it.contains("selection or import was interrupted") })
            assertFalse(preferences.all.values.any { it.toString().contains(sensitivePath) })
        } finally {
            first?.let { model -> InstrumentationRegistry.getInstrumentation().runOnMainSync { model.clearSelectedAudio() } }
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
        try {
            app.assets.open("synthetic-speech.wav").use { input -> checkNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            lateinit var model: VaultModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                // Main is held here, so initialization cannot publish ready yet.
                model = VaultModel(app)
                assertFalse(model.state.value.ready)
                model.selectAudio(uri, "Synthetic early picker result")
                assertTrue(model.state.value.audioSelected)
                model.importSelectedAudio("SYNTHETIC_QA")
                assertTrue(model.state.value.importPending)
            }
            val deadline = System.nanoTime() + 120_000_000_000L
            while ((model.state.value.importPending || !model.state.value.ready || model.state.value.job != null) && System.nanoTime() < deadline) Thread.sleep(20)
            val state = model.state.value
            assertFalse(state.failed)
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
        } finally { resolver.delete(uri, null, null) }
    }
}

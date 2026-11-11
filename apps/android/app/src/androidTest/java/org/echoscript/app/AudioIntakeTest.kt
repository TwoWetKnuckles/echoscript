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
        clip.addItem(ClipData.Item(Uri.parse("content://synthetic.test/document/second")))
        assertThrows(IllegalArgumentException::class.java) { selectedAudioUri(Intent().apply { clipData = clip }) }
        assertNull(selectedAudioUri(Intent()))
        assertEquals("*/*", audioPickerIntent(Intent.ACTION_OPEN_DOCUMENT).type)
        assertTrue(audioPickerIntent(Intent.ACTION_GET_CONTENT).hasCategory(Intent.CATEGORY_OPENABLE))
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
                model.importAudio(uri, "Synthetic early picker result", "SYNTHETIC_QA")
                assertTrue(model.state.value.importPending)
            }
            val deadline = System.nanoTime() + 120_000_000_000L
            while ((model.state.value.importPending || !model.state.value.ready || model.state.value.job != null) && System.nanoTime() < deadline) Thread.sleep(20)
            val state = model.state.value
            assertFalse(state.failed)
            assertEquals("ECHOSCRIPT-SYNTHETIC-EARLY.bin", state.importName)
            val case = state.cases.single { it.getString("caseId") == state.selected }
            assertEquals("9d17a3309b89246c14ecc5a7b9a0f9bf398de0d93824037ddaff4412b35af2bd", case.items("artifacts").first { it.getString("type") == "SOURCE" }.getString("sha256"))
            assertEquals(state.selected, state.importedCaseId)
            assertNotNull(state.analysis)
            assertTrue(File(app.filesDir, "EvidenceVault/cases/${state.selected}").deleteRecursively())
            resolver.delete(uri, null, null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.importAudio(uri, "Synthetic deleted document", "SYNTHETIC_QA") }
            val failedDeadline = System.nanoTime() + 15_000_000_000L
            while ((model.state.value.importPending || model.state.value.job != null) && System.nanoTime() < failedDeadline) Thread.sleep(20)
            assertTrue("Unreadable selection must not silently return to an idle screen", model.state.value.failed)
            assertTrue(model.state.value.message.isNotBlank())
        } finally { resolver.delete(uri, null, null) }
    }
}

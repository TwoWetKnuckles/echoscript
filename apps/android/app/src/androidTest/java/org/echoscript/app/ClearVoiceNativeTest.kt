package org.echoscript.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ClearVoiceNativeTest {
    @Test fun actualBundledModelPreservesSourceAndTimingAndProducesRepeatableProtectedRevisions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = EvidenceVault(context); val workflow = OfflineWorkflow(context, vault)
        val case = context.assets.open("synthetic-speech.wav").use { vault.importSource(it, "SYNTHETIC ClearVoice.wav", "Synthetic ClearVoice test", "SYNTHETIC_QA") }
        val id = case.getString("caseId"); val source = case.activeAudio()
        try {
            assertEquals("1", android.system.Os.getenv("ORT_DISABLE_TELEMETRY"))
            val uid = android.os.Process.myUid()
            val sentBefore = android.net.TrafficStats.getUidTxBytes(uid)
            val receivedBefore = android.net.TrafficStats.getUidRxBytes(uid)
            assertTrue("UID network accounting must be available", sentBefore >= 0 && receivedBefore >= 0)
            val decoded = workflow.prepareAudio(case, "SYNTHETIC_QA", { false }, {})
            val beforeCancel = case.getJSONArray("artifacts").length()
            assertThrows(InterruptedException::class.java) { ClearVoiceNative(context, vault, workflow).enhance(case, "SYNTHETIC_QA", "AFTER", { true }, {}) }
            assertEquals(beforeCancel, case.getJSONArray("artifacts").length())
            val first = ClearVoiceNative(context, vault, workflow).enhance(case, "SYNTHETIC_QA", "AFTER", { false }, {})
            val parameters = first.operationParameters()
            assertEquals(CLEARVOICE_ONNX_HASH, parameters.getString("modelSha256"))
            assertEquals("IN_PROCESS_ONLY", parameters.getString("transport"))
            assertEquals("DISABLED_BEFORE_INITIALIZATION", parameters.getString("runtimeTelemetry"))
            assertEquals(88400L, parameters.getLong("frames"))
            assertEquals(16000, parameters.getInt("sampleRate"))
            assertEquals(1, parameters.getInt("channels"))
            assertEquals(decoded.getString("id"), first.getJSONArray("parents").getString(0))
            vault.open(id, first.getString("id")).use { input -> WaveReader(input, first.getLong("bytes")).use { wave ->
                assertEquals(88400L, wave.frames); assertEquals(PcmFormat(16000,1), wave.format)
            } }
            vault.selectRevision(case, "SYNTHETIC_QA", decoded.getString("id"))
            val second = ClearVoiceNative(context, vault, workflow).enhance(case, "SYNTHETIC_QA", "BEFORE", { false }, {})
            assertEquals(first.getString("sha256"), second.getString("sha256"))
            assertEquals("Local enhancement sent network bytes", sentBefore, android.net.TrafficStats.getUidTxBytes(uid))
            assertEquals("Local enhancement received network bytes", receivedBefore, android.net.TrafficStats.getUidRxBytes(uid))
            assertEquals(source.getString("sha256"), case.artifact(source.getString("id")).getString("sha256"))
            assertTrue(vault.verify(case).contains("agree"))
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it).apply { mkdirs() } }
            if (output != null) {
                File(output,"synthetic-clearvoice.echocase").outputStream().use { vault.exportPackage(case,it,"SYNTHETIC_QA") }
            }
        } finally { assertTrue(File(context.filesDir,"EvidenceVault/cases/$id").deleteRecursively()) }
    }
}

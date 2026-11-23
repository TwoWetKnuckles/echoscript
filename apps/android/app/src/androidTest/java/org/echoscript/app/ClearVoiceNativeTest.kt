package org.echoscript.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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
            val baseId = decoded.getString("id")
            val recipe = JSONObject().put("baseId", baseId).put("rack", EffectRack().json()).put("regions", JSONArray()).put("limit", false)
            val settings = listOf(RenderSettings(limit = false, workbenchPlan = recipe.toString()))
            val pipeline = LocalRenderPipeline(context, vault, workflow)
            val first = pipeline.render(case, "SYNTHETIC_QA", baseId, settings, ClearVoicePlacement.BEFORE, { false }, {})
            val neural = case.artifact(first.getJSONArray("parents").getString(0))
            assertEquals("clearvoice-frcrn-onnx-context-render", neural.getJSONObject("operation").getString("algorithm"))
            val parameters = neural.operationParameters()
            assertEquals(CLEARVOICE_ONNX_HASH, parameters.getString("modelSha256"))
            assertEquals("IN_PROCESS_ONLY", parameters.getString("transport"))
            assertEquals("DISABLED_BEFORE_INITIALIZATION", parameters.getString("runtimeTelemetry"))
            assertFalse(parameters.getBoolean("cpuArenaAllocator"))
            assertFalse(parameters.getBoolean("memoryPatternOptimization"))
            assertEquals(88400L, parameters.getLong("frames"))
            assertEquals(16000, parameters.getInt("sampleRate"))
            assertEquals(1, parameters.getInt("channels"))
            assertEquals(baseId, neural.getJSONArray("parents").getString(0))
            assertEquals(baseId, first.operationParameters().getJSONObject("workbenchPlan").getString("baseId"))
            assertEquals(ClearVoicePlacement.BEFORE, clearVoiceFromRecipe(first.operationParameters().getJSONObject("workbenchPlan")))
            vault.open(id, first.getString("id")).use { input -> WaveReader(input, first.getLong("bytes")).use { wave ->
                assertEquals(88400L, wave.frames); assertEquals(PcmFormat(16000,1), wave.format)
            } }
            val second = pipeline.render(case, "SYNTHETIC_QA", baseId, settings, ClearVoicePlacement.AFTER, { false }, {})
            assertEquals("clearvoice-frcrn-onnx-context-render", second.getJSONObject("operation").getString("algorithm"))
            val effects = case.artifact(second.getJSONArray("parents").getString(0))
            assertEquals("native-pcm16-iir-enhancement", effects.getJSONObject("operation").getString("algorithm"))
            assertEquals(baseId, effects.getJSONArray("parents").getString(0))
            val replay = second.operationParameters().getJSONObject("workbenchPlan")
            assertEquals(baseId, replay.getString("baseId"))
            assertTrue(replay.getJSONObject("clearVoice").getBoolean("applied"))
            assertEquals(ClearVoicePlacement.AFTER, clearVoiceFromRecipe(replay))
            assertEquals(first.getString("sha256"), second.getString("sha256"))
            val disabled = pipeline.render(case, "SYNTHETIC_QA", replay.getString("baseId"), settings, null, { false }, {})
            assertEquals("Disabling must replay the original base", decoded.getString("sha256"), disabled.getString("sha256"))
            assertNull(clearVoiceFromRecipe(disabled.operationParameters().getJSONObject("workbenchPlan")))
            assertEquals("Local enhancement sent network bytes", sentBefore, android.net.TrafficStats.getUidTxBytes(uid))
            assertEquals("Local enhancement received network bytes", receivedBefore, android.net.TrafficStats.getUidRxBytes(uid))
            assertEquals(source.getString("sha256"), case.artifact(source.getString("id")).getString("sha256"))
            assertTrue(vault.verify(case).contains("agree"))
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it).apply { mkdirs() } }
            if (output != null) {
                File(output,"synthetic-clearvoice.echocase").outputStream().use { vault.exportPackage(case,it,"SYNTHETIC_QA") }
                val highWaterKiB = File("/proc/self/status").readLines().single { it.startsWith("VmHWM:") }.filter { it.isDigit() }.toLong()
                File(output, "synthetic-clearvoice-memory.json").writeText(JSONObject().put("scope", "synthetic-emulator-process-high-water-not-physical-acceptance")
                    .put("processHighWaterRssKiB", highWaterKiB).put("api", android.os.Build.VERSION.SDK_INT)
                    .put("modelSha256", CLEARVOICE_ONNX_HASH).put("cpuArenaAllocator", false).put("memoryPatternOptimization", false).toString())
            }
        } finally { assertTrue(File(context.filesDir,"EvidenceVault/cases/$id").deleteRecursively()) }
    }
}

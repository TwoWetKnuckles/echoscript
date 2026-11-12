package org.echoscript.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineWorkflowTest {
    @Test fun sevenNativeCoreWorkflowsWithBundledVoskAndIndependentExports() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = EvidenceVault(context)
        val workflow = OfflineWorkflow(context, vault)
        val outputDir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { File(it).apply { mkdirs() } } ?: context.cacheDir
        val speechHash = "9d17a3309b89246c14ecc5a7b9a0f9bf398de0d93824037ddaff4412b35af2bd"
        var count = 0
        repeat(7) { index ->
            val actor = "SYNTHETIC_QA"
            val case = context.assets.open("synthetic-speech.wav").use {
                vault.importSource(it, "SYNTHETIC TEST DATA - NOT EVIDENCE.wav", "Offline run ${index + 1}", actor)
            }
            assertEquals(speechHash, case.activeAudio().getString("sha256"))
            val analysis = workflow.analyze(case, actor, { false }, {})
            val artifactCount = case.getJSONArray("artifacts").length()
            assertArrayEquals(EvidenceVault.canonical(analysis), EvidenceVault.canonical(checkNotNull(workflow.savedAnalysis(case))))
            assertEquals(artifactCount, case.getJSONArray("artifacts").length())
            assertTrue(analysis.getJSONArray("peaks").length() > 0)
            assertTrue(analysis.getJSONArray("spectrum").length() > 0)
            val enhanced = workflow.enhance(case, actor, RenderSettings(gainDb = 3.0, highPassHz = 70.0), { false }, {})
            assertNotEquals(speechHash, enhanced.getString("sha256"))
            val draft = workflow.transcribe(case, actor, { false }, {})
            val transcript = workflow.readJson(case, draft, 16 * 1024 * 1024)
            assertEquals("vosk-android-local", transcript.getString("provider"))
            assertEquals(SPEECH_MODEL, transcript.getString("model"))
            val segment = transcript.items("segments").first()
            assertTrue("Vosk must return actual words", segment.getString("text").trim().length > 10)
            assertTrue("Vosk must return actual word timings", segment.getJSONArray("words").length() > 0)
            assertTrue(segment.getDouble("end") > segment.getDouble("start"))
            val edited = workflow.review(case, actor, draft, segment.getString("id"), segment.getString("text"), "Synthetic examiner label", true)
            assertEquals("Synthetic examiner label", edited.items("segments").first().getString("speakerLabel"))
            assertFalse(workflow.readJson(case, draft, 16 * 1024 * 1024).items("segments").first().has("speakerLabel"))
            vault.recordEvent(case, actor, "WORKLOAD_UPDATE", org.json.JSONObject().put("status", "IN_REVIEW"))
            assertTrue(vault.verify(case).contains("agree"))
            val wav = ByteArrayOutputStream()
            assertEquals(enhanced.getString("sha256"), workflow.exportArtifact(case, actor, enhanced, wav) { false })
            assertEquals("RIFF", wav.toByteArray().copyOfRange(0, 4).toString(Charsets.US_ASCII))
            val json = ByteArrayOutputStream()
            val jsonHash = exportDocument(workflow, vault, case, actor, ExportFormat.JSON, json) { false }
            assertEquals(EvidenceVault.sha(json.toByteArray()), jsonHash)
            if (index == 0) {
                val historyOut = ByteArrayOutputStream()
                val historyHash = exportProcessingHistory(workflow, vault, case, actor, historyOut) { false }
                assertEquals(EvidenceVault.sha(historyOut.toByteArray()), historyHash)
                val history = org.json.JSONObject(historyOut.toString("UTF-8"))
                assertEquals(enhanced.getString("id"), history.getString("activeArtifact"))
                val enhancedRecord = history.items("audioRevisions").first { it.getString("id") == enhanced.getString("id") }
                assertEquals(enhanced.getString("sha256"), enhancedRecord.getString("sha256"))
                assertEquals(enhanced.getJSONObject("operation").toString(), enhancedRecord.getJSONObject("operation").toString())
                assertTrue(vault.verify(case).contains("agree"))
                for (format in listOf(ExportFormat.PDF, ExportFormat.DOCX, ExportFormat.XLSX, ExportFormat.SRT)) {
                    val destination = ByteArrayOutputStream()
                    exportDocument(workflow, vault, case, actor, format, destination) { false }
                    val bytes = destination.toByteArray()
                    assertTrue(bytes.isNotEmpty())
                    if (format == ExportFormat.PDF) assertEquals("%PDF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
                    if (format in setOf(ExportFormat.DOCX, ExportFormat.XLSX)) assertEquals("PK", bytes.copyOfRange(0, 2).toString(Charsets.US_ASCII))
                    File(outputDir, "synthetic-native-transcript.${format.extension}").writeBytes(bytes)
                }
            }
            val checkpoint = DeviceCheckpoint(context).create(vault, case, actor) { false }
            val fingerprint = checkpoint.operationParameters().getString("keySha256")
            assertEquals(fingerprint, vault.verifyCheckpoints(case).single().keySha256)
            val protectedOutput = ByteArrayOutputStream()
            val transferPassword = if (index == 0) "SYNTHETIC test 密码 é 😀 42" else "SYNTHETIC test transfer password 42"
            val testPassword = transferPassword.toCharArray()
            val protectedHash = try { ProtectedTransfer.export(vault, case, actor, protectedOutput, testPassword) { false } }
                finally { testPassword.fill('\u0000') }
            val protectedBytes = protectedOutput.toByteArray()
            assertEquals(protectedHash, EvidenceVault.sha(protectedBytes))
            assertEquals("ECHOCS01", protectedBytes.copyOfRange(0, 8).toString(Charsets.US_ASCII))
            File(outputDir, "synthetic-protected-run-${index + 1}.echocase.enc").writeBytes(protectedBytes)
            File(outputDir, "synthetic-protected-run-${index + 1}.key-sha256.txt").writeText(fingerprint + "\n")
            val archive = ByteArrayOutputStream()
            val digest = vault.exportPackage(case, archive, actor)
            val zip = archive.toByteArray()
            assertEquals(digest, EvidenceVault.sha(zip))
            val caseId = case.getString("caseId")
            // Simulate a receiving device's empty vault. Only delete this test's synthetic case.
            val folder = File(context.filesDir, "EvidenceVault/cases/$caseId")
            assertTrue(folder.deleteRecursively())
            val reopened = vault.importPackage(zip.inputStream(), actor, digest, { false }, {})
            assertEquals(caseId, reopened.getString("caseId"))
            assertTrue(vault.verify(reopened).contains("agree"))
            assertEquals("CASE_IMPORT", reopened.items("audit").last().getString("action"))
            assertEquals("IN_REVIEW", reopened.items("audit").last { it.getString("action") == "WORKLOAD_UPDATE" }.getJSONObject("details").getString("status"))
            File(outputDir, "synthetic-offline-run-${index + 1}.echocase").writeBytes(zip)
            assertTrue(folder.deleteRecursively())
            // A receiving installation never replaces an existing case or needs
            // the originating device's private signing/vault keys.
            if (index == 0) {
                assertThrows(Exception::class.java) { ProtectedTransfer.import(vault, protectedBytes.inputStream(), actor, protectedHash,
                    "WRONG synthetic password".toCharArray(), { false }, {}) }
                assertFalse(folder.exists())
                assertThrows(Exception::class.java) { ProtectedTransfer.import(vault, protectedBytes.inputStream(), actor, "0".repeat(64),
                    "SYNTHETIC test transfer password 42".toCharArray(), { false }, {}) }
                assertFalse(folder.exists())
            }
            val recovered = ProtectedTransfer.import(vault, protectedBytes.inputStream(), actor, protectedHash,
                transferPassword.toCharArray(), { false }, {})
            assertEquals(caseId, recovered.getString("caseId"))
            assertTrue(vault.verify(recovered).contains("mathematically valid"))
            assertEquals("PROTECTED_TRANSFER_IMPORT", recovered.items("audit").last().getString("action"))
            assertEquals(speechHash, recovered.items("artifacts").first().getString("sha256"))
            assertTrue(folder.deleteRecursively())
            count++
        }
        assertEquals(7, count)
    }

    @Test fun packageTamperingAndExistingCaseReplacementAreRejected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = EvidenceVault(context)
        val case = vault.importSource("SYNTHETIC TEST DATA".byteInputStream(), "synthetic.txt", "Archive integrity", "TEST")
        val output = ByteArrayOutputStream()
        val hash = vault.exportPackage(case, output, "TEST")
        val zip = output.toByteArray()
        val before = vault.listCases().cases.size
        assertThrows(IllegalStateException::class.java) { vault.importPackage(zip.inputStream(), "TEST", hash, { false }, {}) }
        assertThrows(IllegalStateException::class.java) { vault.importPackage(zip.copyOf(zip.size - 10).inputStream(), "TEST", hash, { false }, {}) }
        assertEquals(before, vault.listCases().cases.size)
        assertTrue(vault.verify(case).contains("agree"))
    }
    @Test fun androidDecodesSyntheticMp3AacFlacVorbisAndOpusFromTheVault() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixtures = instrumentation.context.assets.open("android-codecs/codec-fixtures.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
        val hashes = fixtures.items("files").associate { it.getString("name") to it.getString("sha256") }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = EvidenceVault(context)
        val workflow = OfflineWorkflow(context, vault)
        for (extension in listOf("mp3", "m4a", "flac", "ogg", "opus")) {
            val name = "synthetic-codec.$extension"
            val bytes = instrumentation.context.assets.open("android-codecs/$name").use { it.readBytes() }
            assertEquals(hashes[name], EvidenceVault.sha(bytes))
            val case = vault.importSource(bytes.inputStream(), name, "Synthetic $extension codec", "TEST")
            val id = case.getString("caseId")
            try {
                assertEquals(EvidenceVault.sha(bytes), case.activeAudio().getString("sha256"))
                val decoded = workflow.prepareAudio(case, "TEST", { false }, {})
                assertTrue("Empty decode for $extension", decoded.operationParameters().getLong("frames") > 16000)
                assertTrue(vault.verify(case).contains("agree"))
            } finally { assertTrue(File(context.filesDir, "EvidenceVault/cases/$id").deleteRecursively()) }
        }
    }

    @Test fun streams750MbSyntheticSourceThroughDecodeRenderAndVerification() {
        org.junit.Assume.assumeTrue("Large recording enabled only on the designated emulator", InstrumentationRegistry.getArguments().getString("nativeLarge") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = EvidenceVault(context)
        val workflow = OfflineWorkflow(context, vault)
        val bytes = EvidenceVault.MAX_PROCESS_SOURCE_BYTES
        val pcm = object : java.io.InputStream() {
            var remaining = bytes - 44
            override fun read(): Int { if (remaining <= 0) return -1; remaining--; return 1 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (remaining <= 0) return -1
                val n = minOf(remaining, length.toLong()).toInt()
                java.util.Arrays.fill(buffer, offset, offset + n, 1.toByte())
                remaining -= n; return n
            }
        }
        val input = java.io.SequenceInputStream(waveHeader(PcmFormat(16000, 1), bytes - 44).inputStream(), pcm)
        val case = input.use { vault.importSource(it, "SYNTHETIC 750 MB NOT EVIDENCE.wav", "Synthetic large recording", "TEST") }
        val folder = File(context.filesDir, "EvidenceVault/cases/${case.getString("caseId")}")
        try {
            assertEquals(bytes, case.activeAudio().getLong("bytes"))
            val decoded = workflow.prepareAudio(case, "TEST", { false }, {})
            assertEquals(bytes, decoded.getLong("bytes"))
            val rendered = workflow.enhance(case, "TEST", RenderSettings(gainDb = 6.0, limit = false), { false }, {})
            assertEquals(bytes, rendered.getLong("bytes"))
            assertNotEquals(decoded.getString("sha256"), rendered.getString("sha256"))
            assertTrue(vault.verify(case).contains("agree"))
        } finally { assertTrue(folder.deleteRecursively()) }
    }

}

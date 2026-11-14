package org.echoscript.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.sin

/** A host kills a naturally running job after a real pass commits; no production test hooks. */
class ProcessingRecoveryTest {
    @Test fun actualProcessKillResumesTheSavedJob() {
        val phase = InstrumentationRegistry.getArguments().getString("processingRecoveryPhase")
        Assume.assumeTrue("Executed separately by qa-android-processing.py with an actual process kill", phase != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val marker = File(context.filesDir, "synthetic-processing-ready.json")
        val vault = EvidenceVault(context)
        val journal = ProcessingJournal(vault)
        fun launch() { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        when (phase) {
            "interrupt" -> {
                check(!marker.exists()) { "A previous synthetic recovery needs review before another run" }
                launch()
                // Four minutes of synthetic stereo PCM. The real renderer, encryption and commits
                // provide enough natural work for the host to interrupt between 32 saved passes.
                val frames = 4 * 60 * 16000L
                val format = PcmFormat(16000, 2)
                val header = waveHeader(format, frames * format.frameBytes)
                val generated = object : InputStream() {
                    var position = 0L
                    override fun read(): Int = error("Block reads required")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (position >= header.size + frames * format.frameBytes) return -1
                        val count = minOf(length.toLong(), header.size + frames * format.frameBytes - position).toInt()
                        for (i in 0 until count) {
                            val at = position + i
                            buffer[offset + i] = if (at < header.size) header[at.toInt()] else {
                                val pcm = at - header.size
                                val sample = (900 * sin(2 * Math.PI * 440 * (pcm / 4) / 16000)).toInt()
                                (sample shr ((pcm % 2).toInt() * 8)).toByte()
                            }
                        }
                        position += count; return count
                    }
                }
                val case = generated.use { vault.importSource(it, "SYNTHETIC-RECOVERY.wav", "Synthetic persistent render", "SYNTHETIC_QA") }
                val id = UUID.randomUUID().toString()
                val request = ProcessingPlan(id, id, EvidenceVault.utc(), case.getString("caseId"), case.activeAudio().getString("id"),
                    case.activeAudio().getString("sha256"), "SYNTHETIC_QA", LocalJobKind.RENDER, List(32) { RenderSettings(gainDb = .1, limit = false) })
                ProcessingJobs.enqueue(context, vault, request)
                val deadline = System.nanoTime() + 90_000_000_000L
                while (System.nanoTime() < deadline) {
                    val saved = vault.listCases().cases.single { it.getString("caseId") == request.caseId }
                    val passes = saved.items("artifacts").filter { it.operationParameters().optJSONObject("processingJob")?.optString("id") == id }
                    val record = journal.read(id)
                    if (passes.isNotEmpty() && passes.size < 32 && record.getString("state") == JobState.RUNNING.name) {
                        val first = passes.first()
                        val state = JSONObject().put("id", id).put("caseId", request.caseId).put("sourceId", request.baseId)
                            .put("sourceSha256", request.baseSha256).put("firstId", first.getString("id")).put("firstSha256", first.getString("sha256"))
                            .put("completedBeforeKill", passes.size).put("attemptBeforeKill", record.getInt("attempt"))
                        marker.outputStream().use { it.write(EvidenceVault.canonical(state)); it.fd.sync() }
                        while (System.nanoTime() < deadline) Thread.sleep(100)
                        fail("The host did not kill the active synthetic worker")
                    }
                    check(!JobState.valueOf(record.getString("state")).terminal) { "Worker completed or failed before the actual interruption boundary: ${record.getString("message")}" }
                    Thread.sleep(50)
                }
                fail("No committed processing pass became available for interruption")
            }
            "verify" -> {
                val saved = JSONObject(marker.readText())
                val id = saved.getString("id")
                launch()
                ProcessingJobs.recover(context, vault)
                val deadline = System.nanoTime() + 240_000_000_000L
                while (System.nanoTime() < deadline && !JobState.valueOf(journal.read(id).getString("state")).terminal) Thread.sleep(100)
                val record = journal.read(id)
                assertEquals(record.getString("message"), JobState.SUCCEEDED.name, record.getString("state"))
                assertTrue("The same persisted worker must start a new attempt after process death", record.getInt("attempt") > saved.getInt("attemptBeforeKill"))
                assertEquals(WorkInfo.State.SUCCEEDED, WorkManager.getInstance(context).getWorkInfoById(UUID.fromString(id)).get().state)
                val case = vault.listCases().cases.single { it.getString("caseId") == saved.getString("caseId") }
                val passes = case.items("artifacts").filter { it.operationParameters().optJSONObject("processingJob")?.optString("id") == id }
                assertEquals("All 32 completed passes, each exactly once", 32, passes.size)
                assertEquals(32, passes.map { it.operationParameters().getJSONObject("processingJob").getString("step") }.toSet().size)
                assertEquals(saved.getString("firstSha256"), case.artifact(saved.getString("firstId")).getString("sha256"))
                assertEquals(saved.getString("sourceSha256"), case.artifact(saved.getString("sourceId")).getString("sha256"))
                assertTrue(vault.verify(case).contains("agree"))
                val report = JSONObject().put("result", "PASS").put("scope", "process-kill-local-render-subset")
                    .put("completedPasses", passes.size).put("completedBeforeKill", saved.getInt("completedBeforeKill"))
                    .put("attempts", record.getInt("attempt")).put("originalPreserved", true).put("completedPassReused", true)
                    .put("duplicatePasses", 0).put("caseVerified", true).put("fullRecoveryGate", "NOT_PASSED")
                File(context.filesDir, "synthetic-processing-result.json").writeText(report.toString())
                marker.delete()
                // Only this harness's synthetic inputs are removed after verification.
                File(context.filesDir, "EvidenceVault/cases/${saved.getString("caseId")}").deleteRecursively()
                File(context.filesDir, "EvidenceVault/jobs/$id").deleteRecursively()
            }
            else -> error("Unknown synthetic recovery phase")
        }
    }
}

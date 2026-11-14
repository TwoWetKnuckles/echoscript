package org.echoscript.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ProcessingJobsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun plan(case: JSONObject, regions: List<RenderSettings>): ProcessingPlan {
        val id = UUID.randomUUID().toString()
        return ProcessingPlan(id, id, EvidenceVault.utc(), case.getString("caseId"), case.activeAudio().getString("id"),
            case.activeAudio().getString("sha256"), "SYNTHETIC_PRIVATE_EXAMINER", LocalJobKind.RENDER, regions)
    }
    private fun remove(case: JSONObject, vararg ids: String) {
        File(context.filesDir, "EvidenceVault/cases/${case.getString("caseId")}").deleteRecursively()
        for (id in ids) File(context.filesDir, "EvidenceVault/jobs/$id").deleteRecursively()
    }

    @Test fun notificationCancellationStopsAScheduledJobAndKeepsTheSavedRequest() {
        val vault = EvidenceVault(context)
        val case = context.assets.open("synthetic-speech.wav").use { vault.importSource(it, "SYNTHETIC.wav", "Synthetic scheduled cancellation", "SYNTHETIC_QA") }
        val request = plan(case, List(32) { RenderSettings(gainDb = .1, limit = false) })
        val journal = ProcessingJournal(vault)
        val manager = WorkManager.getInstance(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ProcessingJobs.enqueue(context, vault, request)
            val started = System.nanoTime() + 20_000_000_000L
            while (manager.getWorkInfoById(UUID.fromString(request.id)).get()?.state != WorkInfo.State.RUNNING && System.nanoTime() < started) Thread.sleep(20)
            assertEquals(WorkInfo.State.RUNNING, checkNotNull(manager.getWorkInfoById(UUID.fromString(request.id)).get()).state)
            context.sendBroadcast(Intent(context, LocalJobCancelReceiver::class.java).setAction("org.echoscript.CANCEL_LOCAL_JOB").putExtra("id", request.id))
            val stopped = System.nanoTime() + 20_000_000_000L
            while (manager.getWorkInfoById(UUID.fromString(request.id)).get()?.state != WorkInfo.State.CANCELLED && System.nanoTime() < stopped) Thread.sleep(20)
            assertEquals(WorkInfo.State.CANCELLED, checkNotNull(manager.getWorkInfoById(UUID.fromString(request.id)).get()).state)
            assertTrue(ProcessingJobs.cancellationRecorded(context, request.id))
            assertEquals(JobState.CANCELLED.name, journal.read(request.id).getString("state"))
            assertEquals(request.requestSha256, ProcessingPlan.parse(journal.read(request.id).getJSONObject("plan")).requestSha256)
            // Join the actual writer before checking that no cancellation can restart it.
            ProcessingGate.lock.lock()
            try {
                ProcessingJobs.recover(context, vault)
                val saved = vault.listCases().cases.single { it.getString("caseId") == request.caseId }
                assertEquals(request.baseSha256, saved.artifact(request.baseId).getString("sha256"))
                assertTrue(vault.verify(saved).contains("agree"))
                assertEquals(WorkInfo.State.CANCELLED, checkNotNull(manager.getWorkInfoById(UUID.fromString(request.id)).get()).state)
            } finally { ProcessingGate.lock.unlock() }
        } finally {
            runCatching { ProcessingJobs.cancel(context, request.id) }
            scenario.close()
            ProcessingGate.lock.lock()
            try { remove(case, request.id) } finally { ProcessingGate.lock.unlock() }
        }
    }

    @Test fun encryptedJournalRejectsDamagedLatestRecordAndCancellationSurvivesRecovery() {
        val vault = EvidenceVault(context)
        val case = context.assets.open("synthetic-speech.wav").use { vault.importSource(it, "SYNTHETIC.wav", "Synthetic journal", "SYNTHETIC_QA") }
        val request = plan(case, listOf(RenderSettings(gainDb = 3.25)))
        val journal = ProcessingJournal(vault)
        try {
            journal.create(request)
            journal.update(request.id, JobState.RUNNING, "Synthetic private status")
            val records = File(context.filesDir, "EvidenceVault/jobs/${request.id}").listFiles()!!.filter { it.extension == "enc" }.sortedBy { it.name }
            assertEquals(2, records.size)
            assertFalse(records.any { it.readBytes().toString(Charsets.ISO_8859_1).contains(request.actor) })
            val latest = records.last(); val intact = latest.readBytes()
            latest.writeBytes(intact.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertThrows(Exception::class.java) { journal.read(request.id) }
            assertTrue(journal.records().second.any { it.contains("no earlier job record") })
            latest.writeBytes(intact)
            ProcessingJobs.cancel(context, request.id)
            assertTrue(ProcessingJobs.cancellationRecorded(context, request.id))
            assertEquals(JobState.CANCELLED.name, journal.read(request.id).getString("state"))
            ProcessingJobs.recover(context, vault)
            assertEquals(JobState.CANCELLED.name, journal.read(request.id).getString("state"))
            assertEquals(JobState.CANCELLED.name, journal.update(request.id, JobState.RUNNING, "Must not restart").getString("state"))
            val prefs = context.getSharedPreferences("echoscript_job_cancellations", Context.MODE_PRIVATE)
            assertEquals(setOf("ids"), prefs.all.keys)
            assertTrue((prefs.all["ids"] as Set<*>).all { it is String && JOB_ID.matches(it) })
        } finally { remove(case, request.id) }
    }

    @Test fun interruptedRenderReusesVerifiedCompletedPassesAndRejectsTamperedOutput() {
        val vault = EvidenceVault(context); val workflow = OfflineWorkflow(context, vault)
        val case = context.assets.open("synthetic-speech.wav").use { vault.importSource(it, "SYNTHETIC.wav", "Synthetic job retry", "SYNTHETIC_QA") }
        val request = plan(case, listOf(RenderSettings(gainDb = 1.0, limit = false), RenderSettings(gainDb = 2.0, limit = false)))
        val pipeline = LocalRenderPipeline(context, vault, workflow)
        fun completed() = case.items("artifacts").filter { it.operationParameters().optJSONObject("processingJob")?.optString("id") == request.rootId }
        try {
            assertThrows(InterruptedException::class.java) {
                pipeline.render(case, request.actor, request.baseId, request.regions, null, { completed().isNotEmpty() }, {},
                    ProcessingContext(request, vault, { completed().isNotEmpty() }))
            }
            val first = completed().single()
            assertEquals("render.region.0", first.operationParameters().getJSONObject("processingJob").getString("step"))
            val retry = request.retry(request.actor)
            pipeline.render(case, retry.actor, retry.baseId, retry.regions, null, { false }, {}, ProcessingContext(retry, vault, { false }, 2))
            assertEquals(2, completed().size)
            assertEquals(first.getString("id"), completed().first().getString("id"))
            assertEquals(2, completed().last().operationParameters().getJSONObject("processingJob").getInt("attempt"))
            pipeline.render(case, retry.actor, retry.baseId, retry.regions, null, { false }, {}, ProcessingContext(retry, vault, { false }, 3))
            assertEquals("Replay must not compound or duplicate committed passes", 2, completed().size)
            assertEquals(request.baseSha256, case.artifact(request.baseId).getString("sha256"))
            assertTrue(vault.verify(case).contains("agree"))
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it).apply { mkdirs() } } ?: context.cacheDir
            File(output, "synthetic-processing.echocase").outputStream().use { vault.exportPackage(case, it, request.actor) }
            // A second duplicate or changed hash must fail rather than silently reusing a plausible pass.
            val changed = JSONObject(case.toString())
            changed.artifact(first.getString("id")).put("sha256", "0".repeat(64))
            assertThrows(IllegalStateException::class.java) { ProcessingContext(retry, vault, { false }).step("render.region.0").reuse(changed, "ENHANCED_AUDIO", "native-pcm16-iir-enhancement") }
            changed.getJSONArray("artifacts").put(JSONObject(first.toString()))
            assertThrows(IllegalStateException::class.java) { ProcessingContext(retry, vault, { false }).step("render.region.0").reuse(changed, "ENHANCED_AUDIO", "native-pcm16-iir-enhancement") }
        } finally { remove(case) }
    }
}

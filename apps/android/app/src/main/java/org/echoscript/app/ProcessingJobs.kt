package org.echoscript.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** One writer for local processing and foreground UI operations in this process. */
internal object ProcessingGate {
    val lock = ReentrantLock(true)
    private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()
    fun cancellation(id: String) = cancellations.computeIfAbsent(id) { AtomicBoolean(false) }
    fun forget(id: String) { cancellations.remove(id) }
}

internal class ProcessingJournal(private val vault: EvidenceVault) {
    companion object { private val lock = Any() }
    fun read(id: String): JSONObject = checkNotNull(vault.readProcessingRecord(id)) { "Local job record is unavailable" }.also {
        require(it.keys().asSequence().toSet() == setOf("schema", "sequence", "plan", "state", "attempt", "message", "audioId", "transcriptId", "acknowledged", "updatedAtUtc"))
        require(it.getString("schema") == "org.echoscript.local-job-record/1")
        ProcessingPlan.parse(it.getJSONObject("plan"))
        JobState.valueOf(it.getString("state"))
        require(it.getInt("attempt") >= 0 && it.getString("message").length <= 500)
        require(it.opt("acknowledged") is Boolean)
        for (key in listOf("audioId", "transcriptId")) require(it.isNull(key) || JOB_ID.matches(it.getString(key)))
        java.time.Instant.parse(it.getString("updatedAtUtc"))
    }
    fun create(plan: ProcessingPlan): JSONObject = synchronized(lock) {
        check(vault.readProcessingRecord(plan.id) == null) { "Local job already exists" }
        val record = JSONObject().put("schema", "org.echoscript.local-job-record/1").put("sequence", 0)
            .put("plan", plan.json()).put("state", JobState.QUEUED.name).put("attempt", 0)
            .put("message", "Queued for on-device processing.").put("audioId", JSONObject.NULL)
            .put("transcriptId", JSONObject.NULL).put("acknowledged", false).put("updatedAtUtc", EvidenceVault.utc())
        vault.writeProcessingRecord(plan.id, 0, record); record
    }
    fun update(id: String, state: JobState, message: String, audioId: String? = null, transcriptId: String? = null): JSONObject = synchronized(lock) {
        val next = read(id)
        val previous = JobState.valueOf(next.getString("state"))
        if (previous.terminal) return@synchronized next
        next.put("sequence", next.getInt("sequence") + 1).put("state", state.name)
            .put("message", message.take(500)).put("updatedAtUtc", EvidenceVault.utc())
        if (state == JobState.RUNNING) next.put("attempt", next.getInt("attempt") + 1)
        if (audioId != null) { require(JOB_ID.matches(audioId)); next.put("audioId", audioId) }
        if (transcriptId != null) { require(JOB_ID.matches(transcriptId)); next.put("transcriptId", transcriptId) }
        vault.writeProcessingRecord(id, next.getInt("sequence"), next); next
    }
    fun acknowledge(id: String) = synchronized(lock) {
        val next = read(id)
        if (!next.getBoolean("acknowledged") && JobState.valueOf(next.getString("state")).terminal) {
            next.put("sequence", next.getInt("sequence") + 1).put("acknowledged", true).put("updatedAtUtc", EvidenceVault.utc())
            vault.writeProcessingRecord(id, next.getInt("sequence"), next)
        }
    }
    fun records(): Pair<List<JSONObject>, List<String>> {
        val warnings = mutableListOf<String>()
        val records = vault.processingJobIds().mapNotNull { id ->
            try { read(id) } catch (_: Exception) { warnings.add("An unfinished local job could not be authenticated. Preserve the vault for review; no earlier job record was substituted."); null }
        }
        return records.sortedByDescending { it.getJSONObject("plan").getString("createdAtUtc") } to warnings
    }
}

internal object ProcessingJobs {
    const val TAG = "echoscript-local-processing"
    private const val CHAIN = "echoscript-local-processing-chain"
    private val cancelLock = Any()
    private fun cancelPreferences(context: Context) = context.getSharedPreferences("echoscript_job_cancellations", Context.MODE_PRIVATE)
    fun cancellationRecorded(context: Context, id: String): Boolean = synchronized(cancelLock) { id in (cancelPreferences(context).getStringSet("ids", emptySet()) ?: emptySet()) }
    fun schedule(context: Context, id: String) {
        require(JOB_ID.matches(id))
        val work = OneTimeWorkRequestBuilder<LocalProcessingWorker>().setId(UUID.fromString(id))
            .setInputData(workDataOf("id" to id)).addTag(TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).beginUniqueWork(CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, work).enqueue().result.get()
    }
    fun enqueue(context: Context, vault: EvidenceVault, plan: ProcessingPlan): String {
        plan.checkRuntime()
        val journal = ProcessingJournal(vault); journal.create(plan)
        try { schedule(context, plan.id) }
        catch (_: Exception) { journal.update(plan.id, JobState.FAILED, "The device could not schedule local processing. Open the app and retry."); throw IllegalStateException("Local processing could not be scheduled; its settings were preserved.") }
        return plan.id
    }
    /** Restore the enqueue/commit gap only. Failed/cancelled jobs never restart silently. */
    fun recover(context: Context, vault: EvidenceVault): List<String> {
        val (records, warnings) = ProcessingJournal(vault).records()
        for (record in records.asReversed()) {
            val state = JobState.valueOf(record.getString("state"))
            if (state.terminal) continue
            val plan = ProcessingPlan.parse(record.getJSONObject("plan"))
            try {
                plan.checkRuntime()
                val info = WorkManager.getInstance(context).getWorkInfoById(UUID.fromString(plan.id)).get()
                when {
                    cancellationRecorded(context, plan.id) -> ProcessingJournal(vault).update(plan.id, JobState.CANCELLED, "Cancelled. Completed originals and revisions remain preserved.")
                    info == null -> schedule(context, plan.id)
                    info.state == androidx.work.WorkInfo.State.CANCELLED -> ProcessingJournal(vault).update(plan.id, JobState.CANCELLED, "Cancelled. Completed originals and revisions remain preserved.")
                    info.state == androidx.work.WorkInfo.State.FAILED -> ProcessingJournal(vault).update(plan.id, JobState.FAILED, "Local processing stopped. Review the saved case before retrying.")
                }
            } catch (_: Exception) {
                ProcessingJournal(vault).update(plan.id, JobState.FAILED, "This job cannot resume with the current device/software state. Review the saved case and start a new job.")
            }
        }
        return warnings
    }
    fun cancel(context: Context, id: String) {
        require(JOB_ID.matches(id))
        ProcessingGate.cancellation(id).set(true)
        // Only opaque, already-scheduled WorkManager UUIDs may appear here. This
        // durable tombstone also protects cancellation if the vault cannot open.
        synchronized(cancelLock) {
            val prefs = cancelPreferences(context)
            check(prefs.edit().putStringSet("ids", (prefs.getStringSet("ids", emptySet()) ?: emptySet()) + id).commit()) { "Cancellation could not be durably recorded" }
        }
        // Persist cancellation before cancelling WorkManager, including the notification path.
        try { ProcessingJournal(EvidenceVault(context)).update(id, JobState.CANCELLED, "Cancelled. Completed originals and revisions remain preserved.") }
        finally { WorkManager.getInstance(context).cancelWorkById(UUID.fromString(id)).result.get() }
    }
}

class LocalJobCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "org.echoscript.CANCEL_LOCAL_JOB") return
        val id = intent.getStringExtra("id")?.takeIf(JOB_ID::matches) ?: return
        ProcessingGate.cancellation(id).set(true)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { runCatching { ProcessingJobs.cancel(context.applicationContext, id) } } finally { pending.finish() }
        }
    }
}

/** User-requested local work only. Remote routes retain their separate exact-payload approval. */
class LocalProcessingWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString("id") ?: return Result.failure()
        if (!JOB_ID.matches(jobId) || id.toString() != jobId) return Result.failure()
        if (ProcessingJobs.cancellationRecorded(applicationContext, jobId)) return Result.failure()
        var journal: ProcessingJournal? = null
        val control = ProcessingGate.cancellation(jobId)
        fun stopped() = isStopped || control.get() || Thread.currentThread().isInterrupted
        try {
            setForeground(foreground("Processing on this device"))
            return runInterruptible(Dispatchers.IO) {
                ProcessingGate.lock.lockInterruptibly()
                try {
                    val vault = EvidenceVault(applicationContext)
                    val log = ProcessingJournal(vault); journal = log
                    val record = log.read(jobId)
                    val state = JobState.valueOf(record.getString("state"))
                    if ((state.terminal && state != JobState.SUCCEEDED) || stopped()) return@runInterruptible Result.failure()
                    if (state == JobState.SUCCEEDED) return@runInterruptible Result.success()
                    val plan = ProcessingPlan.parse(record.getJSONObject("plan")); plan.checkRuntime()
                    val manifest = vault.listCases().cases.singleOrNull { it.getString("caseId") == plan.caseId }
                        ?: error("The saved case could not be authenticated. Preserve the vault for review.")
                    require(manifest.artifact(plan.baseId).getString("sha256") == plan.baseSha256) { "The job's original input linkage changed" }
                    val running = log.update(jobId, JobState.RUNNING, "Processing on this device; completed passes are reused after interruption.")
                    val workflow = OfflineWorkflow(applicationContext, vault)
                    val processing = ProcessingContext(plan, vault, ::stopped, running.getInt("attempt"))
                    var lastProgress = 0L
                    fun phase(label: String): (Long) -> Unit {
                        setProgressAsync(workDataOf("stage" to label))
                        setForegroundAsync(foreground(label))
                        return { count ->
                            if (stopped()) throw InterruptedException("Local processing interrupted")
                            val now = System.nanoTime()
                            if (now - lastProgress >= 1_000_000_000L) {
                                lastProgress = now; setProgressAsync(workDataOf("stage" to label, "units" to count))
                            }
                        }
                    }
                    vault.selectRevision(manifest, plan.actor, plan.baseId)
                    when (plan.kind) {
                        LocalJobKind.RENDER, LocalJobKind.RENDER_TRANSCRIBE ->
                            LocalRenderPipeline(applicationContext, vault, workflow).render(manifest, plan.actor, plan.baseId, plan.regions, plan.clearVoice, ::stopped, phase("Rendering audio"), processing)
                        LocalJobKind.CLEARVOICE, LocalJobKind.CLEARVOICE_TRANSCRIBE -> {
                            val recipe = JSONObject().put("baseId", plan.baseId).put("rack", EffectRack().json()).put("regions", org.json.JSONArray()).put("limit", false)
                            ClearVoiceNative(applicationContext, vault, workflow).enhance(manifest, plan.actor, ClearVoicePlacement.AFTER.name, ::stopped,
                                phase("Enhancing speech"), withClearVoiceRecipe(recipe.toString(), ClearVoicePlacement.AFTER, true), processing.step("direct-clearvoice"))
                        }
                        LocalJobKind.TRANSCRIBE -> Unit
                    }
                    if (stopped()) throw InterruptedException("Local processing interrupted")
                    val transcript = if (plan.kind in setOf(LocalJobKind.TRANSCRIBE, LocalJobKind.RENDER_TRANSCRIBE, LocalJobKind.CLEARVOICE_TRANSCRIBE))
                        workflow.transcribe(manifest, plan.actor, ::stopped, phase("Transcribing on this device"), processing) else null
                    if (workflow.savedAnalysis(manifest) == null) workflow.analyze(manifest, plan.actor, ::stopped, phase("Analyzing audio"))
                    if (stopped()) throw InterruptedException("Local processing interrupted")
                    log.update(jobId, JobState.SUCCEEDED, if (transcript == null) "Audio revision saved. Earlier audio and settings remain in revision history." else "Draft transcript saved. Review the words, timestamps and speaker groups.", manifest.getString("activeArtifact"), transcript?.getString("id"))
                    Result.success()
                } finally { ProcessingGate.lock.unlock() }
            }
        } catch (failure: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { journal?.update(jobId, JobState.PAUSED, "Paused by Android. The local job can resume from its completed passes.") } }
            throw failure
        } catch (_: InterruptedException) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { journal?.update(jobId, if (control.get()) JobState.CANCELLED else JobState.PAUSED, "Local processing paused. Completed originals and revisions remain preserved.") } }
            return if (control.get()) Result.failure() else Result.retry()
        } catch (_: OutOfMemoryError) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { journal?.update(jobId, JobState.FAILED, "The device ran out of memory. Completed work is preserved; close other apps and review before retrying.") } }
            return Result.failure()
        } catch (_: LinkageError) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { journal?.update(jobId, JobState.FAILED, "The bundled speech runtime could not load on this device. Preserve the case and review device compatibility.") } }
            return Result.failure()
        } catch (failure: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { journal?.update(jobId, JobState.FAILED, failure.message?.take(350) ?: "Local processing could not finish. Review the saved case before retrying.") } }
            return Result.failure()
        } finally { ProcessingGate.forget(jobId) }
    }

    private fun foreground(stage: String): ForegroundInfo {
        val channel = "echoscript-local-processing"
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, "On-device processing", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getBroadcast(applicationContext, id.hashCode(), Intent(applicationContext, LocalJobCancelReceiver::class.java)
            .setAction("org.echoscript.CANCEL_LOCAL_JOB").putExtra("id", id.toString()), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(applicationContext, channel).setSmallIcon(R.drawable.ic_wave).setContentTitle("EchoScript").setContentText("On-device processing").build()
        val notification = NotificationCompat.Builder(applicationContext, channel).setSmallIcon(R.drawable.ic_wave)
            .setContentTitle("EchoScript").setContentText(stage).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel).build()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(602, notification, type)
    }
}

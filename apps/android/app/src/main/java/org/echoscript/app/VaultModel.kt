package org.echoscript.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlin.concurrent.withLock

internal data class ReviewDraft(val text: String, val label: String, val verified: Boolean = false, val note: String = "")
// In-memory workbench controls survive activity recreation without persisting a
// document URI, audio data, or unsaved control state to shared preferences.
internal data class WorkbenchDraftMemory(
    val caseId: String, val revisionId: String, val draft: String, val regions: String,
    val baseId: String, val start: String, val end: String, val chosen: Int,
    val presetBackup: String, val presetName: String, val limit: Boolean,
    val showEffects: Boolean, val effectCategory: String, val selectedEffect: String, val appliedNoiseMeasurement: String,
    val showComparison: Boolean, val comparisonId: String, val showDetail: Boolean, val selectFrequency: Boolean,
    val detailFft: Int, val detailOverlap: Int, val detailWindow: String, val detailScale: String, val detailFloor: Int,
    val clearVoiceEnabled: Boolean, val clearVoicePlacement: ClearVoicePlacement, val transcriptionProvider: TranscriptionProvider,
)
internal const val INTERRUPTED_AUDIO_SELECTION = "A file selection or import was interrupted. Check Cases for a preserved copy, then choose the recording again if needed."

internal data class VaultScreen(
    val ready: Boolean = false,
    val cases: List<JSONObject> = emptyList(),
    val selected: String? = null,
    val job: String? = null,
    val backgroundJobId: String? = null,
    val lastBackgroundJobId: String? = null,
    val message: String = "Opening the encrypted vault…",
    val recoveryWarnings: List<String> = emptyList(),
    val analysis: JSONObject? = null,
    val fullSpectrum: JSONObject? = null,
    val detailedSpectrum: JSONObject? = null,
    val noiseFloorMeasurement: JSONObject? = null,
    val comparisonAnalysis: JSONObject? = null,
    val comparisonAudioId: String? = null,
    val transcript: JSONObject? = null,
    val transcriptRevision: String? = null,
    val failed: Boolean = false,
    val importPending: Boolean = false,
    val importName: String? = null,
    val audioSelected: Boolean = false,
    val pickerNotice: String? = null,
    val importedCaseId: String? = null,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VaultModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(VaultScreen())
    val state = mutable.asStateFlow()
    // Publish UI state on Main even when a DSP/decoder callback runs on IO.
    // StateFlow is thread-safe; Compose view work must still stay on Main.
    private fun updateState(change: (VaultScreen) -> VaultScreen) {
        viewModelScope.launch(Dispatchers.Main.immediate) { mutable.update(change) }
    }
    private val vaultOpened = CompletableDeferred<Boolean>()
    private val cancelled = AtomicBoolean(false)
    private val recordingStop = AtomicBoolean(false)
    private lateinit var vault: EvidenceVault
    private lateinit var workflow: OfflineWorkflow
    private val preferences = application.getSharedPreferences("echoscript_workspace", 0)
    // The provider URI is deliberately held only in memory. After process death,
    // the recovery notice asks for a new selection instead of saving sensitive
    // document paths in preferences or pretending the source was imported.
    private data class AudioSelection(val uri: Uri, val title: String, val sequence: Long)
    private var audioSelection: AudioSelection? = null
    private var selectionSequence = 0L
    var player by mutableStateOf<ExoPlayer?>(null); private set
    var reviewDrafts by mutableStateOf<Map<String, ReviewDraft>>(emptyMap()); private set
    var acquisitionTitle by mutableStateOf("")
    var caseQuery by mutableStateOf("")
    var reviewEditorKey by mutableStateOf<String?>(null)
    var transferPassphrase by mutableStateOf("")
    var exportPassphrase by mutableStateOf("")
    var exportPassphraseConfirmation by mutableStateOf("")
    var trustedCheckpointKey by mutableStateOf("")
    var transcriptionProvider by mutableStateOf(TranscriptionProvider.VOSK)
    var clearVoiceEnabled by mutableStateOf(false)
    var clearVoicePlacement by mutableStateOf(ClearVoicePlacement.AFTER)
    var companionAddress by mutableStateOf("http://127.0.0.1:8787")
    // Session only; never preferences, saved-instance state, manifests or APK resources.
    var companionToken by mutableStateOf("")
    var remoteApproval by mutableStateOf<RemoteBatch?>(null); private set
    private data class RemoteAction(val actor: String, val batch: RemoteBatch, val completed: (JSONObject) -> Unit)
    private var remoteAction: RemoteAction? = null
    private var workbenchMemory: WorkbenchDraftMemory? = null
    private val processingCallbacks = ConcurrentHashMap<String, () -> Unit>()

    fun recallWorkbenchDraft(caseId: String, revisionId: String): WorkbenchDraftMemory? =
        workbenchMemory?.takeIf { it.caseId == caseId && it.revisionId == revisionId }
    fun rememberWorkbenchDraft(memory: WorkbenchDraftMemory) { workbenchMemory = memory }

    fun clearExportSecret() { exportPassphrase = ""; exportPassphraseConfirmation = "" }

    fun updateReviewDraft(key: String, draft: ReviewDraft) {
        reviewDrafts = reviewDrafts + (key to draft)
    }
    fun resetReviewDraft(key: String) {
        reviewDrafts = reviewDrafts - key
        if (reviewEditorKey == key) reviewEditorKey = null
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                vault = EvidenceVault(application)
                workflow = OfflineWorkflow(application, vault)
                val processingWarnings = ProcessingJobs.recover(application, vault)
                val listing = vault.listCases()
                val interrupted = preferences.getString("pendingJob", null)
                val interruptedSelection = preferences.getBoolean("pendingAudioSelection", false)
                withContext(Dispatchers.Main) {
                    mutable.update { current -> current.copy(
                        ready = true,
                        cases = listing.cases,
                        recoveryWarnings = listing.warnings + processingWarnings + listOfNotNull(interrupted?.let {
                            "Interrupted task · $it. Review the saved case before retrying."
                        }) + (if (interruptedSelection && !current.audioSelected && current.pickerNotice == null) listOf(INTERRUPTED_AUDIO_SELECTION) else emptyList()),
                        message = when {
                            current.importPending -> "Recording selected. Waiting for the encrypted vault…"
                            current.audioSelected || current.pickerNotice != null -> current.message
                            else -> "Choose a case or import a recording."
                        },
                    ) }
                    vaultOpened.complete(true)
                }
                observeProcessing()
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    mutable.update { it.copy(message = "The device vault could not open. Preserve the existing evidence and keys.", failed = true, importPending = false) }
                    vaultOpened.complete(false)
                }
            }
        }
    }

    fun select(id: String) {
        if (mutable.value.job != null || id == mutable.value.selected) return
        if (!mutable.value.audioSelected) updateState { it.copy(importName = null) }
        runJob("Opening case", refreshCases = false) {
            val manifest = mutable.value.cases.first { it.getString("caseId") == id }
            restoreWorkspace(manifest)
            "Saved workspace opened."
        }
    }
    fun cancel() {
        val id = mutable.value.backgroundJobId
        if (id == null) cancelled.set(true) else {
            ProcessingGate.cancellation(id).set(true)
            viewModelScope.launch(Dispatchers.IO) {
                try { ProcessingJobs.cancel(getApplication(), id) }
                catch (_: Exception) { notice("Cancellation could not be fully recorded. Preserve the vault and check the job before retrying.") }
            }
        }
        updateState { it.copy(message = "Cancelling after the current block…") }
    }
    fun notice(message: String) { updateState { it.copy(message = message) } }
    private fun selected(): JSONObject = mutable.value.cases.firstOrNull { it.getString("caseId") == mutable.value.selected }
        ?.let { JSONObject(it.toString()) } ?: error("Select a case first")

    internal fun runJob(
        name: String,
        completed: () -> Unit = {},
        refreshCases: Boolean = true,
        action: () -> String,
    ) {
        if (!mutable.value.ready || mutable.value.job != null) return
        stop(); cancelled.set(false)
        updateState { it.copy(job = name, message = name, failed = false, importPending = false) }
        viewModelScope.launch(Dispatchers.IO) {
            var succeeded = false
            var memoryExhausted = false
            val message = try {
                check(preferences.edit().putString("pendingJob", name).commit()) { "Could not record task recovery state" }
                ProcessingGate.lock.withLock { action() }.also { succeeded = true }
            }
            catch (_: InterruptedException) { "Cancelled. Completed originals and revisions are preserved." }
            catch (_: OutOfMemoryError) {
                memoryExhausted = true
                "The device did not have enough memory to finish. Check Cases for preserved work, then close other apps and try again."
            }
            catch (_: LinkageError) { "The bundled speech library could not load on this Android device." }
            catch (failure: Exception) { failure.message?.take(350) ?: "Operation could not complete. Review the case before retrying." }
            var refreshFailed = false
            val listing = try {
                if (refreshCases) vault.listCases() else null
            } catch (_: Exception) {
                refreshFailed = true
                null
            } catch (_: OutOfMemoryError) {
                refreshFailed = true
                memoryExhausted = true
                null
            }
            preferences.edit().remove("pendingJob").commit()
            withContext(Dispatchers.Main) {
                updateState { current ->
                    current.copy(
                        cases = listing?.cases ?: current.cases,
                        recoveryWarnings = listing?.warnings ?: current.recoveryWarnings,
                        job = if (current.backgroundJobId == null) null else current.job,
                        failed = !succeeded || refreshFailed,
                        message = when {
                            refreshFailed && memoryExhausted -> "The device ran out of memory. Reopen the app and check Cases for preserved work."
                            refreshFailed -> "Vault refresh failed. Reopen the app to check the saved case."
                            else -> message
                        },
                    )
                }
                if (succeeded && !refreshFailed) completed()
            }
        }
    }

    private suspend fun observeProcessing() {
        val manager = WorkManager.getInstance(getApplication())
        val journal = ProcessingJournal(vault)
        manager.getWorkInfosByTagFlow(ProcessingJobs.TAG).collect { infos ->
            val active = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: infos.firstOrNull { !it.state.isFinished }
            if (active != null) {
                val id = active.id.toString()
                val record = try { journal.read(id) } catch (_: Exception) {
                    updateState { it.copy(backgroundJobId = id, job = "Local job needs review", failed = true,
                        message = "The saved local job could not be authenticated. Cancel it and preserve the vault for review; no earlier job record was substituted.") }
                    return@collect
                }
                val plan = ProcessingPlan.parse(record.getJSONObject("plan"))
                val phase = active.progress.getString("stage") ?: "Queued for on-device processing"
                val count = active.progress.getLong("units", 0)
                val listing = if (mutable.value.backgroundJobId != id) vault.listCases() else null
                withContext(Dispatchers.Main) {
                    mutable.update { it.copy(backgroundJobId = id, lastBackgroundJobId = null, job = phase,
                        selected = plan.caseId, cases = listing?.cases ?: it.cases,
                        message = if (count > 0) "$phase · $count units processed" else "$phase. You can leave this screen; Android manages the local job.", failed = false) }
                }
            } else {
                // Reconcile terminal WorkManager states before displaying a result.
                for (info in infos.filter { it.state in setOf(WorkInfo.State.CANCELLED, WorkInfo.State.FAILED) }) {
                    val id = info.id.toString()
                    runCatching {
                        val record = journal.read(id)
                        if (!JobState.valueOf(record.getString("state")).terminal) journal.update(id,
                            if (info.state == WorkInfo.State.CANCELLED) JobState.CANCELLED else JobState.FAILED,
                            "Local processing stopped. Completed work remains preserved; review the saved case before retrying.")
                    }
                }
                val result = journal.records().first.firstOrNull()?.takeIf {
                    JobState.valueOf(it.getString("state")).terminal && !it.getBoolean("acknowledged")
                }
                if (result != null) {
                    val plan = ProcessingPlan.parse(result.getJSONObject("plan"))
                    val success = result.getString("state") == JobState.SUCCEEDED.name
                    val listing = vault.listCases()
                    val case = listing.cases.firstOrNull { it.getString("caseId") == plan.caseId }
                    val analysis = case?.let { workflow.savedAnalysis(it) }
                    val transcriptArtifact = if (result.isNull("transcriptId")) null else case?.artifact(result.getString("transcriptId"))
                    val transcript = if (case != null && transcriptArtifact != null) workflow.readTranscript(case, transcriptArtifact) else null
                    if (success) journal.acknowledge(plan.id)
                    withContext(Dispatchers.Main) {
                        if (plan.requestedProvider == "WHISPER") transcriptionProvider = TranscriptionProvider.WHISPER
                        mutable.update { it.copy(backgroundJobId = null, lastBackgroundJobId = if (success) null else plan.id, job = null,
                            cases = listing.cases, selected = case?.getString("caseId") ?: it.selected,
                            analysis = analysis, fullSpectrum = null, detailedSpectrum = null, noiseFloorMeasurement = null,
                            comparisonAnalysis = null, comparisonAudioId = null, transcript = transcript,
                            transcriptRevision = transcriptArtifact?.getString("id"), failed = !success,
                            message = result.getString("message") + if (success && plan.requestedProvider == "WHISPER") " Open Transcript to prepare and approve Whisper; its session credentials are never queued." else "") }
                        if (success) processingCallbacks.remove(plan.id)?.invoke() else processingCallbacks.remove(plan.id)
                    }
                } else if (mutable.value.backgroundJobId != null) {
                    updateState { it.copy(backgroundJobId = null, job = null) }
                }
            }
        }
    }

    private fun queueProcessing(actor: String, kind: LocalJobKind, baseId: String? = null, regions: List<RenderSettings> = emptyList(),
        clearVoice: ClearVoicePlacement? = null, requestedProvider: String = "NONE", completed: () -> Unit = {}) {
        runJob("Queueing on-device processing") {
            val manifest = selected()
            val base = manifest.artifact(baseId ?: manifest.getString("activeArtifact"))
            val id = UUID.randomUUID().toString()
            val plan = ProcessingPlan(id, id, EvidenceVault.utc(), manifest.getString("caseId"), base.getString("id"), base.getString("sha256"), actor,
                kind, regions, clearVoice, requestedProvider)
            processingCallbacks[id] = completed
            try { ProcessingJobs.enqueue(getApplication(), vault, plan) }
            catch (failure: Exception) { processingCallbacks.remove(id); throw failure }
            updateState { it.copy(backgroundJobId = id, job = "Queued for on-device processing", analysis = null,
                fullSpectrum = null, detailedSpectrum = null, noiseFloorMeasurement = null, transcript = null, transcriptRevision = null) }
            "Queued for on-device processing. Completed passes are retained if Android interrupts the job."
        }
    }

    fun retryProcessing() {
        val id = mutable.value.lastBackgroundJobId ?: return
        runJob("Retrying saved local processing") {
            val previous = ProcessingJournal(vault).read(id)
            require(previous.getString("state") in setOf(JobState.FAILED.name, JobState.CANCELLED.name))
            val plan = ProcessingPlan.parse(previous.getJSONObject("plan")).retry()
            ProcessingJournal(vault).acknowledge(id)
            ProcessingJobs.enqueue(getApplication(), vault, plan)
            updateState { it.copy(backgroundJobId = plan.id, lastBackgroundJobId = null, job = "Queued for on-device processing") }
            "Retry queued with the saved settings. Completed passes will be verified before reuse."
        }
    }
    fun dismissProcessingFailure() {
        val id = mutable.value.lastBackgroundJobId ?: return
        runJob("Dismissing local job notice") {
            ProcessingJournal(vault).acknowledge(id)
            updateState { it.copy(lastBackgroundJobId = null) }
            "Saved job settings and completed revisions remain preserved."
        }
    }

    private fun showCase(manifest: JSONObject) {
        updateState { it.copy(selected = manifest.getString("caseId"), analysis = null, fullSpectrum = null, detailedSpectrum = null, noiseFloorMeasurement = null, comparisonAnalysis = null, comparisonAudioId = null, transcript = null, transcriptRevision = null) }
    }
    private fun restoreWorkspace(manifest: JSONObject) {
        showCase(manifest)
        val overview = workflow.savedAnalysis(manifest)
        val fullSpectrum = workflow.savedFullSpectrum(manifest)
        updateState { it.copy(analysis = overview, fullSpectrum = fullSpectrum) }
        manifest.latestTranscript()?.let { openTranscript(manifest, it) }
    }
    private fun reportProgress(unit: String): (Long) -> Unit {
        var last = 0L
        return { count ->
            val now = System.nanoTime()
            if (now - last >= 500_000_000 && !cancelled.get()) {
                last = now
                updateState { it.copy(message = "${it.job} · $count $unit") }
            }
        }
    }
    fun acknowledgeImportedCase(id: String) {
        updateState { if (it.importedCaseId == id) it.copy(importedCaseId = null) else it }
    }
    fun markAudioPickerOpen(): Boolean {
        // Record only that the picker is in flight, before Android leaves this
        // activity. No filename or URI is persisted by this recovery marker.
        return try { preferences.edit().putBoolean("pendingAudioSelection", true).commit() }
        catch (_: RuntimeException) { false }
    }
    fun pickerNotice(message: String) {
        if (audioSelection == null) try { preferences.edit().remove("pendingAudioSelection").apply() } catch (_: RuntimeException) { }
        updateState { it.copy(pickerNotice = message, message = message, failed = true) }
    }
    fun dismissPickerNotice() {
        updateState { it.copy(pickerNotice = null, failed = false) }
    }
    fun dismissInterruptedSelection() {
        if (audioSelection != null) return
        preferences.edit().remove("pendingAudioSelection").apply()
        updateState { it.copy(recoveryWarnings = it.recoveryWarnings.filterNot { warning -> warning == INTERRUPTED_AUDIO_SELECTION }) }
    }
    fun selectAudio(uri: Uri, title: String) {
        if (mutable.value.job != null || mutable.value.importPending) {
            pickerNotice("Another operation is active. Wait for it to finish, then choose the recording again.")
            return
        }
        val selection = AudioSelection(uri, title, ++selectionSequence)
        audioSelection = selection
        val recoveryMarkerSaved = markAudioPickerOpen()
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.filter { it.code >= 32 }?.take(256)
            ?.ifBlank { null } ?: "Selected audio file"
        updateState { it.copy(audioSelected = true, importName = fallback, pickerNotice = null, failed = false,
            recoveryWarnings = it.recoveryWarnings.filterNot { warning -> warning == INTERRUPTED_AUDIO_SELECTION },
            message = if (recoveryMarkerSaved) "Recording selected. Tap Import selected audio to preserve it in the vault."
            else "Recording selected. Keep the app open until the import completes.") }
        viewModelScope.launch(Dispatchers.IO) {
            val name = try {
                getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && it.moveToFirst()) it.getString(column) else null
                }
            } catch (_: Exception) { null }
            val clean = name?.filter { it.code >= 32 }?.take(256)?.ifBlank { null } ?: fallback
            withContext(Dispatchers.Main) {
                if (audioSelection?.sequence == selection.sequence) mutable.update { it.copy(importName = clean) }
            }
        }
    }
    fun clearSelectedAudio() {
        if (mutable.value.job != null || mutable.value.importPending) return
        audioSelection = null; selectionSequence++
        preferences.edit().remove("pendingAudioSelection").apply()
        updateState { it.copy(audioSelected = false, importName = null, pickerNotice = null, failed = false,
            recoveryWarnings = it.recoveryWarnings.filterNot { warning -> warning == INTERRUPTED_AUDIO_SELECTION },
            message = "Selection cleared. Choose an audio file when ready.") }
    }
    fun importSelectedAudio(actor: String) {
        val selection = audioSelection ?: run { notice("Choose an audio file first."); return }
        importAudio(selection.uri, selection.title, actor) {
            if (audioSelection?.sequence == selection.sequence) {
                audioSelection = null; selectionSequence++
                preferences.edit().remove("pendingAudioSelection").apply()
                updateState { it.copy(audioSelected = false,
                    recoveryWarnings = it.recoveryWarnings.filterNot { warning -> warning == INTERRUPTED_AUDIO_SELECTION }) }
            }
        }
    }
    fun importAudio(uri: Uri, title: String, actor: String, completed: () -> Unit = {}) {
        // Result delivery can precede vault initialization after Android recreates
        // this process while a document picker is open. Never drop that result.
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (mutable.value.job != null || mutable.value.importPending) {
                notice("Another operation is active. Wait for it to finish, then choose the recording again.")
                return@launch
            }
            mutable.update { it.copy(importPending = true, importName = "Recording selected", failed = false,
                message = "Recording selected. Opening the encrypted vault…") }
            if (!vaultOpened.await()) {
                mutable.update { it.copy(importPending = false, failed = true,
                    message = "The selected recording was not imported because the vault could not open. Preserve the vault and choose the file again after recovery.") }
                return@launch
            }
            runJob("Preserving selected recording", {
                mutable.update { it.copy(importedCaseId = it.selected) }
                completed()
            }) {
                val resolver = getApplication<Application>().contentResolver
                val filename = try {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && it.moveToFirst()) it.getString(column) else null
                    }
                } catch (_: Exception) { null } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "source-audio"
                val name = filename.filter { it.code >= 32 }.take(256).ifBlank { "source-audio" }
                updateState { it.copy(importName = name, message = "Importing $name into the encrypted vault…") }
                val manifest = checkNotNull(resolver.openInputStream(uri)) { "The selected file could not be read. Choose it again or try the other file picker." }.use {
                    vault.importSource(it, name, title.ifBlank { name }, actor, cancelled::get, reportProgress("bytes preserved"))
                }
                showCase(manifest)
                analyzeAcquired(manifest, actor)
            }
        }
    }
    private fun analyzeAcquired(manifest: JSONObject, actor: String): String {
        return try {
            val overview = workflow.analyze(manifest, actor, cancelled::get, reportProgress("units analyzed"))
            updateState { it.copy(analysis = overview) }
            "Original preserved. Waveform and speech candidates are ready."
        } catch (cancelled: InterruptedException) {
            throw cancelled
        } catch (failure: Exception) {
            "Original preserved. Analysis needs attention. ${failure.message?.take(180) ?: "Review codec and storage limits before retrying."}"
        }
    }
    fun stopRecording() { recordingStop.set(true) }
    fun record(title: String, actor: String, completed: () -> Unit = {}) {
        if (mutable.value.job != null) return
        recordingStop.set(false)
        runJob("Recording microphone", completed) {
            val manifest = captureMicrophone(getApplication(), vault, title, actor, recordingStop::get, cancelled::get, reportProgress("bytes captured"))
            showCase(manifest)
            analyzeAcquired(manifest, actor)
        }
    }
    fun sample(actor: String, completed: () -> Unit) = runJob("Loading synthetic speech", completed) {
        val manifest = getApplication<Application>().assets.open("synthetic-speech.wav").use {
            vault.importSource(it, "SYNTHETIC TEST DATA - NOT EVIDENCE.wav", "Synthetic speech evaluation", actor, cancelled::get)
        }
        showCase(manifest)
        analyzeAcquired(manifest, actor)
    }
    fun importCase(uri: Uri, actor: String, expected: String, completed: () -> Unit) {
        if (!mutable.value.ready || mutable.value.job != null) return
        val password = transferPassphrase.toCharArray()
        runJob("Verifying and importing case", completed) {
            try {
                val manifest = checkNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use {
                    ProtectedTransfer.import(vault, it, actor, expected.trim().lowercase().ifBlank { null }, password, cancelled::get, reportProgress("bytes checked"))
                }
                restoreWorkspace(manifest)
                "Case transferred into the encrypted vault. ${if (expected.isBlank()) "Internal consistency checked." else "The independent package hash matched."}"
            } finally {
                password.fill('\u0000')
                viewModelScope.launch(Dispatchers.Main.immediate) { transferPassphrase = "" }
            }
        }
    }
    fun analyze(actor: String) = runJob("Analyzing audio") {
        val overview = workflow.analyze(selected(), actor, cancelled::get, reportProgress("units processed"))
        updateState { it.copy(analysis = overview, detailedSpectrum = null) }
        "Waveform, spectral overview and candidate speech regions are ready."
    }
    fun inspectSpectrum(actor: String, settings: DetailSpectrumSettings) {
        runJob("Inspecting selected spectrum") {
            val spectrum = workflow.inspectSpectrum(selected(), actor, settings, cancelled::get, reportProgress("frames read"))
            updateState { it.copy(detailedSpectrum = spectrum) }
            "Detailed spectrum saved for the selected interval."
        }
    }
    fun inspectFullSpectrum(actor: String) {
        runJob("Scanning full recording spectrum") {
            val result = workflow.inspectFullSpectrum(selected(), actor, cancelled::get, reportProgress("frames scanned"))
            updateState { it.copy(fullSpectrum = result) }
            "Full-recording spectral overview saved. Use detailed inspection for finer time and frequency resolution."
        }
    }
    fun measureNoise(actor: String, startMs: Long, endMs: Long) {
        runJob("Measuring selected noise floor") {
            val result = workflow.measureNoise(selected(), actor, startMs, endMs, cancelled::get, reportProgress("frames read"))
            updateState { it.copy(noiseFloorMeasurement = result) }
            "Noise-floor measurement saved. Review the threshold before rendering."
        }
    }
    fun compareAudio(actor: String, audioId: String) {
        runJob("Comparing audio revisions") {
            val result = workflow.compareAudio(selected(), actor, audioId, cancelled::get, reportProgress("frames analyzed"))
            updateState { it.copy(comparisonAnalysis = result, comparisonAudioId = audioId) }
            "Comparison is ready. Review the overlay and near-full-scale counts."
        }
    }
    fun renderPlan(actor: String, baseId: String, regions: List<RenderSettings>, clearVoice: ClearVoicePlacement? = null, completed: () -> Unit = {}) {
        queueProcessing(actor, LocalJobKind.RENDER, baseId, regions, clearVoice, completed = completed)
    }
    fun renderPlanAndTranscribe(actor: String, baseId: String, regions: List<RenderSettings>, clearVoice: ClearVoicePlacement?, completed: () -> Unit) {
        val whisper = transcriptionProvider == TranscriptionProvider.WHISPER
        if (whisper && !companionConfigured()) return
        queueProcessing(actor, if (whisper) LocalJobKind.RENDER else LocalJobKind.RENDER_TRANSCRIBE, baseId, regions, clearVoice,
            if (whisper) "WHISPER" else "VOSK") {
            if (whisper) transcribe(actor, completed) else completed()
        }
    }

    fun timelineNote(actor: String, startMs: Long, endMs: Long?, text: String) = runJob("Saving timeline annotation") {
        val manifest = selected()
        require(startMs >= 0 && (endMs == null || endMs > startMs) && text.isNotBlank() && text.length <= 4000)
        vault.recordEvent(manifest, actor, if (endMs == null) "BOOKMARK" else "ANNOTATION", JSONObject()
            .put("audioArtifactId", manifest.getString("activeArtifact")).put("startMs", startMs)
            .put("endMs", endMs ?: JSONObject.NULL).put("text", text))
        "Timeline annotation saved in custody history."
    }
    fun saveSpeechCandidate(actor: String, startMs: Long, endMs: Long, label: String, eventId: String? = null) = runJob("Saving speech candidate revision") {
        val manifest = selected()
        val parent = workflow.savedAnalysisArtifact(manifest) ?: error("Analyze this revision first")
        val previous = workflow.readJson(manifest, parent, 1024 * 1024)
        val analysis = reviseSpeechCandidate(previous, startMs, endMs, label, eventId, "manual-${java.util.UUID.randomUUID()}")
        vault.addDerivative(manifest, actor, "ANALYSIS", listOf(manifest.getString("activeArtifact"),parent.getString("id")), "Examiner speech regions.json", "examiner-speech-region-edit", "1", maxBytes=1024*1024) { out ->
            out.write(EvidenceVault.canonical(analysis)); JSONObject().put("startMs",startMs).put("endMs",endMs).put("label",label).put("adjustedEventId",eventId ?: JSONObject.NULL)
        }
        updateState { it.copy(analysis=analysis) }
        "Speech candidate saved as a new analysis revision."
    }
    fun playbackAt(actor: String, millis: Long) {
        val p = player
        if (p == null) play(actor, startSeconds = millis / 1000.0) else p.seekTo(millis)
    }
    fun togglePlayback(actor: String) {
        val p = player
        if (p == null) play(actor) else if (p.isPlaying) p.pause() else p.play()
    }
    fun enhance(actor: String, settings: RenderSettings) {
        queueProcessing(actor, LocalJobKind.RENDER, regions = listOf(settings))
    }

    fun revision(actor: String, id: String) = runJob("Selecting audio revision") {
        val manifest = selected()
        vault.selectRevision(manifest, actor, id)
        val overview = workflow.savedAnalysis(manifest)
        val fullSpectrum = workflow.savedFullSpectrum(manifest)
        updateState { it.copy(analysis = overview, fullSpectrum = fullSpectrum, detailedSpectrum = null, noiseFloorMeasurement = null, transcript = null, transcriptRevision = null) }
        "Active revision changed. Earlier versions remain in the case."
    }
    fun transcribe(actor: String, completed: () -> Unit = {}) {
        if (transcriptionProvider == TranscriptionProvider.WHISPER) {
            prepareRemote(actor, "openai-whisper") { result ->
                runJob("Opening Whisper draft", completed) {
                    val case = selected(); openTranscript(case, result)
                    "Whisper draft saved. Review words and timing against the linked audio."
                }
            }
        } else queueProcessing(actor, LocalJobKind.TRANSCRIBE, requestedProvider = "VOSK", completed = completed)
    }

    fun transcribeSelected(actor: String, enhanceFirst: Boolean, completed: () -> Unit = {}) {
        if (!enhanceFirst) { transcribe(actor, completed); return }
        val whisper = transcriptionProvider == TranscriptionProvider.WHISPER
        if (whisper && !companionConfigured()) return
        queueProcessing(actor, if (whisper) LocalJobKind.CLEARVOICE else LocalJobKind.CLEARVOICE_TRANSCRIBE,
            requestedProvider = if (whisper) "WHISPER" else "VOSK") {
            if (whisper) transcribe(actor, completed) else completed()
        }
    }

    fun enhanceClearVoice(actor: String, completed: (String) -> Unit = {}) {
        queueProcessing(actor, LocalJobKind.CLEARVOICE) { completed(selected().getString("activeArtifact")) }
    }

    private fun companionConfigured(): Boolean = try {
        CompanionConfig(companionAddress, companionToken); true
    } catch (failure: IllegalArgumentException) {
        notice(failure.message ?: "Configure the companion in Settings"); false
    }

    private fun prepareRemote(actor: String, provider: String, baseId: String? = null, completed: (JSONObject) -> Unit) {
        if (remoteApproval != null) return
        val configuration = try { CompanionConfig(companionAddress, companionToken) }
            catch (failure: IllegalArgumentException) { notice(failure.message ?: "Configure the companion in Settings"); return }
        var prepared: RemoteBatch? = null
        runJob("Preparing audio for approval", completed = {
            val batch = checkNotNull(prepared)
            remoteAction = RemoteAction(actor, batch, completed); remoteApproval = batch
        }) {
            val manifest = selected()
            if (baseId != null && manifest.getString("activeArtifact") != baseId) vault.selectRevision(manifest, actor, baseId)
            prepared = RemoteWorkflow(vault, workflow).prepare(manifest, actor, configuration, provider, cancelled::get, reportProgress("frames prepared"))
            "Audio prepared and hashed. No audio has been sent; review the submission."
        }
    }
    fun cancelRemoteApproval() {
        remoteApproval = null; remoteAction = null
        notice("Submission cancelled. No audio was sent; prepared artifacts remain in the encrypted case.")
    }
    fun approveRemoteSubmission() {
        val action = remoteAction ?: return
        remoteAction = null; remoteApproval = null
        var result: JSONObject? = null
        runJob("Transcribing with Whisper API", completed = { action.completed(checkNotNull(result)) }) {
            val manifest = selected()
            result = RemoteWorkflow(vault, workflow).execute(manifest, action.actor, action.batch, cancelled::get, reportProgress("chunks completed"))
            "Whisper draft saved. Original and earlier revisions are preserved."
        }
    }
    private fun openTranscript(case: JSONObject, artifact: JSONObject) {
        val transcript = workflow.readTranscript(case, artifact)
        updateState { it.copy(transcript = transcript, transcriptRevision = artifact.getString("id")) }
    }
    fun openTranscript() = runJob("Opening transcript", refreshCases = false) {
        val case = selected(); openTranscript(case, case.latestTranscript() ?: error("This case has no transcript yet"))
        "Saved transcript opened."
    }
    fun review(actor: String, segment: String, text: String, label: String, verified: Boolean, note: String) {
        val draftKey = "${mutable.value.selected}/${mutable.value.transcriptRevision}/$segment"
        runJob("Saving examiner revision", completed = { resetReviewDraft(draftKey) }) {
            val case = selected()
            val parent = case.artifact(mutable.value.transcriptRevision ?: error("Open a transcript first"))
            val transcript = workflow.review(case, actor, parent, segment, text, label, verified, note)
            updateState { it.copy(transcript = transcript, transcriptRevision = case.latestTranscript()?.getString("id")) }
            if (verified) "This segment is marked as reviewed by the examiner." else "Edits saved as a separate revision."
        }
    }
    fun workload(actor: String, status: String) = runJob("Saving workload state") {
        require(status in setOf("NEW", "IN_REVIEW", "COMPLETE"))
        vault.recordEvent(selected(), actor, "WORKLOAD_UPDATE", JSONObject().put("status", status))
        "Workload status saved locally. It travels with an explicit case transfer."
    }
    fun verify(actor: String) = runJob("Verifying evidence and audit") {
        val case = selected(); val result = vault.verify(case, cancelled::get)
        val pin = trustedCheckpointKey.trim().lowercase()
        if (pin.isNotEmpty()) {
            require(pin.matches(Regex("[a-f0-9]{64}"))) { "Enter an independently trusted 64-character signer SHA-256" }
            check(vault.verifyCheckpoints(case, cancelled::get).any { checkpoint ->
                checkpoint.keySha256 == pin && case.items("artifacts").drop(checkpoint.coveredArtifacts).all {
                    it.getJSONObject("operation").getString("algorithm") == org.echoscript.security.SignedCheckpoint.ALGORITHM
                }
            }) { "No trusted checkpoint covers the current evidence revisions. Review the signer or create a new checkpoint." }
        }
        vault.recordEvent(case, actor, "VERIFICATION", JSONObject().put("result", "PASS").put("trustedCheckpointKey", if (pin.isEmpty()) JSONObject.NULL else pin))
        if (pin.isEmpty()) result else "$result Independently supplied signer fingerprint matched; review audit events outside the signed snapshot."
    }
    fun checkpoint(actor: String) = runJob("Creating device-signed checkpoint") {
        val artifact = DeviceCheckpoint(getApplication()).create(vault, selected(), actor, cancelled::get)
        "Device checkpoint saved. Verify the public-key fingerprint through an independent trusted channel. ${artifact.operationParameters().getString("keySha256")}"
    }
    fun export(uri: Uri, actor: String, format: ExportFormat) {
        if (!mutable.value.ready || mutable.value.job != null) return
        val password = exportPassphrase.toCharArray()
        runJob("Exporting ${format.label}") {
            try {
                val case = selected()
                val resolver = getApplication<Application>().contentResolver
                val hash = checkNotNull(resolver.openOutputStream(uri, "w")) { "Could not open the export destination" }.use { output ->
                    when (format) {
                        ExportFormat.PROTECTED_CASE -> ProtectedTransfer.export(vault, case, actor, output, password, cancelled::get)
                        ExportFormat.CASE -> vault.exportPackage(case, output, actor, cancelled::get)
                        ExportFormat.WAV -> workflow.exportArtifact(case, actor, workflow.prepareAudio(case, actor, cancelled::get, {}), output, cancelled::get)
                        ExportFormat.CHANGELOG -> exportProcessingHistory(workflow, vault, case, actor, output, cancelled::get)
                        else -> exportDocument(workflow, vault, case, actor, format, output, cancelled::get)
                    }
                }
                "Export saved. SHA-256 $hash"
            } finally {
                password.fill('\u0000')
                viewModelScope.launch(Dispatchers.Main.immediate) { clearExportSecret() }
            }
        }
    }
    fun play(actor: String, id: String? = null, startSeconds: Double = 0.0) = runJob("Preparing authenticated playback", { startPlayback(id, startSeconds) }) {
        val case = selected()
        val artifact = id?.let(case::artifact) ?: case.activeAudio()
        require(artifact.getString("type") in setOf("SOURCE", "DECODE", "ENHANCED_AUDIO"))
        vault.recordEvent(case, actor, "PLAYBACK_REQUEST", JSONObject().put("artifactId", artifact.getString("id")).put("startSeconds", startSeconds))
        "Playback requested for ${artifact.getString("name")}."
    }
    private fun startPlayback(id: String?, startSeconds: Double) {
        if (mutable.value.job != null || !mutable.value.ready) return
        try {
            val case = selected(); val artifact = id?.let(case::artifact) ?: case.activeAudio()
            require(artifact.getString("type") in setOf("SOURCE", "DECODE", "ENHANCED_AUDIO"))
            stop()
            player = ExoPlayer.Builder(getApplication()).build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { notice("Playback could not decode or authenticate the selected audio revision.") }
                })
                val data = ProgressiveMediaSource.Factory { VaultDataSource(vault, case.getString("caseId"), artifact.getString("id")) }
                p.setMediaSource(data.createMediaSource(MediaItem.fromUri("echoscript://selected-audio")))
                p.prepare()
                if (startSeconds.isFinite() && startSeconds > 0) p.seekTo((startSeconds * 1000).toLong())
                p.play()
            }
        } catch (_: Exception) { notice("Choose a playable audio revision.") }
    }
    fun stop() { player?.release(); player = null }
    override fun onCleared() { cancelled.set(true); stop(); super.onCleared() }
}

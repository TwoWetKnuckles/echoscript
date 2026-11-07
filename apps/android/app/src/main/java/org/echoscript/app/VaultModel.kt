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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal data class ReviewDraft(val text: String, val label: String, val verified: Boolean = false)

internal data class VaultScreen(
    val ready: Boolean = false,
    val cases: List<JSONObject> = emptyList(),
    val selected: String? = null,
    val job: String? = null,
    val message: String = "Opening the encrypted vault…",
    val recoveryWarnings: List<String> = emptyList(),
    val analysis: JSONObject? = null,
    val transcript: JSONObject? = null,
    val transcriptRevision: String? = null,
    val failed: Boolean = false,
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
    private val cancelled = AtomicBoolean(false)
    private val recordingStop = AtomicBoolean(false)
    private lateinit var vault: EvidenceVault
    private lateinit var workflow: OfflineWorkflow
    private val preferences = application.getSharedPreferences("echoscript_workspace", 0)
    var player by mutableStateOf<ExoPlayer?>(null); private set
    var reviewDrafts by mutableStateOf<Map<String, ReviewDraft>>(emptyMap()); private set
    var acquisitionTitle by mutableStateOf("")
    var caseQuery by mutableStateOf("")
    var reviewEditorKey by mutableStateOf<String?>(null)
    var transferPassphrase by mutableStateOf("")
    var exportPassphrase by mutableStateOf("")
    var exportPassphraseConfirmation by mutableStateOf("")
    var trustedCheckpointKey by mutableStateOf("")

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
                val listing = vault.listCases()
                val interrupted = preferences.getString("pendingJob", null)
                updateState { VaultScreen(
                    ready = true,
                    cases = listing.cases,
                    recoveryWarnings = listing.warnings + listOfNotNull(interrupted?.let {
                        "Interrupted task · $it. Review the saved case before retrying."
                    }),
                    message = "Choose a case or import a recording.",
                ) }
            } catch (_: Exception) {
                updateState { VaultScreen(message = "The device vault could not open. Preserve the existing evidence and keys.") }
            }
        }
    }

    fun select(id: String) {
        if (mutable.value.job != null || id == mutable.value.selected) return
        runJob("Opening case", refreshCases = false) {
            val manifest = mutable.value.cases.first { it.getString("caseId") == id }
            restoreWorkspace(manifest)
            "Saved workspace opened."
        }
    }
    fun cancel() { cancelled.set(true); updateState { it.copy(message = "Cancelling after the current block…") } }
    fun notice(message: String) { updateState { it.copy(message = message) } }
    private fun selected(): JSONObject = mutable.value.cases.firstOrNull { it.getString("caseId") == mutable.value.selected }
        ?.let { JSONObject(it.toString()) } ?: error("Select a case first")

    private fun runJob(
        name: String,
        completed: () -> Unit = {},
        refreshCases: Boolean = true,
        action: () -> String,
    ) {
        if (!mutable.value.ready || mutable.value.job != null) return
        stop(); cancelled.set(false)
        updateState { it.copy(job = name, message = name, failed = false) }
        viewModelScope.launch(Dispatchers.IO) {
            var succeeded = false
            val message = try {
                check(preferences.edit().putString("pendingJob", name).commit()) { "Could not record task recovery state" }
                action().also { succeeded = true }
            }
            catch (_: InterruptedException) { "Cancelled. Completed originals and revisions are preserved." }
            catch (_: LinkageError) { "The bundled speech library could not load on this Android device." }
            catch (failure: Exception) { failure.message?.take(350) ?: "Operation could not complete. Review the case before retrying." }
            var refreshFailed = false
            val listing = try {
                if (refreshCases) vault.listCases() else null
            } catch (_: Exception) {
                refreshFailed = true
                null
            }
            preferences.edit().remove("pendingJob").commit()
            withContext(Dispatchers.Main) {
                updateState { current ->
                    current.copy(
                        cases = listing?.cases ?: current.cases,
                        recoveryWarnings = listing?.warnings ?: current.recoveryWarnings,
                        job = null,
                        failed = !succeeded || refreshFailed,
                        message = if (refreshFailed) "Vault refresh failed. Reopen the app to check the saved case." else message,
                    )
                }
                if (succeeded && !refreshFailed) completed()
            }
        }
    }
    private fun showCase(manifest: JSONObject) {
        updateState { it.copy(selected = manifest.getString("caseId"), analysis = null, transcript = null, transcriptRevision = null) }
    }
    private fun restoreWorkspace(manifest: JSONObject) {
        showCase(manifest)
        val overview = workflow.savedAnalysis(manifest)
        updateState { it.copy(analysis = overview) }
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
    fun importAudio(uri: Uri, title: String, actor: String, completed: () -> Unit) = runJob("Preserving source", completed) {
        val resolver = getApplication<Application>().contentResolver
        val filename = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null } ?: "source-audio"
        val name = filename.filter { it.code >= 32 }.take(256).ifBlank { "source-audio" }
        val manifest = checkNotNull(resolver.openInputStream(uri)) { "The selected recording could not be opened" }.use {
            vault.importSource(it, name, title.ifBlank { name }, actor, cancelled::get, reportProgress("bytes preserved"))
        }
        showCase(manifest)
        analyzeAcquired(manifest, actor)
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
        updateState { it.copy(analysis = overview) }
        "Waveform, spectral overview and candidate speech regions are ready."
    }
    fun enhance(actor: String, settings: RenderSettings) = runJob("Rendering audio revision") {
        val artifact = workflow.enhance(selected(), actor, settings, cancelled::get, reportProgress("units processed"))
        updateState { it.copy(analysis = null) }
        "New WAV revision saved. SHA-256 ${artifact.getString("sha256")}."
    }
    fun revision(actor: String, id: String) = runJob("Selecting audio revision") {
        val manifest = selected()
        vault.selectRevision(manifest, actor, id)
        val overview = workflow.savedAnalysis(manifest)
        updateState { it.copy(analysis = overview) }
        "Active revision changed. Earlier versions remain in the case."
    }
    fun transcribe(actor: String, completed: () -> Unit = {}) = runJob("Transcribing on this device", completed) {
        val case = selected()
        val artifact = workflow.transcribe(case, actor, cancelled::get, reportProgress("units processed"))
        openTranscript(case, artifact)
        val overview = workflow.savedAnalysis(case)
        updateState { it.copy(analysis = overview) }
        "Draft transcript saved. Review the words, timestamps and speaker groups."
    }
    fun renderAndTranscribe(actor: String, settings: RenderSettings, completed: () -> Unit) =
        runJob("Rendering and transcribing", completed) {
            val manifest = selected()
            workflow.enhance(manifest, actor, settings, cancelled::get, reportProgress("units rendered"))
            updateState { it.copy(analysis = null, transcript = null, transcriptRevision = null) }
            val artifact = workflow.transcribe(manifest, actor, cancelled::get, reportProgress("units transcribed"))
            openTranscript(manifest, artifact)
            val overview = workflow.savedAnalysis(manifest)
            updateState { it.copy(analysis = overview) }
            "Draft transcript saved. Review the words, timestamps and speaker groups."
        }
    private fun openTranscript(case: JSONObject, artifact: JSONObject) {
        val transcript = workflow.readTranscript(case, artifact)
        updateState { it.copy(transcript = transcript, transcriptRevision = artifact.getString("id")) }
    }
    fun openTranscript() = runJob("Opening transcript", refreshCases = false) {
        val case = selected(); openTranscript(case, case.latestTranscript() ?: error("This case has no transcript yet"))
        "Saved transcript opened."
    }
    fun review(actor: String, segment: String, text: String, label: String, verified: Boolean) {
        val draftKey = "${mutable.value.selected}/${mutable.value.transcriptRevision}/$segment"
        runJob("Saving examiner revision", completed = { resetReviewDraft(draftKey) }) {
            val case = selected()
            val parent = case.artifact(mutable.value.transcriptRevision ?: error("Open a transcript first"))
            val transcript = workflow.review(case, actor, parent, segment, text, label, verified)
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

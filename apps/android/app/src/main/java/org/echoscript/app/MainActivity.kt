package org.echoscript.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VaultModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(VaultScreen())
    val state = mutable.asStateFlow()
    private val cancelled = AtomicBoolean(false)
    private val recordingStop = AtomicBoolean(false)
    private lateinit var vault: EvidenceVault
    private lateinit var workflow: OfflineWorkflow
    var player by mutableStateOf<ExoPlayer?>(null); private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                vault = EvidenceVault(application)
                workflow = OfflineWorkflow(application, vault)
                val listing = vault.listCases()
                mutable.value = VaultScreen(ready = true, cases = listing.cases, recoveryWarnings = listing.warnings, message = "Choose a case or import a recording.")
            } catch (_: Exception) {
                mutable.value = VaultScreen(message = "The device vault could not open. Preserve the existing evidence and keys.")
            }
        }
    }

    fun select(id: String) {
        if (mutable.value.job != null) return
        stop()
        mutable.update { it.copy(selected = id, analysis = null, transcript = null, transcriptRevision = null, message = "Case selected.") }
    }
    fun cancel() { cancelled.set(true); mutable.update { it.copy(message = "Cancelling after the current block…") } }
    fun notice(message: String) { mutable.update { it.copy(message = message) } }
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
        mutable.update { it.copy(job = name, message = name) }
        viewModelScope.launch(Dispatchers.IO) {
            var succeeded = false
            val message = try { action().also { succeeded = true } }
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
            withContext(Dispatchers.Main) {
                mutable.update { current ->
                    current.copy(
                        cases = listing?.cases ?: current.cases,
                        recoveryWarnings = listing?.warnings ?: current.recoveryWarnings,
                        job = null,
                        message = if (refreshFailed) "Vault refresh failed. Reopen the app to check the saved case." else message,
                    )
                }
                if (succeeded && !refreshFailed) completed()
            }
        }
    }
    private fun showCase(manifest: JSONObject) {
        mutable.update { it.copy(selected = manifest.getString("caseId"), analysis = null, transcript = null, transcriptRevision = null) }
    }
    private fun reportProgress(unit: String): (Long) -> Unit {
        var last = 0L
        return { count ->
            val now = System.nanoTime()
            if (now - last >= 500_000_000 && !cancelled.get()) {
                last = now
                mutable.update { it.copy(message = "${it.job} · $count $unit") }
            }
        }
    }
    fun importAudio(uri: Uri, title: String, actor: String, completed: () -> Unit) = runJob("Preserving source", completed) {
        val resolver = getApplication<Application>().contentResolver
        val filename = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "source-audio"
        val name = filename.filter { it.code >= 32 }.take(256).ifBlank { "source-audio" }
        val manifest = checkNotNull(resolver.openInputStream(uri)) { "The selected recording could not be opened" }.use {
            vault.importSource(it, name, title.ifBlank { name }, actor, cancelled::get, reportProgress("bytes preserved"))
        }
        showCase(manifest)
        "Original preserved and verified by SHA-256 ${manifest.activeAudio().getString("sha256")}."
    }
    fun stopRecording() { recordingStop.set(true) }
    fun record(title: String, actor: String) {
        if (mutable.value.job != null) return
        recordingStop.set(false)
        runJob("Recording microphone") {
            val manifest = captureMicrophone(getApplication(), vault, title, actor, recordingStop::get, cancelled::get, reportProgress("bytes captured"))
            showCase(manifest)
            "Microphone acquisition preserved and hashed. Open Analyze to process the recording."
        }
    }
    fun sample(actor: String, completed: () -> Unit) = runJob("Loading synthetic speech", completed) {
        val manifest = getApplication<Application>().assets.open("synthetic-speech.wav").use {
            vault.importSource(it, "SYNTHETIC TEST DATA - NOT EVIDENCE.wav", "Synthetic speech evaluation", actor, cancelled::get)
        }
        showCase(manifest); "Synthetic speech is ready in Analyze."
    }
    fun importCase(uri: Uri, actor: String, expected: String, completed: () -> Unit) = runJob("Verifying and importing case", completed) {
        val manifest = checkNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use {
            vault.importPackage(it, actor, expected.trim().lowercase().ifBlank { null }, cancelled::get, reportProgress("bytes checked"))
        }
        showCase(manifest)
        "Case transferred into the encrypted vault. ${if (expected.isBlank()) "Internal consistency checked." else "The independent package hash matched."}"
    }
    fun analyze(actor: String) = runJob("Analyzing audio") {
        val overview = workflow.analyze(selected(), actor, cancelled::get, reportProgress("units processed"))
        mutable.update { it.copy(analysis = overview) }
        "Waveform, spectral overview and candidate speech regions are ready."
    }
    fun enhance(actor: String, settings: RenderSettings) = runJob("Rendering audio revision") {
        val artifact = workflow.enhance(selected(), actor, settings, cancelled::get, reportProgress("units processed"))
        mutable.update { it.copy(analysis = null) }
        "New WAV revision saved. SHA-256 ${artifact.getString("sha256")}."
    }
    fun revision(actor: String, id: String) = runJob("Selecting audio revision") {
        vault.selectRevision(selected(), actor, id)
        mutable.update { it.copy(analysis = null) }
        "Active revision changed. Earlier versions remain in the case."
    }
    fun transcribe(actor: String) = runJob("Transcribing on this device") {
        val case = selected()
        val artifact = workflow.transcribe(case, actor, cancelled::get, reportProgress("units processed"))
        openTranscript(case, artifact)
        "Draft transcript saved. Review the words, timestamps and speaker groups."
    }
    private fun openTranscript(case: JSONObject, artifact: JSONObject) {
        val transcript = workflow.readTranscript(case, artifact)
        mutable.update { it.copy(transcript = transcript, transcriptRevision = artifact.getString("id")) }
    }
    fun openTranscript() = runJob("Opening transcript", refreshCases = false) {
        val case = selected(); openTranscript(case, case.latestTranscript() ?: error("This case has no transcript yet"))
        "Saved transcript opened."
    }
    fun review(actor: String, segment: String, text: String, label: String, verified: Boolean) = runJob("Saving examiner revision") {
        val case = selected()
        val parent = case.artifact(mutable.value.transcriptRevision ?: error("Open a transcript first"))
        val transcript = workflow.review(case, actor, parent, segment, text, label, verified)
        mutable.update { it.copy(transcript = transcript, transcriptRevision = case.latestTranscript()?.getString("id")) }
        if (verified) "This segment is marked as reviewed by the examiner." else "Edits saved as a separate revision."
    }
    fun workload(actor: String, status: String) = runJob("Saving workload state") {
        require(status in setOf("NEW", "IN_REVIEW", "COMPLETE"))
        vault.recordEvent(selected(), actor, "WORKLOAD_UPDATE", JSONObject().put("status", status))
        "Workload status saved locally. It travels with an explicit case transfer."
    }
    fun verify(actor: String) = runJob("Verifying evidence and audit") {
        val case = selected(); val result = vault.verify(case, cancelled::get)
        vault.recordEvent(case, actor, "VERIFICATION", JSONObject().put("result", "PASS")); result
    }
    fun export(uri: Uri, actor: String, format: ExportFormat) = runJob("Exporting ${format.label}") {
        val case = selected()
        val resolver = getApplication<Application>().contentResolver
        val hash = checkNotNull(resolver.openOutputStream(uri, "w")) { "Could not open the export destination" }.use { output ->
            when (format) {
                ExportFormat.CASE -> vault.exportPackage(case, output, actor, cancelled::get)
                ExportFormat.WAV -> workflow.exportArtifact(case, actor, workflow.prepareAudio(case, actor, cancelled::get, {}), output, cancelled::get)
                else -> exportDocument(workflow, vault, case, actor, format, output, cancelled::get)
            }
        }
        "Export saved. SHA-256 $hash"
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { VaultApp() } }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun VaultApp(model: VaultModel = viewModel()) {
    val screen by model.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? ComponentActivity
    val busy = screen.job != null
    val selected = screen.cases.firstOrNull { it.getString("caseId") == screen.selected }
    var tab by rememberSaveable { mutableStateOf("Cases") }
    var title by rememberSaveable { mutableStateOf("") }
    var actor by rememberSaveable { mutableStateOf("EXAMINER") }
    var expectedHash by remember { mutableStateOf("") }
    var exportType by rememberSaveable { mutableStateOf(ExportFormat.CASE.name) }
    var acknowledge by remember(screen.selected) { mutableStateOf(false) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.record(title, actor) else model.notice("Microphone permission was declined. File import is available.")
    }
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importAudio(it, if (tab == "Acquire") title else "", actor) { tab = "Analyze" } }
    }
    val chooseAudio = { importAudio.launch(arrayOf("audio/*", "video/mp4", "video/webm", "application/octet-stream")) }
    val importCase = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importCase(it, actor, expectedHash) { tab = "Cases" } }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data?.let { model.export(it, actor, ExportFormat.valueOf(exportType)) }
    }
    DisposableEffect(busy) {
        if (busy) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(activity) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) model.stopRecording()
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer); model.stopRecording(); model.stop() }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("EchoScript", style = MaterialTheme.typography.headlineMedium)
            Text("On-device workbench · development preview", color = MaterialTheme.colorScheme.primary)
            for (pages in listOf(listOf("Cases", "Acquire", "Analyze"), listOf("Transcript", "Audit", "Export"))) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (page in pages) {
                        FilterChip(selected = tab == page, onClick = { tab = page }, label = { Text(page) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            Text(screen.message, style = MaterialTheme.typography.bodySmall)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (screen.job == "Recording microphone") Button(onClick = model::stopRecording) { Text("Stop and preserve recording") }
                OutlinedButton(onClick = model::cancel) { Text("Cancel current operation") }
            }
            val activePlayer = model.player
            if (activePlayer != null) {
                AndroidView(factory = { context -> PlayerView(context).apply { this.player = activePlayer } }, update = { it.player = activePlayer }, modifier = Modifier.fillMaxWidth().height(90.dp))
                TextButton(onClick = model::stop) { Text("Close player") }
            }
            val pageScroll = key(tab) { rememberScrollState() }
            Column(Modifier.weight(1f).verticalScroll(pageScroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (warning in screen.recoveryWarnings.take(10)) Text(warning, color = MaterialTheme.colorScheme.error)
                val ready = screen.ready && !busy && actor.isNotBlank()
                when (tab) {
                    "Acquire" -> {
                        OutlinedTextField(title, { title = it.take(256) }, label = { Text("Case title (required for microphone)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(actor, { actor = it.take(128) }, label = { Text("Examiner identifier") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = chooseAudio, enabled = ready) { Text("Choose audio file") }
                        Text("The vault preserves original files up to 4 GiB. Processing accepts sources up to 750 MB, subject to codec support and space for decoded audio.")
                        OutlinedButton(onClick = { microphone.launch(android.Manifest.permission.RECORD_AUDIO) }, enabled = ready && title.isNotBlank()) { Text("Record from microphone") }
                    }
                    "Cases" -> {
                        Text("Cases on this device", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = chooseAudio, enabled = ready) { Text("Choose audio file") }
                        if (screen.cases.isEmpty()) Text("Import a recording or try the included sample.")
                        OutlinedButton(onClick = { model.sample(actor) { tab = "Analyze" } }, enabled = ready) { Text("Load synthetic speech sample") }
                        for (case in screen.cases.take(200)) {
                            val id = case.getString("caseId")
                            val workload = remember(case) {
                                val audit = case.getJSONArray("audit")
                                (audit.length() - 1 downTo 0).asSequence().map(audit::getJSONObject)
                                    .firstOrNull { it.getString("action") == "WORKLOAD_UPDATE" }
                                    ?.getJSONObject("details")?.optString("status", "NEW") ?: "NEW"
                            }
                            OutlinedCard(onClick = { model.select(id); tab = "Analyze" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(case.getString("title"), style = MaterialTheme.typography.titleMedium)
                                    Text("${if (id == screen.selected) "Selected · " else ""}${workload.replace('_', ' ')} · ${case.getJSONArray("artifacts").length()} artifacts")
                                }
                            }
                        }
                        if (selected != null) {
                            Text("Set local workload status")
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                for (status in listOf("NEW", "IN_REVIEW", "COMPLETE")) TextButton(onClick = { model.workload(actor, status) }, enabled = ready) { Text(status.replace('_', ' ')) }
                            }
                        }
                        HorizontalDivider()
                        Text("Transfer a case to this device", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(expectedHash, { expectedHash = it.take(64) }, label = { Text("Independently received SHA-256 (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { importCase.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = ready) { Text("Choose .echocase package") }
                        Text("Transfer is explicit. Existing case histories are protected from replacement.")
                    }
                    "Analyze" -> if (selected == null) Text("Select or import a case first.") else AnalysisScreen(model, screen, selected, actor, ready)
                    "Transcript" -> if (selected == null) Text("Select or import a case first.") else TranscriptScreen(model, screen, actor, ready, selected.latestTranscript() != null)
                    "Audit" -> {
                        Button(onClick = { model.verify(actor) }, enabled = ready && selected != null) { Text("Verify all hashes and custody records") }
                        selected?.let { case ->
                            Text("Original SHA-256\n${case.items("artifacts").first { it.getString("type") == "SOURCE" }.getString("sha256")}")
                            for (entry in case.items("audit").takeLast(40).asReversed()) Text("${entry.getInt("seq")} · ${entry.getString("action")}\n${entry.getString("timestampUtc")} · ${entry.getString("actor")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    "Export" -> {
                        Text("Files are written to a destination you choose. The case ZIP includes the original, derivatives and custody history. Exported files are plaintext. Keep them in approved storage and retain their hashes independently.")
                        Row { Checkbox(acknowledge, { acknowledge = it }); Text("I understand and will protect exported files.", Modifier.padding(top = 12.dp)) }
                        ExportOptions(ready && selected != null && acknowledge, selected?.latestTranscript() != null) { format ->
                            exportType = format.name
                            export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(format.mime).putExtra(Intent.EXTRA_TITLE, "echoscript.${format.extension}"))
                        }
                    }
                }
                HorizontalDivider()
                Text("Synthetic evaluation only. Human review and case-specific custody remain essential. Beta gate not passed.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ExportOptions(ready: Boolean, hasTranscript: Boolean, export: (ExportFormat) -> Unit) {
    for (format in listOf(ExportFormat.CASE, ExportFormat.WAV)) {
        OutlinedButton(onClick = { export(format) }, enabled = ready) { Text(format.label) }
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = ready && hasTranscript) { Text("Transcript and report formats") }
        DropdownMenu(expanded = expanded && ready && hasTranscript, onDismissRequest = { expanded = false }) {
            for (format in ExportFormat.entries.filter { it.transcript }) {
                DropdownMenuItem(text = { Text(format.label) }, onClick = { expanded = false; export(format) })
            }
        }
    }
}

@Composable
private fun AnalysisScreen(model: VaultModel, screen: VaultScreen, selected: JSONObject, actor: String, ready: Boolean) {
    var gain by remember(screen.selected) { mutableStateOf("6") }
    var highPass by remember(screen.selected) { mutableStateOf("80") }
    var lowPass by remember(screen.selected) { mutableStateOf("") }
    var low by remember(screen.selected) { mutableStateOf("0") }
    var mid by remember(screen.selected) { mutableStateOf("0") }
    var high by remember(screen.selected) { mutableStateOf("0") }
    var hum by remember(screen.selected) { mutableStateOf(0.0) }
    var notch by remember(screen.selected) { mutableStateOf("") }
    var q by remember(screen.selected) { mutableStateOf("25") }
    var floor by remember(screen.selected) { mutableStateOf("") }
    var start by remember(screen.selected) { mutableStateOf("") }
    var end by remember(screen.selected) { mutableStateOf("") }
    var limiter by remember(screen.selected) { mutableStateOf(true) }
    var advanced by remember { mutableStateOf(false) }
    Text(selected.getString("title"), style = MaterialTheme.typography.titleLarge)
    Text("Active revision · ${selected.activeAudio().getString("name")}")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Button(onClick = { model.analyze(actor) }, enabled = ready) { Text("Analyze audio") }
        TextButton(onClick = { model.play(actor) }, enabled = ready) { Text("Play active audio") }
    }
    screen.analysis?.let { analysis ->
        val peaks = analysis.getJSONArray("peaks")
        val spectrum = analysis.getJSONArray("spectrum")
        val color = MaterialTheme.colorScheme.primary
        Text("${clock(analysis.getLong("durationMs"))} · Peak ${"%.1f".format(Locale.ROOT, analysis.getDouble("peakDb"))} dBFS · ${analysis.getLong("clippedSamples")} near-full-scale samples")
        Canvas(Modifier.fillMaxWidth().height(62.dp)) {
            for (i in 0 until peaks.length()) {
                val x = size.width * i / peaks.length()
                val y = size.height * peaks.getDouble(i).toFloat() / 2
                drawLine(color, Offset(x, size.height / 2 - y), Offset(x, size.height / 2 + y), 1.5f)
            }
        }
        Text("Spectral overview · time left to right, frequency bottom to top", style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(82.dp)) {
            for (x in 0 until spectrum.length()) {
                val tile = spectrum.getJSONArray(x)
                val width = size.width / spectrum.length()
                val height = size.height / tile.length()
                for (y in 0 until tile.length()) {
                    val opacity = ((tile.getDouble(y) + 85) / 85).toFloat().coerceIn(0.04f, 1f)
                    drawRect(color.copy(alpha = opacity), Offset(width * x, size.height - height * (y + 1)), Size(width + 0.2f, height + 0.2f))
                }
            }
        }
        for (event in analysis.items("events").take(12)) {
            val first = event.getLong("startMs"); val last = event.getLong("endMs")
            TextButton(onClick = { start = first.toString(); end = last.toString() }, enabled = ready) { Text("Select speech candidate ${clock(first)} – ${clock(last)}") }
        }
    }
    NumberField(gain, { gain = it }, "Gain (dB)")
    NumberField(highPass, { highPass = it }, "High-pass (Hz, blank to bypass)")
    NumberField(start, { start = it }, "Selection start (ms, optional)")
    NumberField(end, { end = it }, "Selection end (ms, optional)")
    Row { Switch(limiter, { limiter = it }); Text("Limit peaks", Modifier.padding(10.dp)) }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide additional filters" else "Additional filters and EQ") }
    if (advanced) {
        for ((value, set, label) in listOf(Triple(low, { v: String -> low = v }, "Low EQ dB"), Triple(mid, { v: String -> mid = v }, "Mid EQ dB"), Triple(high, { v: String -> high = v }, "High EQ dB"), Triple(lowPass, { v: String -> lowPass = v }, "Low-pass Hz"), Triple(notch, { v: String -> notch = v }, "Tonal notch Hz"), Triple(q, { v: String -> q = v }, "Notch Q"), Triple(floor, { v: String -> floor = v }, "Quiet-region attenuation threshold dBFS"))) NumberField(value, set, label)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (hz in listOf(0.0, 50.0, 60.0)) FilterChip(hum == hz, { hum = hz }, { Text(if (hz == 0.0) "Hum off" else "${hz.toInt()} Hz hum") })
        }
    }
    Button(onClick = {
        try {
            fun number(value: String) = if (value.isBlank()) 0.0 else value.toDouble()
            model.enhance(actor, RenderSettings(number(gain), number(low), number(mid), number(high), number(highPass), number(lowPass), hum,
                number(notch), q.toDouble(), floor.ifBlank { null }?.toDouble(), if (floor.isBlank()) 0.0 else 12.0, limiter,
                start.ifBlank { "0" }.toLong(), end.ifBlank { null }?.toLong()))
        } catch (_: Exception) { model.notice("Check the filter values and the selected interval.") }
    }, enabled = ready) { Text("Render enhanced revision") }
    Text("Audio revisions", style = MaterialTheme.typography.titleMedium)
    selected.items("artifacts").filter { it.getString("type") in setOf("SOURCE", "DECODE", "ENHANCED_AUDIO") }.takeLast(25).asReversed().forEach { artifact ->
        TextButton(onClick = { model.revision(actor, artifact.getString("id")) }, enabled = ready && artifact.getString("id") != selected.getString("activeArtifact")) { Text("Revision ${artifact.getInt("revision")} · ${artifact.getString("name")}") }
    }
}

@Composable
private fun NumberField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, { change(it.take(18)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}

@Composable
private fun TranscriptScreen(model: VaultModel, screen: VaultScreen, actor: String, ready: Boolean, hasTranscript: Boolean) {
    Text("Offline English transcription", style = MaterialTheme.typography.titleLarge)
    Text("Uses the bundled model on this device. Review draft words, timings and speaker groups before relying on them.")
    Button(onClick = { model.transcribe(actor) }, enabled = ready) { Text("Transcribe active audio on device") }
    TextButton(onClick = model::openTranscript, enabled = ready && hasTranscript) { Text("Open saved transcript") }
    val transcript = screen.transcript ?: return
    val segments = transcript.items("segments")
    if (segments.isEmpty()) { Text("The decoder returned no words. Listen to the audio and assess it independently."); return }
    var index by remember(screen.selected) { mutableIntStateOf(0) }
    val current = index.coerceIn(0, segments.lastIndex)
    val segment = segments[current]
    val start = segment.getDouble("start")
    val end = segment.getDouble("end")
    val id = segment.getString("id")
    var text by remember(screen.transcriptRevision, id) { mutableStateOf(segment.getString("text")) }
    var label by remember(screen.transcriptRevision, id) { mutableStateOf(segment.optString("speakerLabel", "")) }
    var verified by remember(screen.transcriptRevision, id) { mutableStateOf(false) }
    HorizontalDivider()
    Text("${transcript.getString("state")} · segment ${current + 1} of ${segments.size}")
    Row {
        TextButton(onClick = { index = current - 1 }, enabled = current > 0) { Text("Previous") }
        TextButton(onClick = { index = current + 1 }, enabled = current < segments.lastIndex) { Text("Next") }
        val audio = transcript.optString("audioArtifactId", "")
        TextButton(onClick = { model.play(actor, audio, start) }, enabled = ready && audio.isNotBlank() && start >= 0) { Text("Play from timestamp") }
    }
    Text("${clock((start * 1000).toLong())} – ${clock((end * 1000).toLong())} · ${if (segment.isNull("speaker")) "Unassigned speaker" else segment.getString("speaker")}")
    segment.optJSONObject("confidence")?.let { Text("${it.getString("semantics")} ${it.getDouble("value")} · uncalibrated") }
    val flags = segment.getJSONArray("flags")
    if (flags.length() > 0) Text((0 until flags.length()).joinToString(" · ") { flags.getString(it) }, color = MaterialTheme.colorScheme.error)
    val alternatives = segment.items("alternatives")
    if (alternatives.isEmpty()) Text("No provider-supported alternative hypothesis available.")
    else for (alternative in alternatives) Text("Alternative · ${alternative.getString("text")}${if (alternative.isNull("score")) "" else " · decoder score ${alternative.getDouble("score")}"}")
    OutlinedTextField(text, { text = it.take(10000) }, label = { Text("Examiner transcript text") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(label, { label = it.take(80) }, label = { Text("Examiner speaker label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row { Checkbox(verified, { verified = it }); Text("I listened to and verified this segment.", Modifier.padding(top = 12.dp)) }
    Button(onClick = { model.review(actor, id, text, label, verified) }, enabled = ready) { Text("Save examiner revision") }
}

internal fun clock(millis: Long, separator: String = "."): String {
    val value = millis.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d:%02d%s%03d", value / 3600000, value / 60000 % 60, value / 1000 % 60, separator, value % 1000)
}

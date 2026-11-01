package org.echoscript.app

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

data class VaultScreen(val cases: List<JSONObject> = emptyList(), val selected: String? = null, val busy: Boolean = false, val bytes: Long = 0, val message: String = "Encrypted local vault. Import a non-sensitive test file.")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VaultModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(VaultScreen())
    val state = mutable.asStateFlow()
    private val cancelled = AtomicBoolean(false)
    private var vault: EvidenceVault? = null
    var player: ExoPlayer? = null; private set
    init { viewModelScope.launch(Dispatchers.IO) { try { vault = EvidenceVault(application); mutable.value = VaultScreen(cases = vault!!.listCases()) } catch (_: Exception) { mutable.value = VaultScreen(message = "Keystore/vault could not open. Evidence keys may be unavailable; no plaintext fallback is used.") } } }
    fun select(id: String) { mutable.value = mutable.value.copy(selected = id) }
    fun cancel() { cancelled.set(true) }
    private fun runJob(task: () -> String) {
        if (mutable.value.busy) return
        cancelled.set(false); mutable.value = mutable.value.copy(busy = true, bytes = 0)
        viewModelScope.launch(Dispatchers.IO) {
            try { val result = task(); mutable.value = mutable.value.copy(cases = vault!!.listCases(), message = result) }
            catch (failure: Exception) { mutable.value = mutable.value.copy(message = failure.message ?: "Operation failed; original evidence was not overwritten.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun import(uri: Uri, title: String, actor: String) = runJob {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "source-audio" } ?: "source-audio"
        val manifest = resolver.openInputStream(uri)!!.use { input -> vault!!.importSource(input, name, title, actor, { cancelled.get() }, { n -> mutable.value = mutable.value.copy(bytes = n) }) }
        mutable.value = mutable.value.copy(selected = manifest.getString("caseId"))
        "Imported, SHA-256 hashed and encrypted. Source: " + manifest.getJSONArray("artifacts").getJSONObject(0).getString("sha256")
    }
    private fun selected(): JSONObject = mutable.value.cases.firstOrNull { it.getString("caseId") == mutable.value.selected }?.let { JSONObject(it.toString()) } ?: error("Select a case")
    fun verify() = runJob { vault!!.verify(selected()) }
    fun export(uri: Uri, actor: String) = runJob {
        val manifest = selected(); getApplication<Application>().contentResolver.openOutputStream(uri, "w")!!.use { vault!!.exportPackage(manifest, it, actor) }
        "Case package exported. It is unencrypted and unsigned; store it in a secure location and verify independently."
    }
    fun play(onError: (String) -> Unit) {
        try {
            val m = selected(); val artifact = m.getJSONArray("artifacts").getJSONObject(0)
            player?.release()
            player = ExoPlayer.Builder(getApplication()).build().also { p ->
                p.addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { onError("Playback is unavailable for this codec or evidence authentication failed. Source bytes remain preserved.") } })
                val source = ProgressiveMediaSource.Factory { VaultDataSource(vault!!, m.getString("caseId"), artifact.getString("id")) }.createMediaSource(MediaItem.fromUri("echoscript://source"))
                p.setMediaSource(source); p.prepare(); p.play()
            }
        } catch (_: Exception) { onError("Select an available case before playback.") }
    }
    fun stop() { player?.release(); player = null }
    override fun onCleared() { cancelled.set(true); stop(); super.onCleared() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { VaultApp() } } }
}
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun VaultApp(model: VaultModel = viewModel()) {
    val screen by model.state.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf("Synthetic audio review") }
    var actor by remember { mutableStateOf("TEST_EXAMINER") }
    var tab by remember { mutableStateOf("Cases") }
    var acknowledge by remember { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }
    var playerMessage by remember { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.import(it, title, actor) } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { model.export(it, actor) } }
    DisposableEffect(Unit) { onDispose { model.stop() } }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("EchoScript", style = MaterialTheme.typography.headlineLarge)
            Text("Android local evidence vault · development build", style = MaterialTheme.typography.labelLarge)
            Text("Air-gapped mode — no external transcription performed.", color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (name in listOf("Cases", "Acquire", "Audit", "Export")) TextButton(onClick = { tab = name }) { Text(name) } }
            Text(screen.message, style = MaterialTheme.typography.bodyMedium)
            if (screen.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Processed ${screen.bytes} bytes"); OutlinedButton(onClick = model::cancel) { Text("Cancel acquisition") } }
            when (tab) {
                "Acquire" -> {
                    OutlinedTextField(title, { title = it }, label = { Text("Case title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(actor, { actor = it }, label = { Text("Examiner identifier (locally supplied)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = { importer.launch(arrayOf("audio/*", "video/mp4", "video/webm", "application/octet-stream")) }, enabled = !screen.busy && title.isNotBlank() && actor.isNotBlank()) { Text("Import source file · up to 4 GiB") }
                    Text("Original bytes are SHA-256 hashed and encrypted before playback or decoding. Persistent app-private files and keys are excluded from Android backup. Device reset or key loss makes this vault unrecoverable without a separately exported package.")
                }
                "Cases" -> {
                    if (screen.cases.isEmpty()) Text("No complete case snapshots found. Incomplete acquisitions are not shown as complete.")
                    for (m in screen.cases) OutlinedCard(onClick = { model.select(m.getString("caseId")); showPlayer = false }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) { Text(m.getString("title"), style = MaterialTheme.typography.titleMedium); Text(m.getString("caseId")); Text(if (screen.selected == m.getString("caseId")) "Selected" else "Tap to select") }
                    }
                    Button(onClick = { model.play { playerMessage = it }; showPlayer = true }, enabled = screen.selected != null && !screen.busy) { Text("Play encrypted source") }
                    if (showPlayer && model.player != null) AndroidView(factory = { context -> PlayerView(context).apply { player = model.player } }, update = { it.player = model.player }, modifier = Modifier.fillMaxWidth().height(180.dp))
                    if (playerMessage.isNotBlank()) Text(playerMessage)
                }
                "Audit" -> {
                    Button(onClick = model::verify, enabled = screen.selected != null && !screen.busy) { Text("Verify complete source and audit chain") }
                    screen.cases.firstOrNull { it.getString("caseId") == screen.selected }?.let { Text(it.toString(2), style = MaterialTheme.typography.bodySmall) }
                }
                "Export" -> {
                    Text("This export includes original evidence and case metadata. The ZIP-format .echocase package is unencrypted and unsigned. Choose a protected destination and independently retain a package hash.")
                    Row { Checkbox(acknowledge, { acknowledge = it }); Text("I understand the exported package contains unencrypted evidence.") }
                    Button(onClick = { exporter.launch("echoscript-${screen.selected}.echocase") }, enabled = acknowledge && screen.selected != null && !screen.busy) { Text("Choose destination and export") }
                }
            }
            HorizontalDivider()
            Text("No claim of admissibility or certification. Android analysis/DSP/transcription integration, recovery jobs, microphone capture, signatures and team identity are not complete. Use synthetic test material; beta gate not passed.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

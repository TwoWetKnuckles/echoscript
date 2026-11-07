package org.echoscript.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VaultApp() }
    }
}

@Composable
private fun Glyph(@DrawableRes resource: Int, description: String? = null) {
    Icon(painterResource(resource), description, modifier = Modifier.size(24.dp))
}

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun Acknowledgement(text: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp).weight(1f))
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
private fun VaultApp(model: VaultModel = viewModel()) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("echoscript_preferences", 0) }
    var appearance by rememberSaveable { mutableStateOf(preferences.getString("appearance", "Dark") ?: "Dark") }
    var actor by rememberSaveable { mutableStateOf(preferences.getString("examiner", "EXAMINER") ?: "EXAMINER") }
    val screen by model.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? ComponentActivity
    val view = LocalView.current
    val busy = screen.job != null
    val selected = screen.cases.firstOrNull { it.getString("caseId") == screen.selected }
    var tab by rememberSaveable { mutableStateOf("Cases") }
    val title = model.acquisitionTitle
    var expectedHash by rememberSaveable { mutableStateOf("") }
    var transfer by rememberSaveable { mutableStateOf(false) }
    var exportType by rememberSaveable { mutableStateOf(ExportFormat.CASE.name) }
    // Retain workspace state for this case across navigation/rotation. A new
    // case gets a fresh registry so restored fields cannot cross case boundaries.
    val stateHolder = key(screen.selected) { rememberSaveableStateHolder() }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.record(title.ifBlank { "Microphone ${EvidenceVault.utc()}" }, actor) { tab = "Analyze" }
        else model.notice("Microphone permission was declined. Choose audio file to import a recording.")
    }
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importAudio(it, if (tab == "Acquire") title else "", actor) { tab = "Analyze" } }
    }
    val chooseAudio = { importAudio.launch(arrayOf("audio/*", "video/mp4", "video/webm", "application/octet-stream")) }
    val importCase = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importCase(uri, actor, expectedHash) { transfer = false; tab = "Cases" }
        else model.transferPassphrase = ""
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && destination != null) model.export(destination, actor, ExportFormat.valueOf(exportType))
        else model.clearExportSecret()
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
    LaunchedEffect(tab, screen.selected, screen.ready) {
        if (tab == "Transcript" && screen.ready && !busy && screen.transcript == null && selected?.latestTranscript() != null) model.openTranscript()
    }
    EchoTheme(appearance) {
        val dark = MaterialTheme.colorScheme.background == Color(0xFF0A1017)
        SideEffect {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Scaffold(
            topBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { Glyph(R.drawable.ic_wave) }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("EchoScript", style = MaterialTheme.typography.titleLarge)
                            Text(selected?.getString("title") ?: "Your audio workspace", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { tab = "Audit" }, enabled = !busy && selected != null) { Glyph(R.drawable.ic_audit, "Audit") }
                        IconButton(onClick = { tab = "Settings" }, enabled = !busy) { Glyph(R.drawable.ic_settings, "Settings") }
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                    for ((page, icon) in listOf("Cases" to R.drawable.ic_cases, "Analyze" to R.drawable.ic_wave, "Transcript" to R.drawable.ic_words, "Export" to R.drawable.ic_export)) {
                        NavigationBarItem(selected = tab == page, onClick = { tab = page }, icon = { Glyph(icon) }, label = { Text(page) })
                    }
                }
            },
        ) { padding ->
            stateHolder.SaveableStateProvider(tab) {
                Column(
                    Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val ready = screen.ready && !busy && actor.isNotBlank()
                    for (warning in screen.recoveryWarnings) Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (screen.job != null || screen.message != "Choose a case or import a recording.") {
                        StatusCard(screen, model)
                    }
                    val activePlayer = model.player
                    if (activePlayer != null) {
                        AndroidView(factory = { c -> PlayerView(c).apply { player = activePlayer } }, update = { it.player = activePlayer }, modifier = Modifier.fillMaxWidth().height(90.dp))
                        TextButton(onClick = model::stop) { Text("Close player") }
                    }
                    when (tab) {
                        "Cases" -> {
                            Text("Your case workspace", style = MaterialTheme.typography.headlineSmall)
                            Text("Import. Examine. Review.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = chooseAudio, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                                Glyph(R.drawable.ic_add); Spacer(Modifier.width(10.dp)); Text("Choose audio file")
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { tab = "Acquire" }, enabled = ready) { Text("Record audio") }
                                TextButton(onClick = { transfer = !transfer }, enabled = ready) { Text("Transfer case") }
                            }
                            if (transfer) Section("Open a case package", "Choose protected storage and retain a separate package hash.") {
                                OutlinedTextField(expectedHash, { expectedHash = it.take(64) }, label = { Text("Independently received SHA-256 (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                SecretField(model.transferPassphrase, { model.transferPassphrase = it }, "Transfer passphrase (protected packages)", ready)
                                Button(onClick = { importCase.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = ready) { Text("Choose .echocase package") }
                            }
                            CasesList(model, screen, ready) { model.select(it); tab = "Analyze" }
                            OutlinedButton(onClick = { model.sample(actor) { tab = "Analyze" } }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Load synthetic speech sample") }
                            Text("On device · English speech model included", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        "Acquire" -> Section("Acquire a recording", "Your examiner identifier is saved in Settings. File names supply the case title when left blank.") {
                            OutlinedTextField(title, { model.acquisitionTitle = it.take(256) }, label = { Text("Case title (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(onClick = chooseAudio, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Choose audio file") }
                            OutlinedButton(onClick = { microphone.launch(android.Manifest.permission.RECORD_AUDIO) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Record from microphone") }
                            Text("Source preservation up to 4 GiB. Processing up to 750 MB, subject to codec and private storage limits.", style = MaterialTheme.typography.bodySmall)
                        }
                        "Settings" -> {
                            Text("Make it yours", style = MaterialTheme.typography.headlineSmall)
                            Section("Appearance") {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (mode in listOf("Dark", "Light", "System")) FilterChip(appearance == mode, {
                                        appearance = mode; preferences.edit().putString("appearance", mode).apply()
                                    }, { Text(mode) }, modifier = Modifier.heightIn(min = 48.dp))
                                }
                            }
                            Section("Examiner", "This identifier is supplied locally and recorded with your actions.") {
                                var draft by rememberSaveable(actor) { mutableStateOf(actor) }
                                OutlinedTextField(draft, { draft = it.take(128) }, label = { Text("Examiner identifier") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Button(onClick = { actor = draft.trim(); preferences.edit().putString("examiner", actor).apply(); model.notice("Examiner identifier saved on this device.") }, enabled = draft.isNotBlank() && draft.none { it.code < 32 }) { Text("Save examiner") }
                            }
                            Text("${BuildConfig.VERSION_NAME} · Android 10+", style = MaterialTheme.typography.bodySmall)
                            Text("The vault is encrypted on this device. Keep verified protected case exports before uninstalling or resetting the app. Individual WAV/document exports and legacy case ZIPs are plaintext. Device checkpoints require independently trusted signer fingerprints.", style = MaterialTheme.typography.bodySmall)
                        }
                        else -> if (selected == null) Section("Start with a recording", "Import an audio file or try the included sample from Cases.") {
                            Button(onClick = chooseAudio, enabled = ready) { Text("Choose audio file") }
                        } else when (tab) {
                            "Analyze" -> AnalysisScreen(model, screen, selected, actor, ready) { tab = "Transcript" }
                            "Transcript" -> TranscriptScreen(model, screen, selected, actor, ready) { tab = "Export" }
                            "Audit" -> AuditScreen(model, selected, actor, ready)
                            "Export" -> ExportScreen(model, selected, ready) { format ->
                                exportType = format.name
                                val basename = selected.getString("caseId").take(8)
                                export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(format.mime).putExtra(Intent.EXTRA_TITLE, "echoscript-$basename.${format.extension}"))
                            }
                        }
                    }
                    Text("Development preview · synthetic evaluation · beta gate pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!screen.ready && screen.job == null) Text(screen.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(screen: VaultScreen, model: VaultModel) {
    Surface(color = if (screen.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer { Text(screen.message, style = MaterialTheme.typography.bodySmall) }
            if (screen.job != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row {
                    if (screen.job == "Recording microphone") TextButton(onClick = model::stopRecording) { Text("Stop and preserve recording") }
                    TextButton(onClick = model::cancel) { Text("Cancel current operation") }
                }
            }
        }
    }
}

@Composable
private fun CasesList(model: VaultModel, screen: VaultScreen, ready: Boolean, select: (String) -> Unit) {
    val query = model.caseQuery
    var visible by rememberSaveable(query) { mutableIntStateOf(25) }
    if (screen.cases.isEmpty()) {
        Section("Start with a recording", "Waveform and speech candidates appear automatically after import.") {
            Text("Originals are preserved in your encrypted device vault.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    OutlinedTextField(query, { model.caseQuery = it.take(256) }, placeholder = { Text("Search cases") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    val cases = remember(screen.cases, query) { screen.cases.filter { it.getString("title").contains(query, ignoreCase = true) } }
    Text("${cases.size} cases on this device", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (case in cases.take(visible)) {
        val id = case.getString("caseId")
        val workload = remember(case) {
            val audit = case.getJSONArray("audit")
            (audit.length() - 1 downTo 0).asSequence().map(audit::getJSONObject)
                .firstOrNull { it.getString("action") == "WORKLOAD_UPDATE" }?.getJSONObject("details")?.optString("status", "NEW") ?: "NEW"
        }
        Card(onClick = { select(id) }, enabled = ready, modifier = Modifier.fillMaxWidth().testTag("case-$id")) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(R.drawable.ic_wave)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(case.getString("title"), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${workload.replace('_', ' ')} · ${case.getString("createdAtUtc").take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Glyph(R.drawable.ic_next)
            }
        }
    }
    if (cases.size > visible) TextButton(onClick = { visible += 25 }) { Text("Show more cases") }
    if (cases.isEmpty()) Text("No case titles match your search.")
}

@Composable
private fun AnalysisScreen(model: VaultModel, screen: VaultScreen, selected: JSONObject, actor: String, ready: Boolean, transcript: () -> Unit) {
    var gain by rememberSaveable(screen.selected) { mutableStateOf("3") }
    var highPass by rememberSaveable(screen.selected) { mutableStateOf("80") }
    var lowPass by rememberSaveable(screen.selected) { mutableStateOf("") }
    var low by rememberSaveable(screen.selected) { mutableStateOf("0") }
    var mid by rememberSaveable(screen.selected) { mutableStateOf("0") }
    var high by rememberSaveable(screen.selected) { mutableStateOf("0") }
    var hum by rememberSaveable(screen.selected) { mutableStateOf(0.0) }
    var notch by rememberSaveable(screen.selected) { mutableStateOf("") }
    var q by rememberSaveable(screen.selected) { mutableStateOf("25") }
    var floor by rememberSaveable(screen.selected) { mutableStateOf("") }
    var start by rememberSaveable(screen.selected) { mutableStateOf("") }
    var end by rememberSaveable(screen.selected) { mutableStateOf("") }
    var limiter by rememberSaveable(screen.selected) { mutableStateOf(true) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var candidateLimit by rememberSaveable(screen.selected) { mutableIntStateOf(6) }
    var revisionLimit by rememberSaveable(screen.selected) { mutableIntStateOf(15) }
    fun settings(): RenderSettings {
        fun number(value: String) = value.ifBlank { "0" }.toDouble()
        return RenderSettings(
            gainDb = gain.toDouble(),
            lowDb = number(low), midDb = number(mid), highDb = number(high),
            highPassHz = number(highPass), lowPassHz = number(lowPass),
            humHz = hum, notchHz = number(notch), notchQ = q.toDouble(),
            noiseFloorDb = floor.ifBlank { null }?.toDouble(),
            noiseReductionDb = if (floor.isBlank()) 0.0 else 12.0,
            limit = limiter,
            startMs = start.ifBlank { "0" }.toLong(),
            endMs = end.ifBlank { null }?.toLong(),
        )
    }
    Text("Review audio", style = MaterialTheme.typography.headlineSmall)
    Text("${if (start.isBlank() && end.isBlank()) "Entire recording" else "Selected region"} · gain $gain dB · high-pass ${highPass.ifBlank { "off" }} Hz", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = {
        try { model.renderAndTranscribe(actor, settings(), transcript) }
        catch (_: Exception) { model.notice("Check the filter values and selected interval.") }
    }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Render & transcribe") }
    screen.analysis?.let { analysis ->
        Section("Signal overview", "${clock(analysis.getLong("durationMs"))} · ${"%.1f".format(Locale.ROOT, analysis.getDouble("peakDb"))} dBFS peak · ${analysis.getLong("clippedSamples")} near-full-scale samples") {
            val color = MaterialTheme.colorScheme.primary
            val peaks = analysis.getJSONArray("peaks")
            Canvas(Modifier.fillMaxWidth().height(80.dp).testTag("waveform")) {
                drawLine(color.copy(alpha = .18f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
                for (i in 0 until peaks.length()) {
                    val x = size.width * i / peaks.length()
                    val y = size.height * peaks.getDouble(i).toFloat() / 2
                    drawLine(color, Offset(x, size.height / 2 - y), Offset(x, size.height / 2 + y), 1.5f)
                }
            }
            val spectrum = analysis.getJSONArray("spectrum")
            Canvas(Modifier.fillMaxWidth().height(104.dp).testTag("spectrogram")) {
                for (x in 0 until spectrum.length()) {
                    val tile = spectrum.getJSONArray(x)
                    val width = size.width / spectrum.length()
                    val height = size.height / tile.length()
                    for (y in 0 until tile.length()) {
                        val intensity = ((tile.getDouble(y) + 85) / 85).toFloat().coerceIn(0f, 1f)
                        drawRect(lerp(Color(0xFF13243A), color, intensity), Offset(width * x, size.height - height * (y + 1)), Size(width + .2f, height + .2f))
                    }
                }
            }
            Text("Spectral overview · Hann / FFT 256 · time left to right", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { model.play(actor) }, enabled = ready) { Glyph(R.drawable.ic_play); Spacer(Modifier.width(8.dp)); Text("Play active audio") }
            TextButton(onClick = { model.analyze(actor) }, enabled = ready) { Text("Refresh analysis") }
        }
        val candidates = analysis.items("events")
        if (candidates.isNotEmpty()) Section("Speech candidates", "Choose a region or process the entire recording. Automatic candidates require review.") {
            for (event in candidates.take(candidateLimit)) {
                val first = event.getLong("startMs"); val last = event.getLong("endMs")
                FilterChip(start == first.toString() && end == last.toString(), { start = first.toString(); end = last.toString() }, { Text("${clock(first)} – ${clock(last)}") }, enabled = ready)
            }
            if (candidates.size > candidateLimit) TextButton(onClick = { candidateLimit += 12 }) { Text("Show more speech candidates") }
            if (start.isNotBlank() || end.isNotBlank()) TextButton(onClick = { start = ""; end = "" }) { Text("Use entire recording") }
        }
    } ?: Button(onClick = { model.analyze(actor) }, enabled = ready) { Text("Analyze audio") }
    Section("Enhance a copy", "${if (start.isBlank() && end.isBlank()) "Entire recording" else "Selected region"} · original preserved") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, hz) in listOf("Speech" to 80, "Gentle" to 60, "Hum 50" to 80)) {
                OutlinedButton(onClick = {
                    gain = if (label == "Gentle") "0" else "3"
                    highPass = hz.toString(); hum = if (label == "Hum 50") 50.0 else 0.0
                    low = "0"; mid = "0"; high = "0"; lowPass = ""; notch = ""; q = "25"; floor = ""; limiter = true
                }, enabled = ready, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label) }
            }
        }
        Text("Gain $gain dB · high-pass ${highPass.ifBlank { "off" }} Hz", style = MaterialTheme.typography.labelLarge)
        Slider(value = gain.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(-30f, 30f) ?: 0f, onValueChange = { gain = "%.0f".format(Locale.ROOT, it) }, valueRange = -30f..30f, steps = 59, enabled = ready)
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide filter details" else "Region and filter details") }
        if (advanced) {
            NumberField(gain, { gain = it }, "Gain dB (-30 to 30)")
            NumberField(highPass, { highPass = it }, "High-pass Hz")
            NumberField(start, { start = it }, "Selection start ms (optional)")
            NumberField(end, { end = it }, "Selection end ms (optional)")
            for ((value, set, label) in listOf(Triple(low, { v: String -> low = v }, "Low EQ dB"), Triple(mid, { v: String -> mid = v }, "Mid EQ dB"), Triple(high, { v: String -> high = v }, "High EQ dB"), Triple(lowPass, { v: String -> lowPass = v }, "Low-pass Hz"), Triple(notch, { v: String -> notch = v }, "Tonal notch Hz"), Triple(q, { v: String -> q = v }, "Notch Q"), Triple(floor, { v: String -> floor = v }, "Quiet-region attenuation threshold dBFS"))) NumberField(value, set, label)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (hz in listOf(0.0, 50.0, 60.0)) FilterChip(hum == hz, { hum = hz }, { Text(if (hz == 0.0) "Hum off" else "${hz.toInt()} Hz") })
            }
            Acknowledgement("Limit peaks", limiter, ready) { limiter = it }
        }
        OutlinedButton(onClick = {
            try { model.enhance(actor, settings()) }
            catch (_: Exception) { model.notice("Check the filter values and selected interval.") }
        }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Render enhanced revision") }
        TextButton(onClick = { model.transcribe(actor, transcript) }, enabled = ready) { Text("Transcribe active audio on device") }
    }
    TextButton(onClick = { history = !history }) { Text(if (history) "Hide audio history" else "Audio revision history") }
    if (history) Section("Audio revisions", "Select a previous revision to compare or branch from it.") {
        val revisions = selected.items("artifacts").filter { it.getString("type") in setOf("SOURCE", "DECODE", "ENHANCED_AUDIO") }
        for (artifact in revisions.takeLast(revisionLimit).asReversed()) {
            TextButton(onClick = { model.revision(actor, artifact.getString("id")) }, enabled = ready && artifact.getString("id") != selected.getString("activeArtifact")) { Text("${artifact.getInt("revision")} · ${artifact.getString("name")}") }
        }
        if (revisions.size > revisionLimit) TextButton(onClick = { revisionLimit += 15 }) { Text("Show earlier audio revisions") }
    }
}

@Composable
private fun NumberField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, { change(it.take(18)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun TranscriptScreen(model: VaultModel, screen: VaultScreen, selected: JSONObject, actor: String, ready: Boolean, export: () -> Unit) {
    Text("Transcript review", style = MaterialTheme.typography.headlineSmall)
    val transcript = screen.transcript
    if (transcript == null) {
        Section("Transcribe on this device", "The English model is included. Review draft words, timing and speaker groups against the audio.") {
            Button(onClick = { model.transcribe(actor) }, enabled = ready) { Text("Transcribe active audio on device") }
        }
        return
    }
    if (transcript.optString("audioArtifactId") != selected.getString("activeArtifact")) {
        Text("This transcript refers to a different audio revision. Its playback uses the linked revision.", color = MaterialTheme.colorScheme.error)
    }
    val segments = transcript.items("segments")
    if (segments.isEmpty()) { Text("The decoder returned no words. Listen to the audio and assess it independently."); return }
    var index by rememberSaveable(screen.selected) { mutableIntStateOf(0) }
    val current = index.coerceIn(0, segments.lastIndex)
    val segment = segments[current]
    val start = segment.getDouble("start"); val end = segment.getDouble("end"); val id = segment.getString("id")
    val draftKey = "${screen.selected}/${screen.transcriptRevision}/$id"
    val draft = model.reviewDrafts[draftKey] ?: ReviewDraft(segment.getString("text"), segment.optString("speakerLabel", ""))
    val text = draft.text
    val label = draft.label
    val verified = draft.verified
    val edit = model.reviewEditorKey == draftKey
    var details by rememberSaveable(screen.selected) { mutableStateOf(false) }
    val dirty = text != segment.getString("text") || label != segment.optString("speakerLabel", "") || verified
    Section("Segment ${current + 1} of ${segments.size}", "${clock((start * 1000).toLong())} – ${clock((end * 1000).toLong())}") {
        val speaker = segment.optString("speakerLabel").ifBlank { if (segment.isNull("speaker")) "Unassigned speaker" else segment.getString("speaker") }
        Text(speaker, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = { model.reviewEditorKey = if (edit) null else draftKey }) { Text(if (edit) "Hide editor" else "Edit and verify segment") }
        SelectionContainer { Text(segment.getString("text"), style = MaterialTheme.typography.bodyLarge) }
        Text(segment.getString("verification").replace('_', ' '), style = MaterialTheme.typography.labelSmall)
        Button(onClick = { model.play(actor, transcript.optString("audioArtifactId"), start) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Glyph(R.drawable.ic_play); Spacer(Modifier.width(8.dp)); Text("Play from timestamp") }
        if (segments.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { index = current - 1 }, enabled = ready && current > 0 && !dirty) { Text("Previous") }
            TextButton(onClick = { index = current + 1 }, enabled = ready && current < segments.lastIndex && !dirty) { Text("Next") }
        }
        if (edit) {
            OutlinedTextField(text, { model.updateReviewDraft(draftKey, draft.copy(text = it.take(10000))) }, label = { Text("Examiner transcript text") }, enabled = ready, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(label, { model.updateReviewDraft(draftKey, draft.copy(label = it.take(80))) }, label = { Text("Examiner speaker label") }, enabled = ready, singleLine = true, modifier = Modifier.fillMaxWidth())
            Acknowledgement("I listened to and verified this segment.", verified, ready) { model.updateReviewDraft(draftKey, draft.copy(verified = it)) }
            Button(onClick = { model.review(actor, id, text, label, verified) }, enabled = ready) { Text("Save examiner revision") }
            Text("Save a revision to retain edits through an app or process restart.", style = MaterialTheme.typography.bodySmall)
            if (dirty) TextButton(onClick = { model.resetReviewDraft(draftKey) }, enabled = ready) { Text("Reset unsaved edits") }
        }
        if (dirty) Text("Save or reset edits before changing segments.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { details = !details }) { Text(if (details) "Hide acoustic details" else "Acoustic details") }
        if (details) {
            segment.optJSONObject("confidence")?.let { Text("${it.getString("semantics")} ${it.getDouble("value")} · uncalibrated") }
            for (flag in (0 until segment.getJSONArray("flags").length()).map { segment.getJSONArray("flags").getString(it) }) Text(flag.replace('_', ' '), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val alternatives = segment.items("alternatives")
            if (alternatives.isEmpty()) Text("No provider-supported alternative hypothesis available.")
            for (alternative in alternatives) Text("${alternative.getString("text")}${if (alternative.isNull("score")) "" else " · decoder score ${alternative.getDouble("score")}"}")
        }
    }
    OutlinedButton(onClick = export, enabled = ready && !dirty, modifier = Modifier.fillMaxWidth()) { Text("Continue to export") }
}

@Composable
private fun AuditScreen(model: VaultModel, selected: JSONObject, actor: String, ready: Boolean) {
    Text("Evidence and custody", style = MaterialTheme.typography.headlineSmall)
    Button(onClick = { model.verify(actor) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Verify all hashes and custody records") }
    OutlinedTextField(model.trustedCheckpointKey, { model.trustedCheckpointKey = it.take(64) },
        label = { Text("Independently trusted signer SHA-256 (optional)") }, enabled = ready, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { model.checkpoint(actor) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Create device-signed checkpoint") }
    selected.items("artifacts").lastOrNull { it.getJSONObject("operation").getString("algorithm") == org.echoscript.security.SignedCheckpoint.ALGORITHM }?.let { artifact ->
        Section("Device signer SHA-256", "A device key attests the saved snapshot. This does not authenticate the examiner or later audit events. Retain this fingerprint independently.") {
            SelectionContainer { Text(artifact.operationParameters().getString("keySha256"), style = MaterialTheme.typography.bodySmall) }
        }
    }
    Section("Original SHA-256", "Retain an independent trusted checkpoint.") {
        SelectionContainer { Text(selected.items("artifacts").first { it.getString("type") == "SOURCE" }.getString("sha256"), style = MaterialTheme.typography.bodySmall) }
    }
    Section("Local workload") {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (status in listOf("NEW", "IN_REVIEW", "COMPLETE")) TextButton(onClick = { model.workload(actor, status) }, enabled = ready) { Text(status.replace('_', ' ')) }
        }
    }
    Section("Custody history", "${selected.getJSONArray("audit").length()} append-only events") {
        for (entry in selected.items("audit").takeLast(50).asReversed()) {
            Text("${entry.getInt("seq")} · ${entry.getString("action").replace('_', ' ')}", style = MaterialTheme.typography.labelLarge)
            Text("${entry.getString("timestampUtc")} · ${entry.getString("actor")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExportScreen(model: VaultModel, selected: JSONObject, ready: Boolean, export: (ExportFormat) -> Unit) {
    var acknowledge by rememberSaveable(selected.getString("caseId")) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    Text("Save your work", style = MaterialTheme.typography.headlineSmall)
    Section("Protect a case transfer", "Passphrase-protected streaming encryption. Save the passphrase and package hash through separate trusted channels. Losing the passphrase makes this export unavailable.") {
        Acknowledgement("I understand and will protect exported files.", acknowledge, ready) { acknowledge = it }
        SecretField(model.exportPassphrase, { model.exportPassphrase = it }, "New transfer passphrase", ready)
        SecretField(model.exportPassphraseConfirmation, { model.exportPassphraseConfirmation = it }, "Confirm transfer passphrase", ready)
        val valid = model.exportPassphrase.length in 16..1024 && model.exportPassphrase.isNotBlank() && model.exportPassphrase == model.exportPassphraseConfirmation
        Button(onClick = { export(ExportFormat.PROTECTED_CASE) }, enabled = ready && acknowledge && valid, modifier = Modifier.fillMaxWidth()) { Text("Protected case package") }
        Text("Use 16 or more unpredictable characters. Passphrases stay in app memory and are cleared after a transfer operation. Only saved case revisions are exported.", style = MaterialTheme.typography.bodySmall)
    }
    Section("Individual and legacy plaintext exports", "Choose an approved protected destination. These exports do not have container encryption. Signed checkpoints, when present, cover their stated historical snapshots.") {
        OutlinedButton(onClick = { export(ExportFormat.CASE) }, enabled = ready && acknowledge, modifier = Modifier.fillMaxWidth()) { Text("Case package") }
        OutlinedButton(onClick = { export(ExportFormat.WAV) }, enabled = ready && acknowledge, modifier = Modifier.fillMaxWidth()) { Text("Active audio WAV") }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = ready && acknowledge && selected.latestTranscript() != null, modifier = Modifier.fillMaxWidth()) { Text("Transcript and report formats") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                for (format in ExportFormat.entries.filter { it.transcript }) DropdownMenuItem(text = { Text(format.label) }, onClick = { expanded = false; export(format) })
            }
        }
        Text("A case package includes original audio, derivatives, transcript revisions and custody history.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SecretField(value: String, change: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, { change(it.take(1024)) }, label = { Text(label) }, enabled = enabled,
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth())
}

internal fun clock(millis: Long, separator: String = "."): String {
    val value = millis.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d:%02d%s%03d", value / 3600000, value / 60000 % 60, value / 1000 % 60, separator, value % 1000)
}

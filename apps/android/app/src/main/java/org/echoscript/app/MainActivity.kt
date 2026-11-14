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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    WorkspaceCard(title, subtitle, content)
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
    val busy = screen.job != null || screen.importPending || model.remoteApproval != null
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
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.notice(if (granted) "Processing notifications enabled." else "Progress remains available in the app. You can enable notifications in Android settings.")
    }
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            model.pickerNotice("No recording was selected. Choose a file or try the other file picker.")
        } else try {
            val uri = selectedAudioUri(result.data)
            if (uri == null) model.pickerNotice("The file picker returned no recording. Try Choose with another file picker.")
            else model.selectAudio(uri, if (tab == "Acquire") title else "")
        } catch (failure: IllegalArgumentException) {
            model.pickerNotice(failure.message ?: "Choose one recording at a time.")
        } catch (_: RuntimeException) {
            model.pickerNotice("The picker returned an unreadable selection. Choose the file again or try the other picker.")
        }
    }
    // Providers sometimes omit or mislabel audio MIME types. Select any openable
    // file; actual decoding validates the bytes after original preservation.
    val chooseAudio = {
        model.markAudioPickerOpen()
        try { importAudio.launch(audioPickerIntent(Intent.ACTION_OPEN_DOCUMENT)) }
        catch (_: android.content.ActivityNotFoundException) { model.pickerNotice("No document picker is available. Try Choose with another file picker.") }
        catch (_: SecurityException) { model.pickerNotice("Android denied access to this document picker. Try the other picker or check your device settings.") }
        catch (_: IllegalStateException) { model.pickerNotice("The document picker could not open yet. Wait a moment and try again.") }
    }
    val chooseOtherAudio = {
        model.markAudioPickerOpen()
        try { importAudio.launch(audioPickerIntent(Intent.ACTION_GET_CONTENT)) }
        catch (_: android.content.ActivityNotFoundException) { model.pickerNotice("No compatible file picker is installed or permitted on this device.") }
        catch (_: SecurityException) { model.pickerNotice("Android denied access to this picker. Try the other picker or check your device settings.") }
        catch (_: IllegalStateException) { model.pickerNotice("The file picker could not open yet. Wait a moment and try again.") }
    }
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
    LaunchedEffect(screen.importedCaseId) {
        screen.importedCaseId?.let { id -> tab = "Analyze"; model.acknowledgeImportedCase(id) }
    }
    LaunchedEffect(tab, screen.selected, screen.ready) {
        if (tab == "Transcript" && screen.ready && !busy && screen.transcript == null && selected?.latestTranscript() != null) model.openTranscript()
    }
    EchoTheme(appearance) {
        SubmissionApproval(model)
        val dark = MaterialTheme.colorScheme.background == Color(0xFF0B1219)
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
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val ready = screen.ready && !busy && actor.isNotBlank()
            if (screen.audioSelected) {
                Surface(color = if (screen.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Selected recording", style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(screen.importName ?: "Selected audio file", maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        if (busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(screen.message, style = MaterialTheme.typography.bodySmall)
                            if (screen.job != null) TextButton(onClick = model::cancel) { Text("Cancel current operation") }
                        } else {
                            Text(screen.message, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { model.importSelectedAudio(actor) }, enabled = ready) { Text(if (screen.failed) "Retry import" else "Import selected audio") }
                                TextButton(onClick = model::clearSelectedAudio, enabled = ready) { Text("Clear") }
                            }
                        }
                    }
                }
            } else if (screen.pickerNotice != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(screen.pickerNotice ?: "Choose an audio file again.", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::dismissPickerNotice) { Text("Dismiss") }
                    }
                }
            } else if (INTERRUPTED_AUDIO_SELECTION in screen.recoveryWarnings) {
                Surface(color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp)) {
                        Text(INTERRUPTED_AUDIO_SELECTION, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = chooseAudio, enabled = ready) { Text("Choose file again") }
                            TextButton(onClick = model::dismissInterruptedSelection) { Text("Dismiss") }
                        }
                    }
                }
            }
            stateHolder.SaveableStateProvider(tab) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    for (warning in screen.recoveryWarnings.filterNot { it == INTERRUPTED_AUDIO_SELECTION }) Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    screen.importName?.let { name ->
                        if (!screen.audioSelected && tab == "Analyze") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Imported recording", style = MaterialTheme.typography.labelSmall)
                            SelectionContainer { Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } else if (!screen.audioSelected && tab in listOf("Cases", "Acquire")) Section("Imported recording") { SelectionContainer { Text(name) } }
                    }
                    if (!screen.audioSelected && screen.pickerNotice == null && (screen.job != null || screen.importPending || screen.message != "Choose a case or import a recording.")) {
                        if (tab == "Analyze" && !screen.failed && screen.job == null && !screen.importPending)
                            Text(screen.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else StatusCard(screen, model, actor)
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
                            TextButton(onClick = chooseOtherAudio, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Choose with another file picker") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { tab = "Acquire" }, enabled = ready) { Text("Record audio") }
                                TextButton(onClick = { transfer = !transfer; if (!transfer) model.transferPassphrase = "" }, enabled = ready) { Text("Transfer case") }
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
                            TextButton(onClick = chooseOtherAudio, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Choose with another file picker") }
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
                            if (android.os.Build.VERSION.SDK_INT >= 33) Section("Processing notifications", "Keep local job progress and cancellation within reach while using other apps.") {
                                OutlinedButton(onClick = {
                                    if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                                        context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                                    else notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }) { Text("Manage processing notifications") }
                            }
                            Section("Companion processing", "Optional Whisper connection") {
                                CompanionSettings(model, ready)
                            }
                            Section("Examiner", "This identifier is supplied locally and recorded with your actions.") {
                                var draft by rememberSaveable(actor) { mutableStateOf(actor) }
                                OutlinedTextField(draft, { draft = it.take(128) }, label = { Text("Examiner identifier") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Button(onClick = { actor = draft.trim(); preferences.edit().putString("examiner", actor).apply(); model.notice("Examiner identifier saved on this device.") }, enabled = ready && draft.isNotBlank() && draft.none { it.code < 32 }) { Text("Save examiner") }
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
                            "Export" -> ExportScreen(model, selected, actor, ready) { format ->
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(screen: VaultScreen, model: VaultModel, actor: String) {
    Surface(color = if (screen.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer { Text(screen.message, style = MaterialTheme.typography.bodySmall) }
            if (screen.lastBackgroundJobId != null && screen.job == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { model.retryProcessing(actor) }) { Text("Retry saved job") }
                    TextButton(onClick = model::dismissProcessingFailure) { Text("Dismiss job notice") }
                }
            }
            if (screen.importPending) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (screen.job != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    AudioWorkbench(model, screen, selected, actor, ready, transcript)
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
        Section("Transcribe active audio", "Choose the route before starting. Review draft words and timestamps against the audio.") {
            ProviderControls(model, ready, placementSelection = false)
            Button(onClick = {
                model.transcribeSelected(actor, model.clearVoiceEnabled)
            }, enabled = ready) { Text(if (model.transcriptionProvider == TranscriptionProvider.VOSK && !model.clearVoiceEnabled) "Transcribe active audio on device" else "Prepare transcription") }
        }
        return
    }
    if (transcript.optString("audioArtifactId") != selected.getString("activeArtifact")) {
        Text("This transcript refers to a different audio revision. Its playback uses the linked revision.", color = MaterialTheme.colorScheme.error)
    }
    val segments = transcript.items("segments")
    if (segments.isEmpty()) { Text("The decoder returned no words. Listen to the audio and assess it independently."); return }
    var search by remember(screen.selected) { mutableStateOf("") }
    var flaggedOnly by rememberSaveable(screen.selected) { mutableStateOf(false) }
    var lowScores by rememberSaveable(screen.selected) { mutableStateOf(false) }
    var index by rememberSaveable(screen.selected) { mutableIntStateOf(0) }
    OutlinedTextField(search,{search=it.take(256)},label={Text("Find transcript text or notes")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    Row { FilterChip(flaggedOnly,{flaggedOnly=!flaggedOnly},{Text("Flagged only")}); Spacer(Modifier.width(8.dp)); FilterChip(lowScores,{lowScores=!lowScores},{Text("Low returned scores")}) }
    val matches=segments.indices.filter { i ->
        val item=segments[i]; val score=item.optJSONObject("confidence")?.optDouble("value",Double.NaN)?:Double.NaN
        (item.getString("text").contains(search,ignoreCase=true) || item.optString("examinerNote", "").contains(search,ignoreCase=true)) && (!flaggedOnly || item.optJSONArray("flags")?.length()?.let{it>0}==true) && (!lowScores || (score.isFinite()&&score<.7))
    }
    Text("${matches.size} matching segments; returned scores are not calibrated probabilities.",style=MaterialTheme.typography.bodySmall)
    for(i in matches.take(30)) TextButton(onClick={if(model.reviewDrafts.isEmpty()) index=i else model.notice("Save or reset the current review draft before jumping")},enabled=ready) {
        val item=segments[i]; val text=item.getString("text"); val note=item.optString("examinerNote", "")
        Text("${i+1}: ${if(search.isNotBlank() && !text.contains(search,ignoreCase=true) && note.contains(search,ignoreCase=true)) "Note: ${note.take(80)}" else text.take(80)}")
    }
    val current = index.coerceIn(0, segments.lastIndex)
    val segment = segments[current]
    val start = segment.getDouble("start"); val end = segment.getDouble("end"); val id = segment.getString("id")
    val draftKey = "${screen.selected}/${screen.transcriptRevision}/$id"
    val draft = model.reviewDrafts[draftKey] ?: ReviewDraft(segment.getString("text"), segment.optString("speakerLabel", ""), note=segment.optString("examinerNote", ""))
    val text = draft.text
    val label = draft.label
    val note = draft.note
    val verified = draft.verified
    val edit = model.reviewEditorKey == draftKey
    var details by rememberSaveable(screen.selected) { mutableStateOf(false) }
    val dirty = text != segment.getString("text") || label != segment.optString("speakerLabel", "") || note != segment.optString("examinerNote", "") || verified
    Section("Segment ${current + 1} of ${segments.size}", "${clock((start * 1000).toLong())} – ${clock((end * 1000).toLong())}") {
        val speaker = segment.optString("speakerLabel").ifBlank { if (segment.isNull("speaker")) "Unassigned speaker" else segment.getString("speaker") }
        Text(speaker, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = { model.reviewEditorKey = if (edit) null else draftKey }) { Text(if (edit) "Hide editor" else "Edit and verify segment") }
        SelectionContainer { Text(segment.getString("text"), style = MaterialTheme.typography.bodyLarge) }
        segment.optString("examinerNote", "").takeIf { it.isNotEmpty() }?.let { savedNote ->
            SelectionContainer { Text("Examiner note: $savedNote", style = MaterialTheme.typography.bodySmall) }
        }
        Text(segment.getString("verification").replace('_', ' '), style = MaterialTheme.typography.labelSmall)
        Button(onClick = { model.play(actor, transcript.optString("audioArtifactId"), start) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Glyph(R.drawable.ic_play); Spacer(Modifier.width(8.dp)); Text("Play from timestamp") }
        if (segments.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { index = current - 1 }, enabled = ready && current > 0 && !dirty) { Text("Previous") }
            TextButton(onClick = { index = current + 1 }, enabled = ready && current < segments.lastIndex && !dirty) { Text("Next") }
        }
        if (edit) {
            OutlinedTextField(text, { model.updateReviewDraft(draftKey, draft.copy(text = it.take(10000))) }, label = { Text("Examiner transcript text") }, enabled = ready, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(label, { model.updateReviewDraft(draftKey, draft.copy(label = it.take(80))) }, label = { Text("Examiner speaker label") }, enabled = ready, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { model.updateReviewDraft(draftKey, draft.copy(note = it.take(4000))) }, label = { Text("Examiner note (not spoken words)") }, enabled = ready, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                for(token in listOf("[inaudible]","[unintelligible]","[crosstalk]","[pause]","[background noise]")) TextButton(onClick={model.updateReviewDraft(draftKey,draft.copy(text=(text+" "+token).trim().take(10000)))},enabled=ready) { Text(token) }
            }
            Acknowledgement("I listened to and verified this segment.", verified, ready) { model.updateReviewDraft(draftKey, draft.copy(verified = it)) }
            Button(onClick = { model.review(actor, id, text, label, verified, note) }, enabled = ready && dirty) { Text("Save examiner revision") }
            Text("Notes are separate from spoken words. Saving only a note does not verify the segment.", style = MaterialTheme.typography.bodySmall)
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
        Section("Device signer SHA-256", "A device key signs the saved snapshot. This does not authenticate the examiner or later audit events. Retain this fingerprint independently.") {
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
private fun ExportScreen(model: VaultModel, selected: JSONObject, actor: String, ready: Boolean, export: (ExportFormat) -> Unit) {
    var acknowledge by rememberSaveable(selected.getString("caseId")) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    Text("Save your work", style = MaterialTheme.typography.headlineSmall)
    Section("Protect a case transfer", "Passphrase-protected streaming encryption. Save the passphrase and package hash through separate trusted channels. Losing the passphrase makes this export unavailable.") {
        OutlinedButton(onClick = { model.checkpoint(actor) }, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Create device-signed checkpoint") }
        Text("Optional: sign the saved revisions before transfer. Retain the signer fingerprint independently; a device signature does not identify the examiner.", style = MaterialTheme.typography.bodySmall)
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
        OutlinedButton(onClick = { export(ExportFormat.CHANGELOG) }, enabled = ready && acknowledge, modifier = Modifier.fillMaxWidth()) { Text("Processing history JSON") }
        Text("Processing history is a point-in-time snapshot. Export a new one after additional changes; individual exports are not encrypted.", style = MaterialTheme.typography.bodySmall)
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
    // Visibility is deliberately not saved: rotation or navigation hides it again.
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, { change(it.take(1024)) }, label = { Text(label) }, enabled = enabled,
        singleLine = true, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "${if (visible) "Hide" else "Show"} $label" }) {
                Text(if (visible) "Hide" else "Show")
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth())
}

internal fun clock(millis: Long, separator: String = "."): String {
    val value = millis.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d:%02d%s%03d", value / 3600000, value / 60000 % 60, value / 1000 % 60, separator, value % 1000)
}

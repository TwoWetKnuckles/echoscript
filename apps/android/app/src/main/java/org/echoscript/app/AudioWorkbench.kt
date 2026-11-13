package org.echoscript.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

internal fun moveProcessingRegion(regions: JSONArray, from: Int, to: Int): JSONArray {
    require(from in 0 until regions.length() && to in 0 until regions.length())
    val items = (0 until regions.length()).map { JSONObject(regions.getJSONObject(it).toString()) }.toMutableList()
    items.add(to, items.removeAt(from))
    return JSONArray(items)
}

// Follow the recorded audio parent, not the previous chronological artifact.
internal fun audioRevisionParent(revisions: List<JSONObject>, currentId: String): String? {
    val ids = revisions.map { it.getString("id") }.toSet()
    val current = revisions.firstOrNull { it.getString("id") == currentId } ?: return null
    val parents = current.getJSONArray("parents")
    return (0 until parents.length()).map { parents.getString(it) }.firstOrNull { it in ids }
}
internal fun audioRevisionChildren(revisions: List<JSONObject>, currentId: String): List<JSONObject> {
    val ids = revisions.map { it.getString("id") }.toSet()
    return revisions.filter { child ->
        val parents = child.getJSONArray("parents")
        (0 until parents.length()).map { parents.getString(it) }.firstOrNull { it in ids } == currentId
    }
}

private val effectGroups = linkedMapOf(
    "Gain & EQ" to setOf("gain", "bass", "presence", "treble", "parametric"),
    "Filters" to setOf("frequency", "band", "hum", "buzz"),
    "Restore" to setOf("declip", "noise"),
    "Time & space" to setOf("room", "echo", "shift"),
    "Pitch & stereo" to setOf("pitch", "width"),
)

@Composable private fun WorkCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); content()
    } }
}
@Composable private fun Numeric(value: String, change: (String) -> Unit, label: String, enabled: Boolean, signed: Boolean = false, errorMessage: String? = null) {
    val support: (@Composable () -> Unit)? = if (errorMessage == null) null else { { Text(errorMessage) } }
    OutlinedTextField(value, change, label = { Text(label) }, enabled = enabled, singleLine = true,
        isError = errorMessage != null, supportingText = support,
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AudioWorkbench(model: VaultModel, screen: VaultScreen, selected: JSONObject, actor: String, ready: Boolean, transcript: () -> Unit) {
    val active = selected.getString("activeArtifact")
    var draft by rememberSaveable(screen.selected) { mutableStateOf(EffectRack().json().toString()) }
    var regionsJson by rememberSaveable(screen.selected) { mutableStateOf("[]") }
    var baseId by rememberSaveable(screen.selected) { mutableStateOf(active) }
    var loadedRevision by rememberSaveable(screen.selected) { mutableStateOf("") }
    var start by rememberSaveable(screen.selected) { mutableStateOf("") }
    var end by rememberSaveable(screen.selected) { mutableStateOf("") }
    var chosen by rememberSaveable(screen.selected) { mutableIntStateOf(-1) }
    var presetBackup by rememberSaveable(screen.selected) { mutableStateOf("") }
    var presetName by rememberSaveable(screen.selected) { mutableStateOf("") }
    var showEffects by rememberSaveable { mutableStateOf(true) }
    var effectCategory by rememberSaveable(screen.selected) { mutableStateOf("Gain & EQ") }
    var selectedEffect by rememberSaveable(screen.selected) { mutableStateOf("gain") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableStateOf(false) }
    var historyCount by rememberSaveable { mutableIntStateOf(15) }
    var candidateCount by rememberSaveable(screen.selected) { mutableIntStateOf(32) }
    var position by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var seek by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    var loop by rememberSaveable(screen.selected) { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var selectedEventId by remember(screen.selected) { mutableStateOf<String?>(null) }
    var comparisonId by rememberSaveable(screen.selected) { mutableStateOf("") }
    var showComparison by rememberSaveable(screen.selected) { mutableStateOf(false) }
    var comparisonMode by remember { mutableStateOf(false) }
    var detailFft by rememberSaveable(screen.selected) { mutableIntStateOf(1024) }
    var detailOverlap by rememberSaveable(screen.selected) { mutableIntStateOf(2) }
    var detailWindow by rememberSaveable(screen.selected) { mutableStateOf("hann") }
    var detailScale by rememberSaveable(screen.selected) { mutableStateOf("linear") }
    var detailFloor by rememberSaveable(screen.selected) { mutableIntStateOf(-90) }
    var selectFrequency by rememberSaveable(screen.selected) { mutableStateOf(false) }
    var showDetail by rememberSaveable(screen.selected) { mutableStateOf(false) }
    var detailRequested by rememberSaveable(screen.selected) { mutableStateOf(false) }
    var detailError by rememberSaveable(screen.selected) { mutableStateOf("") }
    var noiseRequested by rememberSaveable(screen.selected) { mutableStateOf(false) }
    var noiseError by rememberSaveable(screen.selected) { mutableStateOf("") }
    var appliedNoiseMeasurement by rememberSaveable(screen.selected) { mutableStateOf("") }
    var workbenchLoaded by remember(active) { mutableStateOf(false) }
    val rack = remember(draft) { EffectRack.fromJson(JSONObject(draft)) }
    val regions = JSONArray(regionsJson)
    val duration = screen.analysis?.optLong("durationMs", 0) ?: 0L
    val fullOverview = screen.fullSpectrum?.takeIf {
        it.optString("audioArtifactId") == active && it.optString("sourceSha256") == selected.artifact(active).getString("sha256")
    }
    val comparison = screen.comparisonAnalysis?.takeIf { screen.comparisonAudioId == comparisonId }
    LaunchedEffect(model.player,loop,start,end) {
        while (true) {
            position = model.player?.currentPosition ?: 0; playing = model.player?.isPlaying ?: false
            val a=start.toLongOrNull(); val b=end.toLongOrNull()
            if (loop && playing && a!=null && b!=null && b>a && (position>=b || position<a)) model.player?.seekTo(a)
            delay(50)
        }
    }
    LaunchedEffect(screen.job) {
        if (screen.job != null && screen.job != "Inspecting selected spectrum") detailRequested = false
        if (screen.job != null && screen.job != "Measuring selected noise floor") noiseRequested = false
    }
    LaunchedEffect(active) {
        // Keep unsaved controls and selections across activity recreation. Only
        // load a stored recipe when the selected audio revision actually changes.
        val restored = model.recallWorkbenchDraft(selected.getString("caseId"), active)
        if (restored != null) {
            draft=restored.draft; regionsJson=restored.regions; baseId=restored.baseId
            start=restored.start; end=restored.end; chosen=restored.chosen
            presetBackup=restored.presetBackup; presetName=restored.presetName; limit=restored.limit
            showEffects=restored.showEffects; effectCategory=restored.effectCategory; selectedEffect=restored.selectedEffect; appliedNoiseMeasurement=restored.appliedNoiseMeasurement
            showComparison=restored.showComparison; comparisonId=restored.comparisonId
            showDetail=restored.showDetail; selectFrequency=restored.selectFrequency
            detailFft=restored.detailFft; detailOverlap=restored.detailOverlap; detailWindow=restored.detailWindow
            detailScale=restored.detailScale; detailFloor=restored.detailFloor
            loadedRevision=active; workbenchLoaded=true
            return@LaunchedEffect
        }
        if (loadedRevision == active) { workbenchLoaded=true; return@LaunchedEffect }
        comparisonMode = false; detailRequested = false; detailError = ""; noiseRequested = false; noiseError = ""
        try {
        val parameters = selected.artifact(active).operationParameters()
        val plan = parameters.optJSONObject("workbenchPlan")
        if (plan != null) {
            require(plan.getJSONArray("regions").length() <= 32)
            baseId = plan.getString("baseId"); draft = plan.getJSONObject("rack").toString()
            regionsJson = plan.getJSONArray("regions").toString(); limit = plan.optBoolean("limit", false)
        } else {
            baseId = active; regionsJson = "[]"
            draft = (parameters.optJSONObject("effectRack") ?: EffectRack().json()).toString()
        }
        start = if (plan != null && plan.optLong("startMs",0) > 0) plan.getLong("startMs").toString() else ""
        end = if (plan != null && !plan.isNull("endMs")) plan.getLong("endMs").toString() else ""
        chosen = -1; presetName = ""; presetBackup = ""; selectedEventId = null
        } catch (_: Exception) {
            baseId = active; draft = EffectRack().json().toString(); regionsJson = "[]"; start = ""; end = ""; chosen = -1; selectedEventId = null
            model.notice("This revision has an unsupported effect recipe. Audio is retained; start a new recipe or restore an earlier revision.")
        }
        loadedRevision = active; workbenchLoaded = true
    }
    SideEffect {
        if (workbenchLoaded && loadedRevision == active) model.rememberWorkbenchDraft(WorkbenchDraftMemory(
            caseId=selected.getString("caseId"), revisionId=active, draft=draft, regions=regionsJson,
            baseId=baseId, start=start, end=end, chosen=chosen, presetBackup=presetBackup, presetName=presetName,
            limit=limit, showEffects=showEffects, effectCategory=effectCategory, selectedEffect=selectedEffect,
            appliedNoiseMeasurement=appliedNoiseMeasurement, showComparison=showComparison, comparisonId=comparisonId,
            showDetail=showDetail, selectFrequency=selectFrequency, detailFft=detailFft, detailOverlap=detailOverlap,
            detailWindow=detailWindow, detailScale=detailScale, detailFloor=detailFloor,
        ))
    }
    fun update(next: EffectRack) { draft = next.json().toString(); presetName = "" }
    val measurement = screen.noiseFloorMeasurement?.takeIf { it.optString("sourceArtifactId") == active }
    LaunchedEffect(active, loadedRevision, workbenchLoaded, measurement?.optJSONObject("reference")?.optString("artifactId")) {
        if (workbenchLoaded && loadedRevision == active && measurement != null && measurement.getJSONObject("reference").getString("artifactId") != appliedNoiseMeasurement) {
            val reference = measurement.getJSONObject("reference")
            val current = EffectRack.fromJson(JSONObject(draft))
            val noise = current.values["noise"] ?: EffectValue()
            update(current.copy(noiseThresholdDb = measurement.getDouble("gateThresholdDb"),
                noiseReference = NoiseFloorReference(reference.getString("artifactId"), reference.getString("sha256")),
                values = current.values + ("noise" to EffectValue(true, if (noise.strength == 0) 50 else noise.strength))))
            appliedNoiseMeasurement = reference.getString("artifactId")
            effectCategory = "Restore"; selectedEffect = "noise"; showEffects = true
        }
    }
    fun selection(): Pair<Long, Long> {
        val a = start.toLong(); val b = end.toLong()
        require(a >= 0 && b > a && b <= duration) { "Choose a valid interval within the recording" }
        return a to b
    }
    fun render(trim: Boolean = false, after: () -> Unit = {}) {
        try {
            val plan = JSONObject().put("baseId", baseId).put("rack", rack.json()).put("regions", regions).put("limit", limit)
            val settings = if (trim) {
                val (a,b) = selection()
                listOf(RenderSettings(rack = EffectRack(), trim = true, startMs = a, endMs = b, limit = false))
            } else if (regions.length() > 0) (0 until regions.length()).map { i ->
                val region = regions.getJSONObject(i)
                RenderSettings(rack = EffectRack.fromJson(region.getJSONObject("rack")), startMs = region.getLong("startMs"), endMs = region.getLong("endMs"), limit = limit, workbenchPlan = plan.toString())
            } else {
                val interval = if (start.isBlank() && end.isBlank()) null else selection()
                plan.put("startMs", interval?.first ?: 0).put("endMs", interval?.second ?: JSONObject.NULL)
                listOf(RenderSettings(rack = rack, startMs = interval?.first ?: 0, endMs = interval?.second, limit = limit, workbenchPlan = plan.toString()))
            }
            val selectedBase = if (trim) active else baseId
            val useClearVoice = model.clearVoiceEnabled && !trim
            if (useClearVoice && model.clearVoicePlacement == ClearVoicePlacement.BEFORE) {
                model.enhanceClearVoice(actor, selectedBase) { enhanced -> model.renderPlan(actor, enhanced, settings, after) }
            } else {
                model.renderPlan(actor, selectedBase, settings) {
                    if (useClearVoice) model.enhanceClearVoice(actor) { after() } else after()
                }
            }
        } catch (e: Exception) { model.notice(e.message ?: "Check effects and region values") }
    }
    WorkCard("Recording timeline") {
        Text("Tap to seek · drag to select or adjust edges · ${duration} ms", style = MaterialTheme.typography.bodySmall)
        val analysis = screen.analysis
        if (analysis != null) {
            val peaks = analysis.getJSONArray("peaks"); val spectrum = fullOverview?.getJSONArray("columns") ?: analysis.getJSONArray("spectrum")
            val color = MaterialTheme.colorScheme.primary
            var dragAnchor by remember { mutableLongStateOf(0L) }
            val selectionStart = start.toLongOrNull(); val selectionEnd = end.toLongOrNull()
            val latestStart by rememberUpdatedState(selectionStart); val latestEnd by rememberUpdatedState(selectionEnd)
            Canvas(Modifier.fillMaxWidth().height(190.dp).testTag("waveform").semantics { contentDescription = "Full recording waveform and ${if (fullOverview == null) "sampled" else "continuous coarse"} spectrogram; drag to select or adjust selection edges, or use time fields for precision" }
                .pointerInput(duration, ready) { detectTapGestures { point -> if (ready && duration > 0) model.playbackAt(actor, (point.x / size.width * duration).toLong().coerceIn(0, duration)) } }
                .pointerInput(duration, ready) { detectDragGestures(orientationLock = null, onDragStart = { down, _, _ ->
                    val at = (down.position.x / size.width * duration).toLong().coerceIn(0, duration)
                    val a=latestStart; val b=latestEnd
                    val left=if(a!=null && duration>0) size.width*a.toFloat()/duration else Float.NEGATIVE_INFINITY
                    val right=if(b!=null && duration>0) size.width*b.toFloat()/duration else Float.NEGATIVE_INFINITY
                    val leftDistance=abs(down.position.x-left); val rightDistance=abs(down.position.x-right)
                    dragAnchor = if (a!=null && b!=null && b>a && min(leftDistance,rightDistance)<=24.dp.toPx()) {
                        if(leftDistance<=rightDistance) b else a
                    } else at
                }, onDrag = { change, _ ->
                    if (ready && duration > 0) {
                        change.consume()
                        val at = (change.position.x / size.width * duration).toLong().coerceIn(0, duration)
                        val a = min(dragAnchor, at); val b = max(dragAnchor, at)
                        start = a.toString(); end = b.toString(); chosen = -1
                    }
                }) }) {
                val waveHeight = size.height * .4f
                if (comparison != null && comparison.optLong("durationMs") == duration) {
                    val referencePeaks = comparison.getJSONArray("peaks")
                    for (i in 0 until referencePeaks.length()) {
                        val x = size.width * i / referencePeaks.length(); val y = waveHeight * referencePeaks.getDouble(i).toFloat() / 2
                        drawLine(Color(0xFFFFB74D), Offset(x, waveHeight / 2 - y), Offset(x, waveHeight / 2 + y), 3f)
                    }
                }
                for (i in 0 until peaks.length()) {
                    val x = size.width * i / peaks.length(); val y = waveHeight * peaks.getDouble(i).toFloat() / 2
                    drawLine(color, Offset(x, waveHeight / 2 - y), Offset(x, waveHeight / 2 + y))
                }
                for (x in 0 until spectrum.length()) {
                    val tile = spectrum.getJSONArray(x); val w = size.width / spectrum.length(); val h = (size.height-waveHeight) / tile.length()
                    for (y in 0 until tile.length()) drawRect(lerp(Color(0xFF13243A), color, ((tile.getDouble(y)+85)/85).toFloat().coerceIn(0f,1f)), Offset(w*x,size.height-h*(y+1)),Size(w+1,h+1))
                }
                fun shade(a: Long, b: Long, shade: Color) { drawRect(shade,Offset(size.width*a/duration.coerceAtLeast(1),0f),Size(size.width*(b-a)/duration.coerceAtLeast(1),size.height)) }
                for (i in 0 until regions.length()) { val r=regions.getJSONObject(i); shade(r.getLong("startMs"),r.getLong("endMs"),Color(0xFFBB86FC).copy(alpha=.15f)) }
                if (selectionStart != null && selectionEnd != null && selectionEnd > selectionStart) {
                    shade(selectionStart,selectionEnd,color.copy(alpha=.25f))
                    for (edge in listOf(selectionStart,selectionEnd)) {
                        val x=(size.width*edge/duration.coerceAtLeast(1)).coerceIn(0f,size.width)
                        drawLine(color,Offset(x,0f),Offset(x,size.height),2.dp.toPx())
                        drawCircle(color,4.dp.toPx(),Offset(x,5.dp.toPx()))
                    }
                }
                val cursor = size.width * position / duration.coerceAtLeast(1)
                drawLine(Color.Black,Offset(cursor,0f),Offset(cursor,size.height),4f)
                drawLine(Color.White,Offset(cursor,0f),Offset(cursor,size.height),2f)
            }
        } else OutlinedButton(onClick={model.analyze(actor)},enabled=ready) { Text("Analyze audio") }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={model.togglePlayback(actor)},enabled=ready) { Text(if(playing) "Pause" else if(model.player!=null) "Resume" else "Play") }
            OutlinedButton(onClick={model.stop()},enabled=ready) { Text("Stop") }
            OutlinedButton(onClick={render(trim=true)},enabled=ready&&start.isNotBlank()&&end.isNotBlank()) { Text("Create clip from selection") }
            TextButton(onClick={start="";end="";chosen=-1},enabled=ready) { Text("Use entire recording") }
        }
        Text("${position} / ${duration} ms")
        Slider(value=(if(scrubbing) seek else position.toFloat()).coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={seek=it;scrubbing=true},onValueChangeFinished={model.playbackAt(actor,seek.toLong());scrubbing=false},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=ready&&duration>0,modifier=Modifier.semantics { contentDescription="Playback position" })
        Row { Checkbox(loop,{loop=it},enabled=ready&&start.toLongOrNull()!=null&&end.toLongOrNull()!=null); Text("Loop selection while this workbench is open") }
        Numeric(start,{start=it},"Selection start ms",ready); Numeric(end,{end=it},"Selection end ms",ready)
        if (analysis != null) {
            Text(if (fullOverview == null) "Spectral overview · up to 128 brief Hann-window snapshots across the file, not continuous. Events between snapshots can be missed."
                else "Full-recording spectral overview · overlapping windows cover the recording. The 128 time columns and 32 frequency bands show peak magnitudes, so brief sounds can blur; inspect and listen to a selection for detail.",
                modifier=Modifier.testTag("spectrogram"), style=MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick={model.inspectFullSpectrum(actor)},enabled=ready) { Text(if(fullOverview == null) "Scan full recording spectrum" else "Rescan full recording spectrum") }
            Text("A full scan reads the complete recording and can take a long time on large files. Keep EchoScript open; you can cancel. Your original is preserved.",style=MaterialTheme.typography.bodySmall)
            if (screen.job == "Scanning full recording spectrum") {
                LinearProgressIndicator(Modifier.fillMaxWidth()); Text(screen.message,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=model::cancel) { Text("Cancel full spectrum scan") }
            }
            Text("Active revision: peak ${"%.1f".format(java.util.Locale.ROOT,analysis.getDouble("peakDb"))} dBFS · RMS ${"%.1f".format(java.util.Locale.ROOT,analysis.getDouble("rmsDb"))} dBFS · ${analysis.getLong("clippedSamples")} near-full-scale samples. The count is an indicator, not proof of clipping.",style=MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick={showComparison=!showComparison}) { Text(if(showComparison) "Hide A/B comparison" else "Show A/B comparison") }
        if(showComparison) {
        val audioRevisions=selected.items("artifacts").filter{it.getString("type") in setOf("SOURCE","DECODE","ENHANCED_AUDIO")}
        Text("A/B comparison reference")
        for(a in audioRevisions.takeLast(8)) FilterChip(comparisonId==a.getString("id"),{comparisonId=a.getString("id")},{Text("Revision ${a.getInt("revision")}")},enabled=ready)
        val comparisonRevision = audioRevisions.firstOrNull { it.getString("id") == comparisonId }
        val canCompare = comparisonRevision != null && comparisonRevision.getString("type") in setOf("DECODE","ENHANCED_AUDIO")
        OutlinedButton(onClick={model.compareAudio(actor,comparisonId)},enabled=ready&&canCompare) { Text("Compare waveform and clipping") }
        Text("Choose a decoded or enhanced reference to compare peak envelopes and near-full-scale sample counts. Counts include all channels, so different durations or channel counts affect them. The count is an indicator, not proof of clipping.",style=MaterialTheme.typography.bodySmall)
        if (screen.job == "Comparing audio revisions") {
            LinearProgressIndicator(Modifier.fillMaxWidth()); Text(screen.message,style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=model::cancel) { Text("Cancel comparison") }
        }
        if (comparison != null && analysis != null) {
            Text("Reference: ${comparison.getLong("clippedSamples")} near-full-scale samples · active: ${analysis.getLong("clippedSamples")}",modifier=Modifier.testTag("clipping-comparison"),style=MaterialTheme.typography.bodySmall)
            Text(if(comparison.optLong("durationMs")==duration) "Waveform overlay: reference amber, active theme color. Envelopes summarize peaks, not individual samples." else "Durations differ, so the waveform overlay is hidden. Compare a time-aligned revision.",style=MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick={
            val reference=comparisonId.ifBlank{audioRevisions.first().getString("id")}
            val time=model.player?.currentPosition?:position
            val next=if(comparisonMode) active else reference
            comparisonMode=!comparisonMode
            model.play(actor,next,time/1000.0)
        },enabled=ready) { Text(if(comparisonMode) "A/B: hear active render" else "A/B: hear reference") }
        Text("A/B seeks the same millisecond position. Pitch/echo/time-shift processing can alter alignment.",style=MaterialTheme.typography.bodySmall)
        }
    }
    WorkCard("Quick enhancement presets") {
        val overviewPeaks=screen.analysis?.optJSONArray("peaks")
        if (overviewPeaks != null && overviewPeaks.length() > 0) {
            val color=MaterialTheme.colorScheme.primary
            Text("Timeline overview · adjust the selection in the recording timeline above",style=MaterialTheme.typography.labelSmall)
            Canvas(Modifier.fillMaxWidth().height(64.dp).testTag("effects-overview").semantics {
                contentDescription="Read-only waveform overview; adjust the selection in the recording timeline"
            }) {
                val a=start.toLongOrNull(); val b=end.toLongOrNull()
                if (a!=null && b!=null && b>a && duration>0) {
                    val left=(size.width*a/duration).coerceIn(0f,size.width);val right=(size.width*b/duration).coerceIn(0f,size.width)
                    drawRect(color.copy(alpha=.2f),Offset(left,0f),Size((right-left).coerceAtLeast(0f),size.height))
                }
                for (i in 0 until overviewPeaks.length()) {
                    val x=size.width*i/overviewPeaks.length();val y=size.height*overviewPeaks.getDouble(i).toFloat()/2
                    drawLine(color,Offset(x,size.height/2-y),Offset(x,size.height/2+y))
                }
                val cursor=size.width*position/duration.coerceAtLeast(1)
                drawLine(Color.Black,Offset(cursor,0f),Offset(cursor,size.height),4f)
                drawLine(Color.White,Offset(cursor,0f),Offset(cursor,size.height),2f)
            }
            OutlinedButton(onClick={model.togglePlayback(actor)},enabled=ready) { Text(if(playing) "Pause playback" else if(model.player!=null) "Resume playback" else "Play recording") }
        }
        Text("Draft settings only until Render. Tap the selected preset again to remove it and restore prior settings.")
        Row(Modifier.fillMaxWidth().testTag("effect-presets").horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            for ((name,preset) in rackPresets) FilterChip(selected=presetName==name,onClick={
                if(presetName==name) { draft=presetBackup; presetName="";presetBackup="" }
                else { if(presetName.isBlank()) presetBackup=draft; draft=preset.json().toString();presetName=name }
            },label={Text(name)},enabled=ready)
        }
        TextButton(onClick={showEffects=!showEffects}) { Text(if(showEffects) "Hide effect controls" else "Effect controls") }
        if(showEffects) {
            Text("Effect groups · swipe for more",style=MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().testTag("effect-groups").horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                for (group in effectGroups.keys) FilterChip(effectCategory==group,{
                    effectCategory=group; selectedEffect=effectSpecs.first { it.id in effectGroups.getValue(group) }.id
                },{Text(group)},enabled=ready)
            }
            val enabledEffects=effectSpecs.filter { rack.values[it.id]?.enabled == true }.map { it.title }
            Text(if(enabledEffects.isEmpty()) "No effects enabled" else "Enabled: ${enabledEffects.joinToString(", ")}",style=MaterialTheme.typography.bodySmall)
            val groupEffects=effectSpecs.filter { it.id in effectGroups.getValue(effectCategory) }
            val currentEffect=groupEffects.firstOrNull { it.id == selectedEffect } ?: groupEffects.first()
            key(effectCategory) {
                Row(Modifier.fillMaxWidth().testTag("effect-choices").horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    for (effect in groupEffects) FilterChip(currentEffect.id==effect.id,{selectedEffect=effect.id},{Text(effect.title)},enabled=ready)
                }
            }
            val spec=currentEffect; val value=rack.values[spec.id]?:EffectValue()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(spec.title,modifier=Modifier.weight(1f)); Switch(value.enabled,{update(rack.copy(values=rack.values+(spec.id to value.copy(enabled=it))))},enabled=ready,modifier=Modifier.semantics{contentDescription="${spec.title} enabled"})
            }
            Text(spec.description,style=MaterialTheme.typography.bodySmall)
            Slider(value=value.strength.toFloat(),onValueChange={update(rack.copy(values=rack.values+(spec.id to value.copy(strength=it.roundToInt()))))},valueRange=-100f..100f,steps=199,enabled=ready&&value.enabled,modifier=Modifier.semantics{contentDescription="${spec.title} strength"})
            var number by remember(draft,spec.id) { mutableStateOf(value.strength.toString()) }
            val invalid=number.toIntOrNull()?.let { it !in -100..100 } ?: true
            Numeric(number,{text->number=text;text.toIntOrNull()?.takeIf{it in -100..100}?.let{update(rack.copy(values=rack.values+(spec.id to value.copy(strength=it))))}},"${spec.title} strength (−100 to 100)",ready&&value.enabled,signed=true,
                errorMessage=if(value.enabled&&invalid) "Enter a whole number from −100 to 100. The last valid value remains in the draft." else null)
            when(effectCategory) {
                "Filters" -> {
                    if (currentEffect.id == "hum") {
                        Row { for(hz in listOf(50,60)) FilterChip(rack.humHz==hz,{update(rack.copy(humHz=hz))},{Text("$hz Hz")},enabled=ready) }
                    }
                    if (currentEffect.id == "buzz") {
                        var buzz by remember(rack.buzzHz) { mutableStateOf(rack.buzzHz.toString()) }
                        Numeric(buzz,{buzz=it;it.toDoubleOrNull()?.takeIf{n->n.isFinite()&&n in 100.0..20000.0}?.let{n->update(rack.copy(buzzHz=n))}},"Buzz notch Hz (below Nyquist)",ready)
                    }
                    if (currentEffect.id == "frequency") {
                        Text("Band ${rack.bandLowHz}–${rack.bandHighHz} Hz. Frequencies must be below the recording's Nyquist frequency.",style=MaterialTheme.typography.bodySmall)
                        var bandLow by remember(rack.bandLowHz) { mutableStateOf(rack.bandLowHz.toString()) }
                        Numeric(bandLow,{bandLow=it;it.toDoubleOrNull()?.takeIf{n->n.isFinite()&&n>=20&&n<rack.bandHighHz}?.let{n->update(rack.copy(bandLowHz=n))}},"Band low Hz",ready)
                        var bandHigh by remember(rack.bandHighHz) { mutableStateOf(rack.bandHighHz.toString()) }
                        Numeric(bandHigh,{bandHigh=it;it.toDoubleOrNull()?.takeIf{n->n.isFinite()&&n>rack.bandLowHz&&n<=20000}?.let{n->update(rack.copy(bandHighHz=n))}},"Band high Hz",ready)
                    }
                }
                "Time & space" -> {
                    if (currentEffect.id == "echo") {
                        var echo by remember(rack.echoMs) { mutableStateOf(rack.echoMs.toString()) }
                        Numeric(echo,{echo=it;it.toIntOrNull()?.takeIf{n->n in 1..500}?.let{n->update(rack.copy(echoMs=n))}},"Echo delay ms (1–500)",ready)
                    }
                }
                "Restore" -> {
                    if (currentEffect.id == "noise") {
                        var noiseThreshold by remember(rack.noiseThresholdDb) { mutableStateOf(rack.noiseThresholdDb.toString()) }
                        Numeric(noiseThreshold,{noiseThreshold=it;it.toDoubleOrNull()?.takeIf{n->n.isFinite()&&n in -90.0..-10.0}?.let{n->update(rack.copy(noiseThresholdDb=n,noiseReference=null))}},"Noise gate threshold dBFS (−90 to −10)",ready,signed=true)
                    }
                }
                "Gain & EQ" -> {
                    if (currentEffect.id == "parametric") {
                        Text("EQ center ${rack.paramHz} Hz, Q ${rack.paramQ}. The center must be below the recording's Nyquist frequency.",style=MaterialTheme.typography.bodySmall)
                        var paramHz by remember(rack.paramHz) { mutableStateOf(rack.paramHz.toString()) }
                        Numeric(paramHz,{paramHz=it;it.toDoubleOrNull()?.takeIf{n->n.isFinite()&&n in 20.0..20000.0}?.let{n->update(rack.copy(paramHz=n))}},"EQ center Hz (20–20000)",ready)
                        var paramQ by remember(rack.paramQ) { mutableStateOf(rack.paramQ.toString()) }
                        Numeric(paramQ,{paramQ=it;it.toDoubleOrNull()?.takeIf{n->n.isFinite()&&n in .1..20.0}?.let{n->update(rack.copy(paramQ=n))}},"EQ Q (0.1–20)",ready)
                    }
                }
                else -> Unit
            }
            Row { Checkbox(limit,{limit=it},enabled=ready);Text("Soft peak limiter (changes peaks)") }
            TextButton(onClick={update(EffectRack());limit=false},enabled=ready) { Text("Bypass all effects") }
        }
        Text("For a broadband noise gate, select up to 30 seconds of representative noise above, then measure it. Measurement enables Denoise at its existing nonzero setting, or +50 if it was zero; adjust or disable it before rendering. Speech or other important sounds in the selection may also be attenuated. This is not a spectral noise profile or source separation.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={
            try { val (a,b)=selection(); noiseError=""; noiseRequested=true; model.measureNoise(actor,a,b) }
            catch (_:Exception) { noiseError="Choose a valid interval to measure" }
        },enabled=ready&&screen.analysis!=null) { Text("Measure selected noise floor") }
        if (noiseError.isNotBlank()) Text(noiseError,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        if (noiseRequested && (screen.job == "Measuring selected noise floor" || screen.failed)) {
            if (screen.job != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(screen.message,style=MaterialTheme.typography.bodySmall)
            if (screen.job != null) TextButton(onClick=model::cancel) { Text("Cancel noise measurement") }
        }
        if (rack.noiseReference != null) Text("Measured gate threshold: ${"%.1f".format(java.util.Locale.ROOT,rack.noiseThresholdDb)} dBFS. The measurement is saved in this case. Gain and EQ before the gate can change the noise level.",style=MaterialTheme.typography.bodySmall)
        if (measurement != null && measurement.optJSONObject("reference")?.optString("artifactId") == rack.noiseReference?.artifactId && measurement.optLong("nearFullScaleSamples") > 0) {
            Text("The measured selection has ${measurement.getLong("nearFullScaleSamples")} near-full-scale samples. Check whether it is representative before applying the gate.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        }
    }
    WorkCard("Ordered processing regions") {
        Text("Without regions, the rack applies to the current selection or entire file. With regions, only saved region settings are rendered in list order; overlaps accumulate. Update a region after changing its rack. Maximum 32 regions.")
        OutlinedButton(onClick={try{val(a,b)=selection();require(regions.length()<32);regions.put(JSONObject().put("startMs",a).put("endMs",b).put("rack",rack.json()));regionsJson=regions.toString();chosen=regions.length()-1}catch(_:Exception){model.notice("Choose a valid interval; maximum 32 regions")}},enabled=ready) { Text("Add selection with these effects") }
        for(i in 0 until regions.length()) {
            val r=regions.getJSONObject(i)
            FilterChip(chosen==i,{chosen=i;start=r.getLong("startMs").toString();end=r.getLong("endMs").toString();draft=r.getJSONObject("rack").toString();presetName=""},{Text("${i+1}: ${r.getLong("startMs")}–${r.getLong("endMs")} ms")},enabled=ready)
        }
        if(chosen in 0 until regions.length()) {
            OutlinedButton(onClick={try{val(a,b)=selection();regions.put(chosen,JSONObject().put("startMs",a).put("endMs",b).put("rack",rack.json()));regionsJson=regions.toString()}catch(_:Exception){model.notice("Invalid region")}},enabled=ready) { Text("Update selected region") }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={val moved=moveProcessingRegion(regions,chosen,chosen-1);regionsJson=moved.toString();chosen--},enabled=ready&&chosen>0) { Text("Move earlier") }
                OutlinedButton(onClick={val moved=moveProcessingRegion(regions,chosen,chosen+1);regionsJson=moved.toString();chosen++},enabled=ready&&chosen<regions.length()-1) { Text("Move later") }
            }
            TextButton(onClick={regions.remove(chosen);regionsJson=regions.toString();chosen=-1},enabled=ready) { Text("Remove selected region") }
        }
        TextButton(onClick={regionsJson="[]";chosen=-1},enabled=ready) { Text("Clear regions") }
        OutlinedButton(onClick={render()},enabled=ready,modifier=Modifier.fillMaxWidth()) { Text("Render enhanced revision") }
        TextButton(onClick={model.transcribe(actor,transcript)},enabled=ready) { Text("Transcribe active audio on device") }
    }
    WorkCard("Inspect a time range") {
        TextButton(onClick={showDetail=!showDetail}) { Text(if(showDetail) "Hide detailed spectrum" else "Open detailed spectrum") }
        if (showDetail) {
        Text("Select a short interval above, then inspect it with consecutive FFT windows. Smaller hops add overlap; larger FFTs improve frequency resolution but use longer time windows.",style=MaterialTheme.typography.bodySmall)
        Text("FFT size",style=MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            for (n in listOf(256,512,1024,2048)) FilterChip(detailFft==n,{detailFft=n},{Text("$n")},enabled=ready)
        }
        Text("Hop size",style=MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            for (divisor in listOf(1,2,4)) FilterChip(detailOverlap==divisor,{detailOverlap=divisor},{Text("${detailFft/divisor} samples")},enabled=ready)
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            for (window in listOf("hann","hamming")) FilterChip(detailWindow==window,{detailWindow=window},{Text(window.replaceFirstChar{it.uppercase()})},enabled=ready)
            for (scale in listOf("linear","log")) FilterChip(detailScale==scale,{detailScale=scale},{Text(if(scale=="log") "Log frequency" else "Linear frequency")},enabled=ready)
        }
        Text("Display floor",style=MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            for (db in listOf(-60,-90,-120)) FilterChip(detailFloor==db,{detailFloor=db},{Text("$db dBFS")},enabled=ready)
        }
        fun inspect(a:Long,b:Long) {
            try {
                require(a>=0 && b>a && b<=duration) { "Choose a valid interval inside the recording" }
                start=a.toString();end=b.toString();chosen=-1
                detailError="";detailRequested=true
                model.inspectSpectrum(actor,DetailSpectrumSettings(a,b,detailFft,detailFft/detailOverlap,detailWindow,detailScale,detailFloor))
            } catch (failure:Exception) { detailError=failure.message ?: "Choose a valid interval" }
        }
        Button(onClick={try{val(a,b)=selection();inspect(a,b)}catch(_:Exception){detailError="Choose a valid interval to inspect"}},enabled=ready) { Text("Inspect selected interval") }
        if (detailError.isNotBlank()) Text(detailError,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        if (detailRequested && (screen.job == "Inspecting selected spectrum" || screen.failed)) {
            if (screen.job != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(screen.message,style=MaterialTheme.typography.bodySmall)
            if (screen.job != null) TextButton(onClick=model::cancel) { Text("Cancel inspection") }
        }
        val detail=screen.detailedSpectrum?.takeIf{it.optString("audioArtifactId")==active}
        if (detail != null) {
            val actual=detail.getJSONObject("settings");val a=actual.getLong("startMs");val b=actual.getLong("endMs")
            val sampleRate=actual.getInt("sampleRate");val span=b-a;val center=a+span/2
            Text("$a–$b ms · ${actual.getInt("fftSize")}-sample ${actual.getString("window")} window · hop ${actual.getInt("hopSize")} · ${actual.getString("scale")} · ${actual.getInt("floorDb")} dBFS floor",style=MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={val minimum=(detailFft*1000L/sampleRate+1).coerceAtMost(duration);val size=max(minimum,span/2);val left=(center-size/2).coerceIn(0,(duration-size).coerceAtLeast(0));inspect(left,left+size)},enabled=ready&&span>1) { Text("Zoom in") }
                OutlinedButton(onClick={val size=min(duration,span*2);val left=(center-size/2).coerceIn(0,(duration-size).coerceAtLeast(0));inspect(left,left+size)},enabled=ready&&span<duration) { Text("Zoom out") }
                OutlinedButton(onClick={val left=(a-span/2).coerceAtLeast(0);inspect(left,(left+span).coerceAtMost(duration))},enabled=ready&&a>0) { Text("Earlier") }
                OutlinedButton(onClick={val left=(a+span/2).coerceAtMost((duration-span).coerceAtLeast(0));inspect(left,left+span)},enabled=ready&&b<duration) { Text("Later") }
            }
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(selectFrequency,enabled=ready,role=Role.Checkbox,onValueChange={selectFrequency=it})) {
                Checkbox(selectFrequency,null,enabled=ready);Text("Select a frequency band by dragging on the spectrum")
            }
            DetailedTimeline(detail,position,start.toLongOrNull(),end.toLongOrNull(),selectFrequency,ready,
                seek={model.playbackAt(actor,it)}, selectTime={first,last->start=first.toString();end=last.toString();chosen=-1},
                selectFrequencyBand={low,high->
                    val value=rack.values["frequency"]?:EffectValue()
                    update(rack.copy(bandLowHz=low,bandHighHz=high,values=rack.values+("frequency" to EffectValue(true,if(value.strength==0) 100 else value.strength))))
                    effectCategory="Filters";selectedEffect="frequency";showEffects=true
                    model.notice("Frequency isolation enabled in draft. Render to apply the selected band.")
                })
            Text("Tap to seek; drag to select time. Spectral colors show the strongest channel/bin, not speech certainty or power density. Edge samples and the final incomplete hop have no centered window. Other effects operate across the selected time range, not only within the frequency band.",style=MaterialTheme.typography.bodySmall)
        }
        }
    }
    WorkCard("Speech processing") { ProviderControls(model, ready) }
    Text("Review audio",style=MaterialTheme.typography.headlineSmall)
    Button(onClick={render(after={model.transcribe(actor,transcript)})},enabled=ready,modifier=Modifier.fillMaxWidth()) { Text("Render & transcribe") }
    WorkCard("Markers and speech candidates") {
        OutlinedTextField(note,{note=it.take(4000)},label={Text("Examiner note / candidate label")},enabled=ready,modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={model.timelineNote(actor,position,null,note);note=""},enabled=ready&&note.isNotBlank()) { Text("Bookmark cursor") }
            OutlinedButton(onClick={try{val(a,b)=selection();model.timelineNote(actor,a,b,note);note=""}catch(_:Exception){model.notice("Choose a valid interval")}},enabled=ready&&note.isNotBlank()) { Text("Note selection") }
        }
        OutlinedButton(onClick={try{val(a,b)=selection();model.saveSpeechCandidate(actor,a,b,note.take(160))}catch(_:Exception){model.notice("Choose a valid interval")}},enabled=ready) { Text("Mark selected speech candidate") }
        val speechEvents=screen.analysis?.items("events").orEmpty()
        for(event in speechEvents.take(candidateCount)) FilterChip(selectedEventId==event.getString("eventId"),{
            selectedEventId=event.getString("eventId");start=event.getLong("startMs").toString();end=event.getLong("endMs").toString();note=event.optString("label");chosen=-1
        },{Text("Candidate ${event.getLong("startMs")}–${event.getLong("endMs")} ms ${event.optString("label")}")},enabled=ready)
        if (speechEvents.size>candidateCount) TextButton(onClick={candidateCount+=32}) { Text("Show more candidates") }
        if (selectedEventId != null) OutlinedButton(onClick={try{val(a,b)=selection();model.saveSpeechCandidate(actor,a,b,note.take(160),selectedEventId)}catch(_:Exception){model.notice("Choose a valid interval")}},enabled=ready) { Text("Save candidate adjustment") }
        for(event in selected.items("audit").filter{it.optString("action") in setOf("BOOKMARK","ANNOTATION")}.takeLast(32).asReversed()) {
            val d=event.optJSONObject("details")?:continue
            if(d.optString("audioArtifactId")==active) TextButton(onClick={model.playbackAt(actor,d.getLong("startMs"))},enabled=ready) { Text("${d.getLong("startMs")} ms · ${d.optString("text")}") }
        }
    }
    TextButton(onClick={showHistory=!showHistory}) { Text(if(showHistory) "Hide audio history" else "Audio revision history") }
    if(showHistory) WorkCard("Processing changelog") {
        Text("Restore selects an immutable earlier render and its saved recipe. New work branches without deleting later renders. Missing, corrupt or unsupported media may prevent playback/re-render; original and history are retained.")
        val revisions=selected.items("artifacts").filter{it.getString("type") in setOf("SOURCE","DECODE","ENHANCED_AUDIO")}
        val parentId=audioRevisionParent(revisions,active)
        val children=audioRevisionChildren(revisions,active)
        OutlinedButton(onClick={parentId?.let{model.revision(actor,it)}},enabled=ready&&parentId!=null) { Text("Undo to parent") }
        if (children.isNotEmpty()) {
            Text(if (children.size==1) "Redo" else "Choose a saved branch to redo",style=MaterialTheme.typography.labelMedium)
            for(child in children.takeLast(historyCount)) OutlinedButton(onClick={model.revision(actor,child.getString("id"))},enabled=ready) { Text("Redo revision ${child.getInt("revision")}") }
            if (children.size>historyCount) TextButton(onClick={historyCount+=15}) { Text("Show earlier branches") }
        }
        for(a in revisions.takeLast(historyCount).asReversed()) {
            Text("Revision ${a.getInt("revision")} · ${a.getString("name")}")
            Text("SHA-256 ${a.getString("sha256")}",style=MaterialTheme.typography.bodySmall)
            Text(a.getJSONObject("operation").toString(2),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={model.revision(actor,a.getString("id"))},enabled=ready&&a.getString("id")!=active) { Text("Restore revision ${a.getInt("revision")}") }
        }
        if(revisions.size>historyCount) TextButton(onClick={historyCount+=15}) { Text("Show earlier audio revisions") }
    }
}

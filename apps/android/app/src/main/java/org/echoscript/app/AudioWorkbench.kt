package org.echoscript.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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

@Composable private fun WorkCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); content()
    } }
}
@Composable private fun Numeric(value: String, change: (String) -> Unit, label: String, enabled: Boolean, signed: Boolean = false) {
    OutlinedTextField(value, change, label = { Text(label) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable internal fun AudioWorkbench(model: VaultModel, screen: VaultScreen, selected: JSONObject, actor: String, ready: Boolean, transcript: () -> Unit) {
    val active = selected.getString("activeArtifact")
    var draft by rememberSaveable(screen.selected) { mutableStateOf(EffectRack().json().toString()) }
    var regionsJson by rememberSaveable(screen.selected) { mutableStateOf("[]") }
    var baseId by rememberSaveable(screen.selected) { mutableStateOf(active) }
    var start by rememberSaveable(screen.selected) { mutableStateOf("") }
    var end by rememberSaveable(screen.selected) { mutableStateOf("") }
    var chosen by rememberSaveable(screen.selected) { mutableIntStateOf(-1) }
    var presetBackup by rememberSaveable(screen.selected) { mutableStateOf("") }
    var presetName by rememberSaveable(screen.selected) { mutableStateOf("") }
    var showEffects by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableStateOf(false) }
    var historyCount by rememberSaveable { mutableIntStateOf(15) }
    var position by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var seek by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    var loop by rememberSaveable(screen.selected) { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var comparisonId by rememberSaveable(screen.selected) { mutableStateOf("") }
    var comparisonMode by remember { mutableStateOf(false) }
    val rack = remember(draft) { EffectRack.fromJson(JSONObject(draft)) }
    val regions = JSONArray(regionsJson)
    val duration = screen.analysis?.optLong("durationMs", 0) ?: 0L
    LaunchedEffect(model.player,loop,start,end) {
        while (true) {
            position = model.player?.currentPosition ?: 0; playing = model.player?.isPlaying ?: false
            val a=start.toLongOrNull(); val b=end.toLongOrNull()
            if (loop && playing && a!=null && b!=null && b>a && (position>=b || position<a)) model.player?.seekTo(a)
            delay(50)
        }
    }
    LaunchedEffect(active) {
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
        chosen = -1; presetName = ""; presetBackup = ""
        } catch (_: Exception) {
            baseId = active; draft = EffectRack().json().toString(); regionsJson = "[]"; start = ""; end = ""; chosen = -1
            model.notice("This revision has an unsupported effect recipe. Audio is retained; start a new recipe or restore an earlier revision.")
        }
    }
    fun update(next: EffectRack) { draft = next.json().toString(); presetName = "" }
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
            model.renderPlan(actor, if (trim) active else baseId, settings, after)
        } catch (e: Exception) { model.notice(e.message ?: "Check effects and region values") }
    }
    WorkCard("Recording timeline") {
        Text("Tap to seek · drag to select · add regions to keep multiple selections. Full duration ${duration} ms.", style = MaterialTheme.typography.bodySmall)
        val analysis = screen.analysis
        if (analysis != null) {
            val peaks = analysis.getJSONArray("peaks"); val spectrum = analysis.getJSONArray("spectrum")
            val color = MaterialTheme.colorScheme.primary
            var dragStart by remember { mutableFloatStateOf(0f) }
            val selectionStart = start.toLongOrNull(); val selectionEnd = end.toLongOrNull()
            Canvas(Modifier.fillMaxWidth().height(190.dp).testTag("waveform").semantics { contentDescription = "Full recording waveform and spectrogram; use time fields for precise accessible selection" }
                .pointerInput(duration, ready) { detectTapGestures { point -> if (ready && duration > 0) model.playbackAt(actor, (point.x / size.width * duration).toLong().coerceIn(0, duration)) } }
                .pointerInput(duration, ready) { detectDragGestures(onDragStart = { dragStart = it.x }, onDrag = { change, _ ->
                    if (ready && duration > 0) {
                        change.consume()
                        val a = (min(dragStart, change.position.x) / size.width * duration).toLong().coerceIn(0, duration)
                        val b = (max(dragStart, change.position.x) / size.width * duration).toLong().coerceIn(0, duration)
                        start = a.toString(); end = b.toString(); chosen = -1
                    }
                }) }) {
                val waveHeight = size.height * .4f
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
                if (selectionStart != null && selectionEnd != null && selectionEnd > selectionStart) shade(selectionStart,selectionEnd,color.copy(alpha=.25f))
                val cursor = size.width * position / duration.coerceAtLeast(1)
                drawLine(Color.White,Offset(cursor,0f),Offset(cursor,size.height),2f)
            }
            Text("Spectral overview · up to 128 brief Hann-window snapshots across the file, not continuous. Events between snapshots can be missed; use precise time fields and listen to the recording.", modifier=Modifier.testTag("spectrogram"), style=MaterialTheme.typography.labelSmall)
            Text("Active revision: peak ${"%.1f".format(java.util.Locale.ROOT,analysis.getDouble("peakDb"))} dBFS · RMS ${"%.1f".format(java.util.Locale.ROOT,analysis.getDouble("rmsDb"))} dBFS · ${analysis.getLong("clippedSamples")} near-full-scale samples. The count is an indicator, not proof of clipping.",style=MaterialTheme.typography.bodySmall)
        } else OutlinedButton(onClick={model.analyze(actor)},enabled=ready) { Text("Analyze audio") }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={model.togglePlayback(actor)},enabled=ready) { Text(if(playing) "Pause" else if(model.player!=null) "Resume" else "Play") }
            OutlinedButton(onClick={model.stop()},enabled=ready) { Text("Stop") }
        }
        Text("${position} / ${duration} ms")
        Row { Checkbox(loop,{loop=it},enabled=ready&&start.toLongOrNull()!=null&&end.toLongOrNull()!=null); Text("Loop selection while this workbench is open") }
        val audioRevisions=selected.items("artifacts").filter{it.getString("type") in setOf("SOURCE","DECODE","ENHANCED_AUDIO")}
        Text("A/B comparison reference")
        for(a in audioRevisions.takeLast(8)) FilterChip(comparisonId==a.getString("id"),{comparisonId=a.getString("id")},{Text("Revision ${a.getInt("revision")}")},enabled=ready)
        OutlinedButton(onClick={
            val reference=comparisonId.ifBlank{audioRevisions.first().getString("id")}
            val time=model.player?.currentPosition?:position
            val next=if(comparisonMode) active else reference
            comparisonMode=!comparisonMode
            model.play(actor,next,time/1000.0)
        },enabled=ready) { Text(if(comparisonMode) "A/B: hear active render" else "A/B: hear reference") }
        Text("A/B seeks the same millisecond position. Pitch/echo/time-shift processing can alter alignment.",style=MaterialTheme.typography.bodySmall)
        Slider(value=(if(scrubbing) seek else position.toFloat()).coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={seek=it;scrubbing=true},onValueChangeFinished={model.playbackAt(actor,seek.toLong());scrubbing=false},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=ready&&duration>0,modifier=Modifier.semantics { contentDescription="Playback position" })
        Numeric(start,{start=it},"Selection start ms",ready); Numeric(end,{end=it},"Selection end ms",ready)
        TextButton(onClick={start="";end="";chosen=-1},enabled=ready) { Text("Use entire recording") }
        OutlinedButton(onClick={render(trim=true)},enabled=ready&&start.isNotBlank()&&end.isNotBlank()) { Text("Create clip from selection") }
    }
    WorkCard("Markers and speech candidates") {
        OutlinedTextField(note,{note=it.take(4000)},label={Text("Examiner note / candidate label")},enabled=ready,modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={model.timelineNote(actor,position,null,note);note=""},enabled=ready&&note.isNotBlank()) { Text("Bookmark cursor") }
            OutlinedButton(onClick={try{val(a,b)=selection();model.timelineNote(actor,a,b,note);note=""}catch(_:Exception){model.notice("Choose a valid interval")}},enabled=ready&&note.isNotBlank()) { Text("Note selection") }
        }
        OutlinedButton(onClick={try{val(a,b)=selection();model.saveSpeechCandidate(actor,a,b,note.take(160))}catch(_:Exception){model.notice("Choose a valid interval")}},enabled=ready) { Text("Mark selected speech candidate") }
        for(event in screen.analysis?.items("events").orEmpty().take(32)) FilterChip(false,{
            start=event.getLong("startMs").toString();end=event.getLong("endMs").toString();chosen=-1
        },{Text("Candidate ${event.getLong("startMs")}–${event.getLong("endMs")} ms ${event.optString("label")}")},enabled=ready)
        for(event in selected.items("audit").filter{it.optString("action") in setOf("BOOKMARK","ANNOTATION")}.takeLast(32).asReversed()) {
            val d=event.optJSONObject("details")?:continue
            if(d.optString("audioArtifactId")==active) TextButton(onClick={model.playbackAt(actor,d.getLong("startMs"))},enabled=ready) { Text("${d.getLong("startMs")} ms · ${d.optString("text")}") }
        }
    }
    Text("Review audio",style=MaterialTheme.typography.headlineSmall)
    Button(onClick={render(after={model.transcribe(actor,transcript)})},enabled=ready,modifier=Modifier.fillMaxWidth()) { Text("Render & transcribe") }
    WorkCard("Quick enhancement presets") {
        Text("Draft settings only until Render. Tap the selected preset again to remove it and restore prior settings.")
        for ((name,preset) in rackPresets) FilterChip(selected=presetName==name,onClick={
            if(presetName==name) { draft=presetBackup; presetName="";presetBackup="" }
            else { if(presetName.isBlank()) presetBackup=draft; draft=preset.json().toString();presetName=name }
        },label={Text(name)},enabled=ready)
        TextButton(onClick={showEffects=!showEffects}) { Text(if(showEffects) "Hide effect controls" else "Effect controls") }
        if(showEffects) {
            for(spec in effectSpecs) {
                val value=rack.values[spec.id]?:EffectValue()
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(spec.title,modifier=Modifier.weight(1f)); Switch(value.enabled,{update(rack.copy(values=rack.values+(spec.id to value.copy(enabled=it))))},enabled=ready,modifier=Modifier.semantics{contentDescription="${spec.title} enabled"})
                }
                Text(spec.description,style=MaterialTheme.typography.bodySmall)
                Slider(value=value.strength.toFloat(),onValueChange={update(rack.copy(values=rack.values+(spec.id to value.copy(strength=it.roundToInt()))))},valueRange=-100f..100f,steps=199,enabled=ready&&value.enabled,modifier=Modifier.semantics{contentDescription="${spec.title} strength"})
                var number by remember(draft,spec.id) { mutableStateOf(value.strength.toString()) }
                Numeric(number,{text->number=text;text.toIntOrNull()?.takeIf{it in -100..100}?.let{update(rack.copy(values=rack.values+(spec.id to value.copy(strength=it))))}},"${spec.title} strength (−100 to 100)",ready&&value.enabled,signed=true)
            }
            Row { for(hz in listOf(50,60)) FilterChip(rack.humHz==hz,{update(rack.copy(humHz=hz))},{Text("$hz Hz")},enabled=ready) }
            var buzz by remember(rack.buzzHz) { mutableStateOf(rack.buzzHz.toString()) }
            Numeric(buzz,{buzz=it;it.toDoubleOrNull()?.takeIf{n->n.isFinite()&&n in 100.0..20000.0}?.let{n->update(rack.copy(buzzHz=n))}},"Buzz notch Hz (below Nyquist)",ready)
            var echo by remember(rack.echoMs) { mutableStateOf(rack.echoMs.toString()) }
            Numeric(echo,{echo=it;it.toIntOrNull()?.takeIf{n->n in 1..500}?.let{n->update(rack.copy(echoMs=n))}},"Echo delay ms (1–500)",ready)
            Text("Frequency values apply when valid. Current: EQ ${rack.paramHz} Hz, Q ${rack.paramQ}; band ${rack.bandLowHz}–${rack.bandHighHz} Hz. All must be below the recording's Nyquist frequency.",style=MaterialTheme.typography.bodySmall)
            var paramHz by remember(rack.paramHz) { mutableStateOf(rack.paramHz.toString()) }
            Numeric(paramHz,{paramHz=it;it.toDoubleOrNull()?.takeIf{n->n.isFinite()&&n in 20.0..20000.0}?.let{n->update(rack.copy(paramHz=n))}},"EQ center Hz (20–20000)",ready)
            var paramQ by remember(rack.paramQ) { mutableStateOf(rack.paramQ.toString()) }
            Numeric(paramQ,{paramQ=it;it.toDoubleOrNull()?.takeIf{n->n.isFinite()&&n in .1..20.0}?.let{n->update(rack.copy(paramQ=n))}},"EQ Q (0.1–20)",ready)
            var bandLow by remember(rack.bandLowHz) { mutableStateOf(rack.bandLowHz.toString()) }
            Numeric(bandLow,{bandLow=it;it.toDoubleOrNull()?.takeIf{n->n.isFinite()&&n>=20&&n<rack.bandHighHz}?.let{n->update(rack.copy(bandLowHz=n))}},"Band low Hz",ready)
            var bandHigh by remember(rack.bandHighHz) { mutableStateOf(rack.bandHighHz.toString()) }
            Numeric(bandHigh,{bandHigh=it;it.toDoubleOrNull()?.takeIf{n->n.isFinite()&&n>rack.bandLowHz&&n<=20000}?.let{n->update(rack.copy(bandHighHz=n))}},"Band high Hz",ready)
            Row { Checkbox(limit,{limit=it},enabled=ready);Text("Soft peak limiter (changes peaks)") }
            TextButton(onClick={update(EffectRack());limit=false},enabled=ready) { Text("Bypass all effects") }
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
    TextButton(onClick={showHistory=!showHistory}) { Text(if(showHistory) "Hide audio history" else "Audio revision history") }
    if(showHistory) WorkCard("Processing changelog") {
        Text("Restore selects an immutable earlier render and its saved recipe. New work branches without deleting later renders. Missing, corrupt or unsupported media may prevent playback/re-render; original and history are retained.")
        val revisions=selected.items("artifacts").filter{it.getString("type") in setOf("SOURCE","DECODE","ENHANCED_AUDIO")}
        val index=revisions.indexOfFirst{it.getString("id")==active}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={model.revision(actor,revisions[index-1].getString("id"))},enabled=ready&&index>0) { Text("Undo") }
            OutlinedButton(onClick={model.revision(actor,revisions[index+1].getString("id"))},enabled=ready&&index>=0&&index<revisions.lastIndex) { Text("Redo (next saved)") }
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

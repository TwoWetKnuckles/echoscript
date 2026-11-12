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

@Composable private fun WorkCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); content()
    } }
}
@Composable private fun Numeric(value: String, change: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, change, label = { Text(label) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
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
    val rack = remember(draft) { EffectRack.fromJson(JSONObject(draft)) }
    val regions = JSONArray(regionsJson)
    val duration = screen.analysis?.optLong("durationMs", 0) ?: 0L
    LaunchedEffect(model.player) {
        while (true) { position = model.player?.currentPosition ?: 0; playing = model.player?.isPlaying ?: false; delay(100) }
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
            Text("Spectrogram · Hann / FFT 256 · full-file overview (128 time tiles); short events require precise time fields.", modifier=Modifier.testTag("spectrogram"), style=MaterialTheme.typography.labelSmall)
        } else OutlinedButton(onClick={model.analyze(actor)},enabled=ready) { Text("Analyze audio") }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={model.togglePlayback(actor)},enabled=ready) { Text(if(playing) "Pause" else if(model.player!=null) "Resume" else "Play") }
            OutlinedButton(onClick={model.stop()},enabled=ready) { Text("Stop") }
        }
        Text("${position} / ${duration} ms")
        Slider(value=if(playing) position.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()) else seek.coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={seek=it},onValueChangeFinished={model.playbackAt(actor,seek.toLong())},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=ready&&duration>0,modifier=Modifier.semantics { contentDescription="Playback position" })
        Numeric(start,{start=it},"Selection start ms",ready); Numeric(end,{end=it},"Selection end ms",ready)
        TextButton(onClick={start="";end="";chosen=-1},enabled=ready) { Text("Use entire recording") }
        OutlinedButton(onClick={render(trim=true)},enabled=ready&&start.isNotBlank()&&end.isNotBlank()) { Text("Create clip from selection") }
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
                Numeric(number,{text->number=text;text.toIntOrNull()?.takeIf{it in -100..100}?.let{update(rack.copy(values=rack.values+(spec.id to value.copy(strength=it))))}},"${spec.title} strength (−100 to 100)",ready&&value.enabled)
            }
            Row { for(hz in listOf(50,60)) FilterChip(rack.humHz==hz,{update(rack.copy(humHz=hz))},{Text("$hz Hz")},enabled=ready) }
            var buzz by remember(rack.buzzHz) { mutableStateOf(rack.buzzHz.toString()) }
            Numeric(buzz,{buzz=it;it.toDoubleOrNull()?.takeIf{n->n.isFinite()&&n in 100.0..20000.0}?.let{n->update(rack.copy(buzzHz=n))}},"Buzz notch Hz (below Nyquist)",ready)
            var echo by remember(rack.echoMs) { mutableStateOf(rack.echoMs.toString()) }
            Numeric(echo,{echo=it;it.toIntOrNull()?.takeIf{n->n in 1..500}?.let{n->update(rack.copy(echoMs=n))}},"Echo delay ms (1–500)",ready)
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

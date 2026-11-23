package org.echoscript.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ProviderControls(model: VaultModel, ready: Boolean, placementSelection: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Speech workflow", style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (provider in TranscriptionProvider.entries) FilterChip(model.transcriptionProvider == provider,
                { model.transcriptionProvider = provider }, { Text(provider.label) }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp))
        }
        Text(if (model.transcriptionProvider == TranscriptionProvider.VOSK) "Vosk runs on this phone. No audio is sent for transcription."
            else "Whisper uses the key configured on your companion server. Review and approve the prepared audio before submission.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("ClearVoice speech enhancement")
                Text(if (model.clearVoiceEnabled) "Enabled · on device" else "Disabled", style = MaterialTheme.typography.bodySmall)
            }
            Switch(model.clearVoiceEnabled, { model.clearVoiceEnabled = it }, enabled = ready,
                modifier = Modifier.semantics { contentDescription = "ClearVoice speech enhancement" })
        }
        if (model.clearVoiceEnabled) {
            if (placementSelection) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (placement in ClearVoicePlacement.entries) FilterChip(model.clearVoicePlacement == placement,
                    { model.clearVoicePlacement = placement }, { Text(placement.label) }, enabled = ready, modifier = Modifier.heightIn(min = 48.dp))
            }
            Text("ClearVoice runs offline on this phone. It creates a separate mono 16 kHz revision and can alter speech. Compare it with the original. Cancel stops at a neural-window boundary.", style = MaterialTheme.typography.bodySmall)
        }
        if (model.transcriptionProvider == TranscriptionProvider.WHISPER)
            Text("Configure the companion address and session token in Settings. Approval is required for each prepared batch.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CompanionSettings(model: VaultModel, ready: Boolean) {
    OutlinedTextField(model.companionAddress, { model.companionAddress = it.take(2048) }, label = { Text("Companion HTTPS address") },
        singleLine = true, enabled = ready, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model.companionToken, { model.companionToken = it.take(512) }, label = { Text("Companion session token") },
        singleLine = true, enabled = ready, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Text("The OpenAI key stays on the companion server. This session token stays in app memory and is cleared when the app process closes. HTTP is allowed only through a localhost tunnel; remote hosts require HTTPS.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { model.companionToken = "" }, enabled = ready && model.companionToken.isNotEmpty()) { Text("Clear session token") }
}

@Composable
internal fun SubmissionApproval(model: VaultModel) {
    val batch = model.remoteApproval ?: return
    var acknowledged by remember(batch.approval.getString("id")) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = model::cancelRemoteApproval,
        title = { Text("Approve Whisper transcription") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Provider: ${batch.provider}\nModel: ${batch.model}\nDestination: ${batch.config.origin}\nAudio: ${"%.3f".format(java.util.Locale.ROOT, batch.duration)} seconds · ${batch.chunks.size} chunks\nBytes: ${batch.chunks.sumOf { it.getLong("bytes") }}")
                SelectionContainer { Text("Submission manifest SHA-256\n${batch.approval.getString("sha256")}\n\nPrepared audio SHA-256\n${batch.canonical.getString("sha256")}", style = MaterialTheme.typography.bodySmall) }
                Text("Audio leaves this device and the companion sends it to OpenAI. API charges and the configured OpenAI project's processing/retention policy apply. Project-specific retention has not been verified here.")
                Text("Earlier completed chunks can be reused from this encrypted case on retry. No alternate provider is selected automatically.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(acknowledged, role = Role.Checkbox, onValueChange = { acknowledged = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(acknowledged, null)
                    Text("I approve sending this prepared audio to the destination above.", Modifier.weight(1f))
                }
            }
        },
        confirmButton = { Button(onClick = model::approveRemoteSubmission, enabled = acknowledged) { Text("Confirm & start") } },
        dismissButton = { TextButton(onClick = model::cancelRemoteApproval) { Text("Cancel") } })
}

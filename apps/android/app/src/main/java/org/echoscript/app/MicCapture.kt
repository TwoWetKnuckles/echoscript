package org.echoscript.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream

internal fun captureMicrophone(
    context: Context,
    vault: EvidenceVault,
    title: String,
    actor: String,
    stop: () -> Boolean,
    cancelled: () -> Boolean,
    progress: (Long) -> Unit,
): JSONObject {
    require(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is required" }
    val rate = 48000
    val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    require(minimum > 0) { "PCM microphone capture is unavailable on this device" }
    val manager = context.getSystemService(AudioManager::class.java)
    val raw = manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    val source = if (raw) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    val config = JSONObject().put("requestedAudioSource", if (raw) "UNPROCESSED" else "VOICE_RECOGNITION")
        .put("captureStartedUtc", EvidenceVault.utc()).put("note", "Actual hardware/OS signal processing can differ by device")
    val limit = minOf(EvidenceVault.MAX_PROCESS_SOURCE_BYTES - 44, (context.filesDir.usableSpace - 64L * 1024 * 1024) / 2)
    require(limit >= 65536) { "Insufficient private storage for microphone capture" }
    var manifest: JSONObject? = null
    var recordedFormat: PcmFormat? = null
    vault.withEncryptedStage(limit, { output ->
        val recorder = AudioRecord.Builder().setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 16384)).build()
        val effects = mutableListOf<AudioEffect>()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not initialize" }
            val format = PcmFormat(recorder.sampleRate, recorder.channelCount)
            recordedFormat = format
            config.put("sampleRate", format.sampleRate).put("channels", format.channels).put("bitDepth", 16).put("audioSource", recorder.audioSource)
            fun disable(name: String, create: () -> AudioEffect?) {
                val state = try {
                    val effect = create()
                    if (effect == null) "unavailable" else {
                        effects.add(effect)
                        val result = effect.setEnabled(false)
                        if (result == AudioEffect.SUCCESS && !effect.enabled) "reported_disabled" else "not_confirmed_disabled"
                    }
                } catch (_: RuntimeException) { "not_confirmed_disabled" }
                config.put(name, state)
            }
            disable("acousticEchoCancellation") { AcousticEchoCanceler.create(recorder.audioSessionId) }
            disable("automaticGainControl") { AutomaticGainControl.create(recorder.audioSessionId) }
            disable("noiseSuppression") { NoiseSuppressor.create(recorder.audioSessionId) }
            val samples = ShortArray(4096)
            val buffer = ByteArray(samples.size * 2)
            var bytes = 0L
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone capture did not start" }
            while (!stop() && bytes + buffer.size <= limit) {
                if (cancelled()) throw InterruptedException("Microphone capture cancelled")
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                check(count > 0 && count % format.channels == 0) { "Microphone read failed or returned an incomplete frame" }
                for (i in 0 until count) putPcm16(buffer, i * 2, samples[i].toInt())
                output.write(buffer, 0, count * 2); bytes += count * 2; progress(bytes)
            }
            if (cancelled()) throw InterruptedException("Microphone capture cancelled")
            config.put("captureEndedUtc", EvidenceVault.utc()).put("stoppedAtStorageLimit", !stop())
        } finally {
            try { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            finally { effects.forEach { it.release() }; recorder.release() }
        }
    }, { stream, _, length ->
        val wave = SequenceInputStream(ByteArrayInputStream(waveHeader(checkNotNull(recordedFormat), length)), stream)
        manifest = wave.use { vault.importSource(it, "Microphone acquisition.wav", title, actor, cancelled, {}, config) }
    })
    return checkNotNull(manifest)
}

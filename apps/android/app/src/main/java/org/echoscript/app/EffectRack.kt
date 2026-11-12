package org.echoscript.app

import org.json.JSONArray
import org.json.JSONObject

internal data class EffectSpec(val id: String, val title: String, val description: String)
internal val effectSpecs = listOf(
    EffectSpec("gain", "Preamp / gain", "−30 to +30 dB"),
    EffectSpec("bass", "Low frequency EQ", "180 Hz · −18 to +18 dB"),
    EffectSpec("presence", "Speech frequency EQ", "1 kHz · −18 to +18 dB"),
    EffectSpec("treble", "High frequency EQ", "Up to 4 kHz · −18 to +18 dB"),
    EffectSpec("band", "Speech band / rumble", "Positive: blend 100–4000 Hz band. Negative: emphasize removed frequencies."),
    EffectSpec("hum", "Low hum isolation", "50 or 60 Hz and first four harmonics. Positive: remove; negative: emphasize residue."),
    EffectSpec("buzz", "High buzz isolation", "Adjustable tonal notch. Positive: remove; negative: emphasize residue."),
    EffectSpec("room", "Reverb / dereverb", "Positive: add short room reflections. Negative: subtract delayed reflections; approximate, not room reconstruction."),
    EffectSpec("echo", "Echo compensation", "Positive: add delayed signal. Negative: subtract estimated echo; set delay below."),
    EffectSpec("shift", "Time shift", "−100 to +100 ms · advance / delay, silence padding; duration unchanged"),
    EffectSpec("declip", "Declip", "Positive: short plateau interpolation (≤64 samples). Negative: emphasize residual; lost peaks cannot be recovered."),
    EffectSpec("noise", "Denoise", "Envelope gate below −45 dBFS. Positive: attenuate up to 24 dB; negative: emphasize removed signal. Not source separation."),
    EffectSpec("pitch", "Pitch shift", "−12 to +12 semitones, two-grain delay crossfade; fixed duration, latency/artifacts possible"),
    EffectSpec("width", "Stereo width", "−100: mono · 0: unchanged · +100: double side level. Stereo only.")
)
internal data class EffectValue(val enabled: Boolean = false, val strength: Int = 0) {
    init { require(strength in -100..100) }
    val amount get() = if (enabled) strength / 100.0 else 0.0
}
internal data class EffectRack(
    val values: Map<String, EffectValue> = emptyMap(),
    val humHz: Int = 60,
    val buzzHz: Double = 4000.0,
    val echoMs: Int = 120,
) {
    init {
        require(values.keys.all { key -> effectSpecs.any { it.id == key } })
        require(humHz in setOf(50, 60) && buzzHz.isFinite() && buzzHz in 100.0..20000.0 && echoMs in 1..500)
    }
    fun amount(id: String) = values[id]?.amount ?: 0.0
    fun json(): JSONObject = JSONObject().put("algorithm", "bounded-native-effect-rack/1")
        .put("humHz", humHz).put("buzzHz", buzzHz).put("echoMs", echoMs)
        .put("mapping", JSONObject().put("gainDb", amount("gain") * 30)
            .put("lowEqDb", amount("bass") * 18).put("midEqDb", amount("presence") * 18).put("highEqDb", amount("treble") * 18)
            .put("timeShiftMs", amount("shift") * 100).put("pitchSemitones", amount("pitch") * 12)
            .put("stereoSideMultiplier", 1 + amount("width")).put("gateThresholdDbFS", -45)
            .put("gateMaxAttenuationDb", kotlin.math.abs(amount("noise")) * 24)
            .put("speechBandHz", JSONArray(listOf(100,4000))).put("humHarmonics",4)
            .put("buzzQ",25).put("echoMix",amount("echo")*.8))
        .put("order", JSONArray(listOf("shift", "declip", "gain", "bass", "presence", "treble", "band", "hum", "buzz", "room", "echo", "noise", "pitch", "width", "optional-limiter")))
        .put("effects", JSONArray(effectSpecs.map { spec ->
            val value = values[spec.id] ?: EffectValue()
            JSONObject().put("id", spec.id).put("enabled", value.enabled).put("strength", value.strength)
        }))
    companion object {
        fun fromJson(json: JSONObject): EffectRack {
            require(json.getString("algorithm") == "bounded-native-effect-rack/1")
            val effects = json.getJSONArray("effects")
            require(effects.length() <= effectSpecs.size)
            val values = mutableMapOf<String, EffectValue>()
            val seen = mutableSetOf<String>()
            for (i in 0 until effects.length()) {
                val e = effects.getJSONObject(i); val id = e.getString("id")
                require(seen.add(id))
                val value = EffectValue(e.getBoolean("enabled"), e.getInt("strength"))
                if (value != EffectValue()) values[id] = value
            }
            return EffectRack(values, json.getInt("humHz"), json.getDouble("buzzHz"), json.getInt("echoMs"))
        }
    }
}
internal val rackPresets = linkedMapOf(
    "Speech band" to EffectRack(mapOf("band" to EffectValue(true, 85), "presence" to EffectValue(true, 12))),
    "Rumble" to EffectRack(mapOf("band" to EffectValue(true, 55))),
    "Hum 50 Hz" to EffectRack(mapOf("hum" to EffectValue(true, 100)), humHz = 50),
    "Hum 60 Hz" to EffectRack(mapOf("hum" to EffectValue(true, 100)), humHz = 60),
    "Gentle denoise" to EffectRack(mapOf("noise" to EffectValue(true, 35)))
)

package org.echoscript.app

import org.json.JSONArray
import org.json.JSONObject

internal data class EffectSpec(val id: String, val title: String, val description: String)
internal val effectSpecs = listOf(
    EffectSpec("gain", "Preamp / gain", "−30 to +30 dB"),
    EffectSpec("bass", "Low frequency EQ", "180 Hz · −18 to +18 dB"),
    EffectSpec("presence", "Speech frequency EQ", "1 kHz · −18 to +18 dB"),
    EffectSpec("treble", "High frequency EQ", "Up to 4 kHz · −18 to +18 dB"),
    EffectSpec("parametric", "Parametric EQ", "Adjustable center and Q · −18 to +18 dB"),
    EffectSpec("frequency", "Selected frequency band", "Positive: isolate adjustable band. Negative: emphasize frequencies outside it."),
    EffectSpec("band", "Speech band / rumble", "Positive: blend 100–4000 Hz band. Negative: emphasize removed frequencies."),
    EffectSpec("hum", "Low hum isolation", "50 or 60 Hz and first four harmonics. Positive: remove; negative: emphasize residue."),
    EffectSpec("buzz", "High buzz isolation", "Adjustable tonal notch. Positive: remove; negative: emphasize residue."),
    EffectSpec("room", "Reverb / dereverb", "Positive: add short room reflections. Negative: subtract delayed reflections; approximate, not room reconstruction."),
    EffectSpec("echo", "Echo compensation", "Positive: add delayed signal. Negative: subtract estimated echo; set delay below."),
    EffectSpec("shift", "Time shift", "−100 to +100 ms · advance / delay, silence padding; duration unchanged"),
    EffectSpec("declip", "Declip", "Positive: short plateau interpolation (≤64 samples). Negative: emphasize residual; lost peaks cannot be recovered."),
    EffectSpec("noise", "Denoise", "Adjustable broadband envelope gate. Positive: attenuate up to 24 dB; negative: emphasize removed signal. Not source separation."),
    EffectSpec("pitch", "Pitch shift", "−12 to +12 semitones, two-grain delay crossfade; fixed duration, latency/artifacts possible"),
    EffectSpec("width", "Stereo width", "−100: mono · 0: unchanged · +100: double side level. Stereo only.")
)
internal data class EffectValue(val enabled: Boolean = false, val strength: Int = 0) {
    init { require(strength in -100..100) }
    val amount get() = if (enabled) strength / 100.0 else 0.0
}
internal data class NoiseFloorReference(val artifactId: String, val sha256: String) {
    init {
        require(artifactId.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")))
        require(sha256.matches(Regex("^[0-9a-f]{64}$")))
    }
    fun json() = JSONObject().put("artifactId", artifactId).put("sha256", sha256)
}
internal data class EffectRack(
    val values: Map<String, EffectValue> = emptyMap(),
    val humHz: Int = 60,
    val buzzHz: Double = 4000.0,
    val echoMs: Int = 120,
    val paramHz: Double = 1000.0,
    val paramQ: Double = 1.0,
    val bandLowHz: Double = 100.0,
    val bandHighHz: Double = 3000.0,
    val noiseThresholdDb: Double = -45.0,
    val noiseReference: NoiseFloorReference? = null,
) {
    init {
        require(values.keys.all { key -> effectSpecs.any { it.id == key } })
        require(humHz in setOf(50, 60) && buzzHz.isFinite() && buzzHz in 100.0..20000.0 && echoMs in 1..500)
        require(paramHz.isFinite() && paramHz in 20.0..20000.0 && paramQ.isFinite() && paramQ in .1..20.0)
        require(bandLowHz.isFinite() && bandHighHz.isFinite() && bandLowHz >= 20 && bandHighHz <= 20000 && bandLowHz < bandHighHz)
        require(noiseThresholdDb.isFinite() && noiseThresholdDb in -90.0..-10.0)
    }
    fun amount(id: String) = values[id]?.amount ?: 0.0
    fun json(): JSONObject = JSONObject().put("algorithm", "bounded-native-effect-rack/3")
        .put("humHz", humHz).put("buzzHz", buzzHz).put("echoMs", echoMs)
        .put("paramHz", paramHz).put("paramQ", paramQ).put("bandLowHz", bandLowHz).put("bandHighHz", bandHighHz)
        .put("noiseThresholdDb", noiseThresholdDb).put("noiseReference", noiseReference?.json() ?: JSONObject.NULL)
        .put("mapping", JSONObject().put("gainDb", amount("gain") * 30)
            .put("lowEqDb", amount("bass") * 18).put("midEqDb", amount("presence") * 18).put("highEqDb", amount("treble") * 18)
            .put("parametricEqDb", amount("parametric") * 18).put("frequencyBandHz", JSONArray(listOf(bandLowHz, bandHighHz)))
            .put("timeShiftMs", amount("shift") * 100).put("pitchSemitones", amount("pitch") * 12)
            .put("stereoSideMultiplier", 1 + amount("width")).put("gateThresholdDbFS", noiseThresholdDb)
            .put("gateMaxAttenuationDb", kotlin.math.abs(amount("noise")) * 24)
            .put("speechBandHz", JSONArray(listOf(100,4000))).put("humHarmonics",4)
            .put("buzzQ",25).put("echoMix",amount("echo")*.8))
        .put("order", JSONArray(listOf("shift", "declip", "gain", "bass", "presence", "treble", "parametric", "frequency", "band", "hum", "buzz", "room", "echo", "noise", "pitch", "width", "optional-limiter")))
        .put("effects", JSONArray(effectSpecs.map { spec ->
            val value = values[spec.id] ?: EffectValue()
            JSONObject().put("id", spec.id).put("enabled", value.enabled).put("strength", value.strength)
        }))
    companion object {
        fun fromJson(json: JSONObject): EffectRack {
            val algorithm = json.getString("algorithm")
            require(algorithm in setOf("bounded-native-effect-rack/1", "bounded-native-effect-rack/2", "bounded-native-effect-rack/3"))
            val effects = json.getJSONArray("effects")
            require(effects.length() <= effectSpecs.size)
            val values = mutableMapOf<String, EffectValue>()
            val seen = mutableSetOf<String>()
            for (i in 0 until effects.length()) {
                val e = effects.getJSONObject(i); val id = e.getString("id")
                require(effectSpecs.any { it.id == id } && seen.add(id))
                val value = EffectValue(e.getBoolean("enabled"), e.getInt("strength"))
                if (value != EffectValue()) values[id] = value
            }
            val reference = if (algorithm.endsWith("/3")) {
                require(json.has("noiseReference"))
                if (json.isNull("noiseReference")) null else json.getJSONObject("noiseReference").let {
                    NoiseFloorReference(it.getString("artifactId"), it.getString("sha256"))
                }
            } else null
            return EffectRack(values, json.getInt("humHz"), json.getDouble("buzzHz"), json.getInt("echoMs"),
                json.optDouble("paramHz", 1000.0), json.optDouble("paramQ", 1.0),
                json.optDouble("bandLowHz", 100.0), json.optDouble("bandHighHz", 3000.0),
                if (algorithm.endsWith("/3")) json.getDouble("noiseThresholdDb") else -45.0, reference)
        }
    }
}
// Intermediate audio revisions must restore only the passes already rendered.
internal fun completedRegionPlan(plan: String?, completed: Int): String? {
    if (plan == null) return null
    val value = JSONObject(plan)
    val regions = value.getJSONArray("regions")
    if (regions.length() == 0) return value.toString()
    require(completed in 1..regions.length())
    val prefix = JSONArray()
    for (i in 0 until completed) prefix.put(regions.getJSONObject(i))
    return value.put("regions", prefix).toString()
}
internal val rackPresets = linkedMapOf(
    "Speech band" to EffectRack(mapOf("band" to EffectValue(true, 85), "presence" to EffectValue(true, 12))),
    "Rumble" to EffectRack(mapOf("band" to EffectValue(true, 55))),
    "Hum 50 Hz" to EffectRack(mapOf("hum" to EffectValue(true, 100)), humHz = 50),
    "Hum 60 Hz" to EffectRack(mapOf("hum" to EffectValue(true, 100)), humHz = 60),
    "Gentle denoise" to EffectRack(mapOf("noise" to EffectValue(true, 35)))
)

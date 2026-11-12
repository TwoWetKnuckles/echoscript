package org.echoscript.app

// This is a reversible, in-memory product-design preview, not billing or proof
// of purchase. Production entitlements require a separately verified provider.
internal enum class AccessPreview(val label: String) {
    EVALUATION("All tools"),
    FREE("Free preview"),
    SUBSCRIPTION("Subscription preview"),
    ONE_TIME("One-time preview"),
}

internal enum class PremiumCapability(val title: String) {
    FULL_SPECTRUM("Full-recording spectrum"),
    DETAIL_SPECTRUM("Detailed spectral inspection"),
    NOISE_MEASUREMENT("Measured noise floor"),
    CLIPPING_COMPARISON("Waveform and clipping comparison"),
    ADVANCED_EFFECTS("Advanced effects"),
    MULTIPLE_REGIONS("Multiple processing regions"),
}

internal object PlanPreviewPolicy {
    private val advancedEffects = setOf("parametric", "frequency", "room", "echo", "shift", "declip", "pitch", "width")

    fun allows(preview: AccessPreview, capability: PremiumCapability): Boolean = when (capability) {
        PremiumCapability.FULL_SPECTRUM, PremiumCapability.DETAIL_SPECTRUM,
        PremiumCapability.NOISE_MEASUREMENT, PremiumCapability.CLIPPING_COMPARISON,
        PremiumCapability.ADVANCED_EFFECTS, PremiumCapability.MULTIPLE_REGIONS -> preview != AccessPreview.FREE
    }

    // Playback, history, import and all exports are never restricted here.
    // Check complete planned renders before selecting or modifying any revision.
    fun renderRequirement(regions: List<RenderSettings>): PremiumCapability? {
        if (regions.size > 1) return PremiumCapability.MULTIPLE_REGIONS
        if (regions.any { setting ->
            val rack = setting.rack
            rack != null && (advancedEffects.any { rack.amount(it) != 0.0 } ||
                (rack.amount("noise") != 0.0 && rack.noiseReference != null))
        }) return PremiumCapability.ADVANCED_EFFECTS
        return null
    }
}

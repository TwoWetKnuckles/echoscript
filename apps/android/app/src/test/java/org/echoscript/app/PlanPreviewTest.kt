package org.echoscript.app

import org.junit.Assert.*
import org.junit.Test

class PlanPreviewTest {
    @Test fun freeAndBothPremiumPreviewsHaveConsistentAccess() {
        for (capability in PremiumCapability.entries) {
            assertFalse(PlanPreviewPolicy.allows(AccessPreview.FREE, capability))
            assertTrue(PlanPreviewPolicy.allows(AccessPreview.SUBSCRIPTION, capability))
            assertTrue(PlanPreviewPolicy.allows(AccessPreview.ONE_TIME, capability))
            assertTrue(PlanPreviewPolicy.allows(AccessPreview.EVALUATION, capability))
        }
    }

    @Test fun basicClipAndEffectsStayFreeButAdvancedAndMultiplePassesAreIdentified() {
        val basic = RenderSettings(rack=EffectRack(mapOf("gain" to EffectValue(true, 25), "hum" to EffectValue(true, 50))))
        assertNull(PlanPreviewPolicy.renderRequirement(listOf(basic)))
        assertNull(PlanPreviewPolicy.renderRequirement(listOf(RenderSettings(rack=EffectRack(), trim=true, endMs=1000))))
        assertNull(PlanPreviewPolicy.renderRequirement(listOf(RenderSettings(rack=EffectRack(mapOf("pitch" to EffectValue(false, 100)))))))
        assertEquals(PremiumCapability.ADVANCED_EFFECTS, PlanPreviewPolicy.renderRequirement(
            listOf(RenderSettings(rack=EffectRack(mapOf("pitch" to EffectValue(true, 20)))))))
        assertEquals(PremiumCapability.MULTIPLE_REGIONS, PlanPreviewPolicy.renderRequirement(listOf(basic, basic)))
        val measured = NoiseFloorReference("01234567-89ab-4cde-8fab-0123456789ab", "a".repeat(64))
        val rack = EffectRack(mapOf("noise" to EffectValue(true, 20)), noiseReference=measured)
        assertEquals(PremiumCapability.ADVANCED_EFFECTS, PlanPreviewPolicy.renderRequirement(listOf(RenderSettings(rack=rack))))
    }
}

package org.echoscript.app

import org.junit.Assert.*
import org.junit.Test

class ClearVoiceProgressTest {
    @Test fun estimateWaitsForMeasurementsAndLabelsOnlyTheEnhancementStage() {
        assertTrue(clearVoiceProgress(0, 100, 0, 0).contains("estimating"))
        assertTrue(clearVoiceProgress(40, 100, 20000, 4).contains("estimating"))
        assertTrue(clearVoiceProgress(50, 100, 20000, 5).contains("about 20 s left for enhancement"))
        assertEquals("Enhancing speech · 100%", clearVoiceProgress(100, 100, 40000, 10))
    }

    @Test fun longEstimatesCarryMinutesIntoHoursAndRejectInvalidCounters() {
        assertTrue(clearVoiceProgress(50, 100, 7199000, 5).contains("2 h 0 min"))
        assertThrows(IllegalArgumentException::class.java) { clearVoiceProgress(1, 0, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { clearVoiceProgress(101, 100, 1, 1) }
    }
}

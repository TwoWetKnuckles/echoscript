package org.echoscript.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProcessingPlanTest {
    private fun plan(): ProcessingPlan {
        val id = UUID.randomUUID().toString()
        return ProcessingPlan(id, id, "2026-11-14T00:00:00Z", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "a".repeat(64), "SYNTHETIC_QA", LocalJobKind.RENDER_TRANSCRIBE,
            listOf(RenderSettings(rack = EffectRack(), workbenchPlan = "{\"baseId\":\"synthetic\"}", startMs = 100, endMs = 800, limit = false)),
            ClearVoicePlacement.BEFORE, "VOSK")
    }

    @Test fun retryPreservesTheExactRequestAndRootButHasANewSchedulingIdentity() {
        val original = plan()
        val decoded = ProcessingPlan.parse(original.json())
        assertEquals(original, decoded)
        val retry = decoded.retry()
        assertNotEquals(original.id, retry.id)
        assertEquals(original.rootId, retry.rootId)
        assertEquals(original.requestSha256, retry.requestSha256)
        assertEquals(original.regions, retry.regions)
        retry.checkRuntime()
    }

    @Test fun alteredUnknownOrAmbiguousSettingsCannotBeReplayed() {
        val original = plan()
        val altered = original.json()
        altered.getJSONObject("request").getJSONArray("regions").getJSONObject(0).put("gainDb", 6)
        assertThrows(IllegalArgumentException::class.java) { ProcessingPlan.parse(altered) }
        assertThrows(IllegalArgumentException::class.java) { ProcessingPlan.parse(original.json().put("token", "synthetic")) }
        val unknown = original.json(); unknown.getJSONObject("request").put("origin", "https://example.invalid")
        assertThrows(IllegalArgumentException::class.java) { ProcessingPlan.parse(unknown) }
        val malformed = renderSettingsJson(original.regions.single()).put("limit", "true")
        assertThrows(IllegalArgumentException::class.java) { parseRenderSettings(malformed) }
        val fractional = original.json(); fractional.getJSONObject("request").getJSONArray("regions").getJSONObject(0).put("startMs", 100.5)
        assertThrows(IllegalArgumentException::class.java) { ProcessingPlan.parse(fractional) }
        fractional.put("requestSha256", EvidenceVault.sha(EvidenceVault.canonical(fractional.getJSONObject("request"))))
        assertThrows(IllegalArgumentException::class.java) { ProcessingPlan.parse(fractional) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(regions = List(33) { RenderSettings() }) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(softwareVersion = "earlier-build").checkRuntime() }
    }

    @Test fun remoteApprovalAndCredentialsAreNeverSerializableWork() {
        val original = plan()
        assertThrows(IllegalArgumentException::class.java) { original.copy(requestedProvider = "WHISPER") }
        val preparation = original.copy(kind = LocalJobKind.RENDER, requestedProvider = "WHISPER")
        assertEquals(LocalJobKind.RENDER, ProcessingPlan.parse(preparation.json()).kind)
        for (key in listOf("token", "origin", "approval", "apiKey", "uri")) assertFalse(preparation.body().has(key))
        assertThrows(IllegalArgumentException::class.java) { original.copy(actor = "bad\nactor") }
        assertThrows(IllegalArgumentException::class.java) { original.copy(baseSha256 = "invalid") }
    }
}

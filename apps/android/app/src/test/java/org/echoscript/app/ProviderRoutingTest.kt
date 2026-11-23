package org.echoscript.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderRoutingTest {
    @Test fun connectionRequiresTlsAndKeepsCredentialsOutOfDescriptions() {
        val token = "synthetic-secret-".repeat(3)
        val config = CompanionConfig("https://companion.example/", token)
        assertEquals("https://companion.example", config.origin)
        assertFalse(config.toString().contains(token))
        CompanionConfig("http://127.0.0.1:8787", token)
        for (address in listOf("http://192.168.1.2", "https://user:password@companion.example", "https://companion.example/?token=secret", "file:///tmp/audio", "https://companion.example/path", "https://companion.example/#x")) {
            assertThrows(IllegalArgumentException::class.java) { CompanionConfig(address, token) }
        }
    }
    @Test fun whisperKeepsReturnedTimingAndDoesNotInventScoresSpeakersOrAlternatives() {
        val output = JSONArray()
        appendWhisperSegments(output, JSONObject("""{"text":"synthetic speech","segments":[{"text":"synthetic speech","start":0.1,"end":1.4}],"words":[{"word":"synthetic","start":0.1,"end":0.8},{"word":"speech","start":0.9,"end":1.4}]}"""), 30.0, 2.0)
        val segment = output.getJSONObject(0)
        assertEquals(30.1, segment.getDouble("start"), 1e-9)
        assertEquals(31.4, segment.getJSONArray("words").getJSONObject(1).getDouble("end"), 1e-9)
        assertTrue(segment.isNull("speaker")); assertTrue(segment.isNull("confidence"))
        assertEquals(0, segment.getJSONArray("alternatives").length())
        assertEquals("UNVERIFIED", segment.getString("verification"))
        val changed = JSONArray()
        appendWhisperSegments(changed, JSONObject("""{"text":"synthetic","segments":[{"text":"synthetic","start":-0.2,"end":4}]}"""), 0.0, 2.0)
        assertEquals(-0.2, changed.getJSONObject(0).getDouble("start"), 0.0)
        assertTrue(changed.getJSONObject(0).getJSONArray("flags").toString().contains("TIMESTAMP_ANOMALY"))
    }
    @Test fun fallbackTimingAndEmptyOutputAreExplicit() {
        val output = JSONArray()
        appendWhisperSegments(output, JSONObject("""{"text":"synthetic"}"""), 60.0, 2.0)
        assertTrue(output.getJSONObject(0).getJSONArray("flags").toString().contains("CHUNK_BOUNDARY_TIMING_ONLY"))
        val empty = JSONArray(); appendWhisperSegments(empty, JSONObject("""{"text":""}"""), 0.0, 1.0)
        assertEquals(0, empty.length())
    }
}

package org.echoscript.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteChunkPolicyTest {
    private fun chunk(frames: Int = 120 * 16000) = JSONObject().put("type", "TRANSCRIPTION_TRANSMISSION")
        .put("bytes", frames * 2L + 44).put("parents", JSONArray().put("canonical-input"))
        .put("operation", JSONObject().put("algorithm", "companion-exact-pcm-chunk").put("version", "2")
            .put("parameters", JSONObject().put("startFrame", 0).put("frames", frames)
                .put("provider", "openai-whisper").put("model", "whisper-1")))

    @Test fun legacyShortChunkCannotStandInForALongerRangeOnRetry() {
        val old = chunk(30 * 16000)
        old.getJSONObject("operation").put("version", "1")
        assertFalse(RemoteChunkPolicy.matches(old, "canonical-input", 0, 120 * 16000, "openai-whisper", "whisper-1"))
        assertTrue(RemoteChunkPolicy.matches(chunk(), "canonical-input", 0, 120 * 16000, "openai-whisper", "whisper-1"))
    }

    @Test fun reuseRequiresExactRangeParentProviderModelAndByteCount() {
        val value = chunk()
        fun reject(parent: String = "canonical-input", start: Long = 0, frames: Int = 120 * 16000,
            provider: String = "openai-whisper", model: String = "whisper-1") =
            assertFalse(RemoteChunkPolicy.matches(value, parent, start, frames, provider, model))
        reject(parent = "different-parent")
        reject(start = 16000)
        reject(frames = 16000)
        reject(provider = "different-provider")
        reject(model = "different-model")
        value.put("bytes", value.getLong("bytes") - 2)
        assertFalse(RemoteChunkPolicy.matches(value, "canonical-input", 0, 120 * 16000, "openai-whisper", "whisper-1"))
        val fractional = chunk()
        fractional.getJSONObject("operation").getJSONObject("parameters").put("startFrame", .5)
        assertFalse(RemoteChunkPolicy.matches(fractional, "canonical-input", 0, 120 * 16000, "openai-whisper", "whisper-1"))
    }
}

package org.echoscript.app

import org.json.JSONObject

/** Below 4 MiB per PCM request, with longer context and fewer durable commits. */
internal object RemoteChunkPolicy {
    const val SECONDS = 120
    const val MAX_PCM_BYTES = SECONDS * 32000
    const val MAX_WAVE_BYTES = MAX_PCM_BYTES + 44
    const val VERSION = "2"
    private fun exactInteger(value: Any?, expected: Long) = (value is Int || value is Long) && (value as Number).toLong() == expected

    fun matches(artifact: JSONObject, parent: String, start: Long, frames: Int, provider: String, model: String): Boolean {
        val operation = artifact.getJSONObject("operation")
        val parameters = operation.getJSONObject("parameters")
        return artifact.getString("type") == "TRANSCRIPTION_TRANSMISSION" &&
            operation.getString("algorithm") == "companion-exact-pcm-chunk" &&
            operation.getString("version") == VERSION &&
            artifact.getJSONArray("parents").length() == 1 && artifact.getJSONArray("parents").getString(0) == parent &&
            artifact.getLong("bytes") == frames * 2L + 44 &&
            exactInteger(parameters.opt("startFrame"), start) && exactInteger(parameters.opt("frames"), frames.toLong()) &&
            parameters.optString("provider") == provider && parameters.optString("model") == model
    }
}

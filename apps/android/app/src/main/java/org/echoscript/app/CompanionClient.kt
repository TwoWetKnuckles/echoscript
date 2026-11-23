package org.echoscript.app

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal enum class TranscriptionProvider(val label: String) { VOSK("Vosk · on device"), WHISPER("Whisper API") }
internal enum class ClearVoicePlacement(val label: String) { BEFORE("Before effects"), AFTER("After effects") }

internal data class CompanionConfig(val address: String, val token: String) {
    val origin: String
    init {
        val url = try { URI.create(address.trim().trimEnd('/')) }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("Enter a valid companion HTTPS address") }
        require(url.scheme == "https" || (url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost"))) { "Use HTTPS, or a loopback HTTP tunnel" }
        require(!url.host.isNullOrBlank() && url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null && url.path.orEmpty() in setOf("", "/")) { "Use the companion origin without a path, query or credentials" }
        require(token.length in 32..512 && token.none { it.code < 33 || it.code > 126 }) { "Enter the companion access token (at least 32 characters)" }
        origin = url.toASCIIString().trimEnd('/')
    }
    // Neither toString nor error messages may disclose the session credential.
    override fun toString() = "CompanionConfig($origin, token=REDACTED)"
}

internal class CompanionClient(private val config: CompanionConfig, private val cancelled: () -> Boolean) {
    fun post(path: String, bytes: ByteArray, contentType: String, consent: String? = null, maxResponse: Int = 8 * 1024 * 1024): ByteArray {
        require(path in setOf("/v1/consents", "/v1/transcriptions/openai-whisper"))
        if (cancelled()) throw InterruptedException("Request cancelled")
        val connection = URI(config.origin + path).toURL().openConnection() as HttpURLConnection
        val monitor = Executors.newSingleThreadScheduledExecutor()
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 300000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            connection.setRequestProperty("Content-Type", contentType)
            if (consent != null) connection.setRequestProperty("X-Consent-ID", consent)
            connection.setFixedLengthStreamingMode(bytes.size)
            monitor.scheduleWithFixedDelay({ if (cancelled()) connection.disconnect() }, 0, 100, TimeUnit.MILLISECONDS)
            connection.outputStream.use { out ->
                var offset = 0
                while (offset < bytes.size) {
                    if (cancelled()) throw InterruptedException("Request cancelled")
                    val count = minOf(PCM_BLOCK_BYTES, bytes.size - offset); out.write(bytes, offset, count); offset += count
                }
            }
            val status = connection.responseCode
            check(status == 200) { "Companion request failed (HTTP $status). Check service configuration; no other provider was used." }
            val result = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val block = ByteArray(PCM_BLOCK_BYTES)
                while (true) {
                    if (cancelled()) throw InterruptedException("Request cancelled")
                    val count = input.read(block); if (count < 0) break
                    require(result.size() + count <= maxResponse) { "Companion response exceeds the safety limit" }
                    result.write(block, 0, count)
                }
            }
            return result.toByteArray()
        } catch (error: Exception) {
            if (cancelled()) throw InterruptedException("Request cancelled; completed revisions are preserved")
            if (error is IllegalStateException || error is IllegalArgumentException) throw error
            throw IllegalStateException("Companion connection failed. Check the address, TLS, service and token; no alternate provider was used.")
        } finally { monitor.shutdownNow(); connection.disconnect() }
    }
    fun submit(provider: String, model: String, bytes: ByteArray): ByteArray {
        val body = JSONObject().put("artifactHash", EvidenceVault.sha(bytes)).put("duration", (bytes.size - 44) / 32000.0)
            .put("approved", true).put("provider", provider).put("model", model).put("acknowledgedExternal", true)
        val consent = JSONObject(post("/v1/consents", body.toString().toByteArray(), "application/json", maxResponse = 65536).toString(Charsets.UTF_8)).getString("consentId")
        require(consent.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "Invalid companion consent" }
        require(provider == "openai-whisper" && model == "whisper-1")
        return post("/v1/transcriptions/openai-whisper", bytes, "audio/wav", consent)
    }
}

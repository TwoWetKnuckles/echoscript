package org.echoscript.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CompanionClientTest {
    @Test(timeout = 10000) fun transportCompletesSeparateConsentAndExactAudioRequests() {
        val token = "SYNTHETIC-companion-session-token-00000001"
        val audio = waveHeader(PcmFormat(16000, 1), 2) + byteArrayOf(0, 0)
        val failure = AtomicReference<Throwable?>()
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val runner = thread(isDaemon = true) {
                try {
                    for (expected in listOf("/v1/consents", "/v1/transcriptions/openai-whisper")) server.accept().use { client ->
                        client.soTimeout = 3000
                        val input = BufferedInputStream(client.getInputStream())
                        fun line(): String {
                            val out = java.io.ByteArrayOutputStream()
                            while (true) { val b = input.read(); check(b >= 0); if (b == 10) break; check(out.size() < 8192); if (b != 13) out.write(b) }
                            return out.toString("US-ASCII")
                        }
                        assertEquals(expected, line().split(' ')[1])
                        val headers = mutableMapOf<String, String>()
                        while (true) { val row = line(); if (row.isEmpty()) break; val i = row.indexOf(':'); headers[row.substring(0, i).lowercase()] = row.substring(i + 1).trim() }
                        assertEquals("Bearer $token", headers["authorization"])
                        val bytes = ByteArray(checkNotNull(headers["content-length"]).toInt())
                        DataInputStream(input).readFully(bytes)
                        val response = if (expected == "/v1/consents") {
                            assertEquals(EvidenceVault.sha(audio), JSONObject(bytes.toString(Charsets.UTF_8)).getString("artifactHash"))
                            """{"consentId":"synthetic-one-use-consent"}"""
                        } else {
                            assertEquals("synthetic-one-use-consent", headers["x-consent-id"])
                            assertArrayEquals(audio, bytes)
                            """{"text":"synthetic"}"""
                        }.toByteArray()
                        client.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush() }
                    }
                } catch (error: Throwable) { failure.set(error) }
            }
            try {
                val client = CompanionClient(CompanionConfig("http://127.0.0.1:${server.localPort}", token)) { false }
                val result = client.submit("openai-whisper", "whisper-1", audio)
                assertEquals("synthetic", JSONObject(result.toString(Charsets.UTF_8)).getString("text"))
                runner.join(1000)
                failure.get()?.let { throw AssertionError("Synthetic companion failed", it) }
                assertFalse(runner.isAlive)
            } finally { server.close(); runner.join(1000) }
        }
    }
}

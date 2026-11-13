package org.echoscript.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CompanionWorkflowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun waitFor(text: String) = ui.waitUntil(60000) { ui.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun click(text: String) { ui.onNodeWithText(text).performScrollTo().performClick() }

    @Test fun whisperSelectionPreparesWithoutSendingAndRequiresExplicitApproval() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = EvidenceVault(context)
        val prior = vault.listCases().cases.map { it.getString("caseId") }.toSet()
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted("192.168.1.9"))
        assertFalse(policy.isCleartextTrafficPermitted("example.org"))
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        TestCompanion().use { server ->
            click("Load synthetic speech sample")
            waitFor("Original preserved. Waveform and speech candidates are ready.")
            val case = vault.listCases().cases.single { it.getString("caseId") !in prior }
            val sourceHash = case.items("artifacts").first().getString("sha256")
            try {
                ui.runOnIdle {
                    val model = ViewModelProvider(ui.activity)[VaultModel::class.java]
                    assertEquals(TranscriptionProvider.VOSK, model.transcriptionProvider)
                    assertFalse(model.clearVoiceEnabled)
                    model.companionAddress = "http://127.0.0.1:${server.port}"
                    model.companionToken = server.token
                }
                click("Whisper API")
                click("Render & transcribe")
                waitFor("Approve Whisper transcription")
                assertEquals(0, server.requests.size)
                ui.onNodeWithText("Confirm & start").assertIsNotEnabled()
                ui.onNodeWithText("Cancel", useUnmergedTree = false).performClick()
                waitFor("Submission cancelled. No audio was sent")
                assertEquals(0, server.requests.size)
                click("Render & transcribe")
                waitFor("Approve Whisper transcription")
                click("I approve sending this prepared audio to the destination above.")
                ui.onNodeWithText("Confirm & start").performClick()
                waitFor("Whisper draft saved.")
                server.failure.get()?.let { throw AssertionError("Synthetic companion failed", it) }
                assertEquals(listOf("/v1/consents", "/v1/transcriptions/openai-whisper"), server.requests.toList())
                val saved = vault.listCases().cases.single { it.getString("caseId") == case.getString("caseId") }
                val transcript = OfflineWorkflow(context, vault).readTranscript(saved, checkNotNull(saved.latestTranscript()))
                assertEquals("openai-whisper", transcript.getString("provider"))
                assertTrue(transcript.getJSONArray("segments").getJSONObject(0).isNull("confidence"))
                assertEquals(sourceHash, saved.items("artifacts").first().getString("sha256"))
                assertTrue(vault.verify(saved).contains("agree"))
                assertTrue(saved.items("audit").any { it.getString("action") == "AUDIO_SUBMISSION_APPROVED" })
                val output = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it).apply { mkdirs() } }
                if (output != null) java.io.File(output, "synthetic-whisper.echocase").outputStream().use { vault.exportPackage(saved, it, "SYNTHETIC_QA") }

            } finally {
                assertTrue(java.io.File(context.filesDir, "EvidenceVault/cases/${case.getString("caseId")}").deleteRecursively())
            }
        }
    }

    /** Contract fixture only. It verifies transport and approval, not model accuracy. */
    private class TestCompanion : AutoCloseable {
        val token = "synthetic-companion-session-token-00000001"
        val requests = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable?>()
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        private var expectedHash: String? = null
        private val runner = thread(isDaemon = true) {
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    client.soTimeout = 10000
                    val input = BufferedInputStream(client.getInputStream())
                    fun line(): String {
                        val out = java.io.ByteArrayOutputStream()
                        while (true) { val b = input.read(); check(b >= 0); if (b == 10) break; check(out.size() < 8192); if (b != 13) out.write(b) }
                        return out.toString("US-ASCII")
                    }
                    val path = line().split(' ')[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) { val line = line(); if (line.isEmpty()) break; val i = line.indexOf(':'); headers[line.substring(0, i).lowercase()] = line.substring(i + 1).trim() }
                    check(headers["authorization"] == "Bearer $token")
                    val length = checkNotNull(headers["content-length"]).toInt(); check(length in 1..(30 * 32000 + 65536))
                    val body = ByteArray(length); DataInputStream(input).readFully(body)
                    requests.add(path)
                    val response = if (path == "/v1/consents") {
                        val c = JSONObject(body.toString(Charsets.UTF_8)); check(c.getString("provider") == "openai-whisper")
                        check(c.getString("model") == "whisper-1" && c.getBoolean("acknowledgedExternal"))
                        expectedHash = c.getString("artifactHash")
                        """{"consentId":"synthetic-one-use-consent"}"""
                    } else {
                        check(path == "/v1/transcriptions/openai-whisper" && headers["x-consent-id"] == "synthetic-one-use-consent")
                        check(EvidenceVault.sha(body) == expectedHash); expectedHash = null
                        """{"text":"synthetic test","words":[{"word":"synthetic","start":0.1,"end":0.4},{"word":"test","start":0.5,"end":0.8}]}"""
                    }.toByteArray()
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush()
                    }
                }
            } catch (error: Throwable) { if (!socket.isClosed) failure.set(error) }
        }
        override fun close() { socket.close(); runner.join(2000) }
    }
}

package org.echoscript.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Host kills the real app process between phases; never run against real evidence. */
class CrashRecoveryTest {
    @Test fun checkpointAndInterrupt() {
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        Assume.assumeTrue("Executed separately by qa-android-crash.py with an actual host process kill", phase != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state = File(context.filesDir, "synthetic-crash-state.json")
        val marker = File(context.filesDir, "synthetic-crash-ready")
        val root = File(context.filesDir, "EvidenceVault/cases")
        val bytes = "SYNTHETIC CRASH TEST DATA — NOT EVIDENCE".toByteArray()
        val vault = EvidenceVault(context)
        when (phase) {
            "prepare" -> {
                check(!state.exists()) { "A previous synthetic interruption needs review before a new test" }
                marker.delete()
                val m = vault.importSource(bytes.inputStream(), "SYNTHETIC-CRASH.txt", "Synthetic completed case before interruption", "SYNTHETIC_QA")
                val saved = JSONObject().put("caseId", m.getString("caseId")).put("sourceId", m.items("artifacts").first().getString("id"))
                    .put("sha256", EvidenceVault.sha(bytes)).put("existingFolders", JSONArray(root.listFiles()?.map { it.name } ?: emptyList<String>()))
                state.outputStream().use { it.write(saved.toString().toByteArray()); it.fd.sync() }
            }
            "interrupt" -> {
                check(state.exists() && !marker.exists())
                val generated = object : InputStream() {
                    var emitted = 0L
                    override fun read(): Int = error("Block reads required by the vault")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (emitted >= 2 * 1024 * 1024) {
                            marker.outputStream().use { it.write("SYNTHETIC_IMPORT_BLOCKED".toByteArray()); it.fd.sync() }
                            // A missed host kill is a test failure, not a successful crash test.
                            CountDownLatch(1).await(45, TimeUnit.SECONDS)
                            error("The host did not interrupt the synthetic acquisition")
                        }
                        val count = minOf(length, 65536)
                        java.util.Arrays.fill(buffer, offset, offset + count, 83.toByte())
                        emitted += count
                        return count
                    }
                }
                vault.importSource(generated, "SYNTHETIC-INTERRUPTED.bin", "Synthetic interrupted acquisition", "SYNTHETIC_QA")
                fail("The host must terminate this phase before any completed import")
            }
            "verify" -> {
                check(marker.exists()) { "No confirmed interruption boundary" }
                val saved = JSONObject(state.readText())
                val m = vault.listCases().cases.single { it.getString("caseId") == saved.getString("caseId") }
                assertEquals(saved.getString("sha256"), EvidenceVault.sha(vault.open(m.getString("caseId"), saved.getString("sourceId")).use { it.readBytes() }))
                assertTrue(vault.verify(m).contains("agree"))
                val baseline = saved.getJSONArray("existingFolders").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                val interrupted = root.listFiles()!!.filter { it.name !in baseline }
                assertEquals("Exactly one synthetic interrupted acquisition expected", 1, interrupted.size)
                val orphan = interrupted.single()
                assertTrue(orphan.walkTopDown().any { it.name.endsWith(".pending") && it.length() > 0 })
                val listing = vault.listCases()
                assertFalse(listing.cases.any { it.getString("caseId") == orphan.name })
                assertTrue(listing.warnings.any { it.contains(orphan.name) && it.contains("not a complete case") })
                val output = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))).apply { mkdirs() }
                File(output, "process-kill-import.json").writeText(JSONObject().put("check", "ANDROID_ACTUAL_PROCESS_KILL_IMPORT_SUBSET")
                    .put("result", "PASS").put("originalPreserved", true).put("falseCompleteCase", false)
                    .put("interruptedEncryptedBytesPreservedAndReported", true)
                    .put("scope", "Actual host force-stop during encrypted acquisition; not power loss, all-stage recovery or automatic resumable jobs").toString())
                // Delete only this harness's own explicitly synthetic test data.
                assertTrue(orphan.deleteRecursively())
                assertTrue(File(root, saved.getString("caseId")).deleteRecursively())
                assertTrue(state.delete()); assertTrue(marker.delete())
            }
            else -> error("Unknown synthetic crash phase")
        }
    }
}

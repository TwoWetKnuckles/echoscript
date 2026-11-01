package org.echoscript.app
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
@RunWith(AndroidJUnit4::class)
class VaultRoundTripTest {
    @Test fun corruptLatestSnapshotIsReportedWithoutSilentFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val vault = EvidenceVault(context)
        val manifest = vault.importSource("SYNTHETIC TEST DATA — NOT EVIDENCE".toByteArray().inputStream(), "synthetic.txt", "Synthetic snapshot tamper", "TEST")
        val id = manifest.getString("caseId")
        val file = java.io.File(context.filesDir, "EvidenceVault/cases/$id/manifests").listFiles()!!.maxBy { it.name }
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        val listing = EvidenceVault(context).listCases()
        assertFalse(listing.cases.any { it.getString("caseId") == id }); assertTrue(listing.warnings.any { it.contains(id) })
    }
    @Test fun cancelledAcquisitionNeverCreatesACompleteCaseOrPendingPlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val vault = EvidenceVault(context)
        val before = vault.listCases().cases.map { it.getString("caseId") }.toSet(); var count = 0L; var failed = false
        try { vault.importSource(ByteArray(256 * 1024) { 83 }.inputStream(), "synthetic.bin", "Synthetic cancellation", "TEST", { count >= 65536 }, { count = it }) } catch (_: InterruptedException) { failed = true }
        assertTrue(failed); assertEquals(before, vault.listCases().cases.map { it.getString("caseId") }.toSet())
        assertFalse(java.io.File(context.filesDir, "EvidenceVault").walkTopDown().any { it.name.endsWith(".pending") })
    }
    @Test fun corruptedCiphertextFailsAndDoesNotLeakOpenDescriptors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = EvidenceVault(context)
        val m = vault.importSource("SYNTHETIC TEST DATA — NOT EVIDENCE".toByteArray().inputStream(), "synthetic.txt", "Synthetic tamper vector", "TEST")
        val caseId = m.getString("caseId"); val id = m.getJSONArray("artifacts").getJSONObject(0).getString("id")
        val file = java.io.File(context.filesDir, "EvidenceVault/cases/$caseId/artifacts/$id.enc")
        val ciphertext = file.readBytes(); ciphertext[0] = (ciphertext[0].toInt() xor 127).toByte(); file.writeBytes(ciphertext)
        val before = java.io.File("/proc/self/fd").list()?.size ?: 0
        repeat(30) { var failed = false; try { vault.open(caseId, id).use { it.readBytes() } } catch (_: Exception) { failed = true }; assertTrue("Tampered source must fail authentication", failed) }
        val after = java.io.File("/proc/self/fd").list()?.size ?: 0
        assertTrue("Descriptor count grew after rejected ciphertext", after <= before + 2)
    }
    @Test fun encryptedSourceAndExportRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = EvidenceVault(context); val bytes = "SYNTHETIC TEST DATA — NOT EVIDENCE".toByteArray()
        val manifest = vault.importSource(bytes.inputStream(), "synthetic.txt", "Synthetic instrumentation", "TEST_EXAMINER")
        val id = manifest.getJSONArray("artifacts").getJSONObject(0).getString("id")
        assertArrayEquals(bytes, vault.open(manifest.getString("caseId"), id).use { it.readBytes() })
        val reopened = EvidenceVault(context).listCases().cases.first { it.getString("caseId") == manifest.getString("caseId") }
        assertArrayEquals(EvidenceVault.canonical(manifest), EvidenceVault.canonical(reopened))
        assertTrue(vault.verify(manifest).contains("agree"))
        val output = ByteArrayOutputStream(); val packageHash = vault.exportPackage(manifest, output, "TEST_EXAMINER")
        assertEquals(EvidenceVault.sha(output.toByteArray()), packageHash)
        // AGP pulls additionalTestOutputDir before disposing the test installation.
        // Internal app cache cannot be assumed to remain accessible afterwards.
        val testOutput = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it).apply { mkdirs() } } ?: context.cacheDir
        val testPackage = java.io.File(testOutput, "synthetic-native.echocase"); testPackage.writeBytes(output.toByteArray())
        assertEquals(packageHash, EvidenceVault.sha(testPackage.readBytes()))
        val entries = mutableMapOf<String,ByteArray>(); ZipInputStream(output.toByteArray().inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() } }
        assertArrayEquals(bytes, entries["artifacts/$id"]); assertNotNull(entries["manifest.json"]); assertNotNull(entries["SHA256SUMS.txt"])
    }
}

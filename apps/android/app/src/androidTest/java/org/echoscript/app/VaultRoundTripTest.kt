package org.echoscript.app
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
@RunWith(AndroidJUnit4::class)
class VaultRoundTripTest {
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
        assertTrue(vault.verify(manifest).contains("agree"))
        val output = ByteArrayOutputStream(); vault.exportPackage(manifest, output, "TEST_EXAMINER")
        val entries = mutableMapOf<String,ByteArray>(); ZipInputStream(output.toByteArray().inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() } }
        assertArrayEquals(bytes, entries["artifacts/$id"]); assertNotNull(entries["manifest.json"]); assertNotNull(entries["SHA256SUMS.txt"])
    }
}

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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VaultRoundTripTest {
    @Test fun authenticatedDataSourceSupportsBoundedSeekAfterInitialization() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val vault = EvidenceVault(context)
        val bytes = "SYNTHETIC TEST DATA — NOT EVIDENCE".toByteArray()
        val m = vault.importSource(bytes.inputStream(), "synthetic.txt", "Synthetic seek", "TEST")
        val source = VaultDataSource(vault, m.getString("caseId"), m.getJSONArray("artifacts").getJSONObject(0).getString("id"))
        try {
            assertEquals(6L, source.open(androidx.media3.datasource.DataSpec.Builder().setUri("echoscript://synthetic").setPosition(3).setLength(6).build()))
            val read = ByteArray(20); assertEquals(6, source.read(read, 0, read.size)); assertArrayEquals(bytes.copyOfRange(3,9),read.copyOf(6))
            assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT,source.read(read,0,read.size))
        } finally { source.close() }
    }
    @Test fun controlledSpeechPlaysFromAuthenticatedEncryptedSourceToEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>(); val vault = EvidenceVault(context)
        val m = instrumentation.context.assets.open("synthetic-speech.wav").use { vault.importSource(it, "SYNTHETIC_TEST_NOT_EVIDENCE.wav", "Synthetic playback", "TEST") }
        val artifact = m.getJSONArray("artifacts").getJSONObject(0)
        assertEquals("9d17a3309b89246c14ecc5a7b9a0f9bf398de0d93824037ddaff4412b35af2bd", artifact.getString("sha256"))
        val ended = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val player = java.util.concurrent.atomic.AtomicReference<androidx.media3.exoplayer.ExoPlayer>()
        try {
            instrumentation.runOnMainSync {
                val p = androidx.media3.exoplayer.ExoPlayer.Builder(context).build(); player.set(p)
                p.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_ENDED) ended.countDown() }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failure.set(error.errorCodeName); ended.countDown() }
                })
                val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory { VaultDataSource(vault, m.getString("caseId"), artifact.getString("id")) }.createMediaSource(androidx.media3.common.MediaItem.fromUri("echoscript://synthetic-source"))
                p.setMediaSource(source); p.prepare(); p.play()
            }
            assertTrue("Synthetic encrypted playback timed out", ended.await(30, java.util.concurrent.TimeUnit.SECONDS)); assertNull("Synthetic playback failed", failure.get())
        } finally { instrumentation.runOnMainSync { player.get()?.release() } }
        assertTrue(vault.verify(m).contains("agree"))
    }
    @Test fun encryptedLegacyKeysetMigratesWithoutChangingEvidence() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val vault = EvidenceVault(context)
        val m = vault.importSource("SYNTHETIC TEST DATA — NOT EVIDENCE".toByteArray().inputStream(), "synthetic.txt", "Synthetic encrypted migration", "TEST")
        val file = java.io.File(context.filesDir, "EvidenceVault/streaming-keyset.v1.enc"); val original = file.readBytes()
        val prefs = context.getSharedPreferences("echoscript_keysets", Context.MODE_PRIVATE); val previous = prefs.getString("stream_keyset", null)
        val master = com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient().getAead("android-keystore://echoscript.vault.master.v1")
        val handle = com.google.crypto.tink.TinkProtoKeysetFormat.parseEncryptedKeyset(original, master, "org.echoscript.vault.keyset/1".toByteArray())
        val encryptedLegacy = com.google.crypto.tink.TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, master, byteArrayOf())
        try {
            check(prefs.edit().putString("stream_keyset", com.google.crypto.tink.subtle.Hex.encode(encryptedLegacy)).commit())
            check(file.delete()); val reopened = EvidenceVault(context)
            assertTrue(reopened.verify(m).contains("agree")); assertTrue(file.exists())
        } finally {
            file.writeBytes(original)
            val editor = prefs.edit(); if (previous == null) editor.remove("stream_keyset") else editor.putString("stream_keyset", previous); check(editor.commit())
        }
    }
    @Test fun tamperedEncryptedKeysetFailsWithoutReplacement() {
        val context = ApplicationProvider.getApplicationContext<Context>(); EvidenceVault(context)
        val file = java.io.File(context.filesDir, "EvidenceVault/streaming-keyset.v1.enc"); val original = file.readBytes()
        val changed = original.clone(); changed[10] = (changed[10].toInt() xor 1).toByte()
        try {
            file.writeBytes(changed); var failed = false
            try { EvidenceVault(context) } catch (_: Exception) { failed = true }
            assertTrue("Tampered keyset must fail closed", failed); assertArrayEquals(changed, file.readBytes())
        } finally { file.writeBytes(original) }
        EvidenceVault(context)
    }
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

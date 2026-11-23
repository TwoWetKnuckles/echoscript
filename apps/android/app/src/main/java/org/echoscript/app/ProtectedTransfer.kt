package org.echoscript.app

import org.echoscript.security.ProtectedCase
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

internal object ProtectedTransfer {
    fun export(vault: EvidenceVault, manifest: JSONObject, actor: String, destination: OutputStream,
               passphrase: CharArray, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        ProtectedCase.encrypt(DigestOutputStream(destination, digest), passphrase) { secured ->
            vault.exportPackage(manifest, secured, actor, cancelled)
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        vault.recordEvent(manifest, actor, "PROTECTED_TRANSFER_EXPORT", JSONObject().put("ciphertextSha256", hash)
            .put("format", "org.echoscript.protected-case/1").put("encryption", "Tink AES256_GCM_HKDF_1MB")
            .put("keyWrapping", "PBKDF2-HMAC-SHA256/600000 + AES256-GCM"))
        return hash
    }

    fun import(vault: EvidenceVault, source: InputStream, actor: String, expected: String?,
               passphrase: CharArray, cancelled: () -> Boolean, progress: (Long) -> Unit): JSONObject {
        require(expected == null || expected.matches(Regex("[a-f0-9]{64}"))) { "Enter a 64-character SHA-256" }
        val input = BufferedInputStream(source)
        input.mark(8)
        val prefix = ByteArray(8)
        var count = 0
        while (count < prefix.size) { val n = input.read(prefix, count, prefix.size - count); if (n < 0) break; count += n }
        input.reset()
        if (count != 8 || !ProtectedCase.looksProtected(prefix))
            return vault.importPackage(input, actor, expected, cancelled, progress)
        ProtectedCase.validatePassphrase(passphrase)
        var result: JSONObject? = null
        // Authenticate the independently supplied ciphertext hash before any case
        // is installed. Both the encrypted container and decoded ZIP stage stay
        // encrypted in app-private storage; no plaintext cache file is created.
        vault.withEncryptedStage(EvidenceVault.MAX_PACKAGE_BYTES + 2 * 1024 * 1024, { encrypted ->
            ProtectedCase.copy(input, encrypted, { cancelled() }, { progress(it) })
        }, { staged, cipherHash, _ ->
            check(expected == null || expected == cipherHash) { "Protected package differs from the independently supplied ciphertext hash" }
            ProtectedCase.decryptingStream(staged, passphrase).use { decoded ->
                result = vault.importPackage(decoded, actor, null, cancelled, progress)
            }
            vault.recordEvent(checkNotNull(result), actor, "PROTECTED_TRANSFER_IMPORT", JSONObject()
                .put("ciphertextSha256", cipherHash).put("trustedExternalHash", expected != null)
                .put("format", "org.echoscript.protected-case/1"))
        })
        return checkNotNull(result)
    }
}

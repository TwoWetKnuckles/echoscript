package org.echoscript.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.echoscript.security.SignedCheckpoint
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/** Device-key attestation of a snapshot, not a human examiner's legal signature. */
internal class DeviceCheckpoint(private val context: Context) {
    companion object {
        private const val ALIAS = "echoscript.checkpoint.p256.v1"
        private val LOCK = Any()
    }
    fun create(vault: EvidenceVault, manifest: JSONObject, actor: String, cancelled: () -> Boolean): JSONObject {
        vault.verify(manifest, cancelled)
        require(manifest.items("artifacts").count { it.getJSONObject("operation").getString("algorithm") == SignedCheckpoint.ALGORITHM } < SignedCheckpoint.MAX_CHECKPOINTS) {
            "This case has reached the signed-checkpoint limit"
        }
        val bytes = synchronized(LOCK) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val preferences = context.getSharedPreferences("echoscript_signer", Context.MODE_PRIVATE)
            val retained = preferences.getString("publicKeySha256", null)
            if (!store.containsAlias(ALIAS)) {
                check(retained == null) { "The checkpoint signing key is missing. Existing signatures remain verifiable; preserve the vault and independent checkpoints." }
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                generator.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
                generator.generateKeyPair()
            }
            val publicKey = checkNotNull(store.getCertificate(ALIAS)).publicKey
            val fingerprint = SignedCheckpoint.sha(publicKey.encoded)
            check(retained == null || retained == fingerprint) { "The device checkpoint identity changed unexpectedly" }
            if (retained == null) check(preferences.edit().putString("publicKeySha256", fingerprint).commit())
            val signingKey = store.getKey(ALIAS, null) as? PrivateKey ?: error("Checkpoint signing key is unavailable")
            if (cancelled()) throw InterruptedException("Checkpoint cancelled")
            SignedCheckpoint.sign(JSONObject(manifest.toString()), actor, EvidenceVault.utc(), signingKey, publicKey)
        }
        val verified = SignedCheckpoint.verify(bytes, manifest)
        val parents = listOfNotNull(manifest.getString("activeArtifact"), manifest.latestTranscript()?.getString("id")).distinct()
        return vault.addDerivative(manifest, actor, "REPORT", parents, "Device-signed case checkpoint.json", SignedCheckpoint.ALGORITHM, "1",
            warnings = listOf("Device-key signature. The actor label is locally supplied, not independently authenticated. Confirm the public-key fingerprint through a trusted channel. Later case events are outside this checkpoint."),
            maxBytes = SignedCheckpoint.MAX_BYTES.toLong()) { output ->
            output.write(bytes)
            JSONObject().put("keySha256", verified.keySha256).put("coveredArtifacts", verified.coveredArtifacts)
                .put("coveredAuditEntries", verified.coveredAuditEntries).put("hardwareBacking", "NOT_ASSERTED")
        }
    }
}

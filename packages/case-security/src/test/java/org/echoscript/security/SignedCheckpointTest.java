package org.echoscript.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

public class SignedCheckpointTest {
    private static KeyPair pair(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve)); return generator.generateKeyPair();
    }
    private static JSONObject snapshot() {
        return new JSONObject().put("format", "org.echoscript.case").put("schemaVersion", 1)
            .put("caseId", "synthetic-case").put("title", "SYNTHETIC TEST DATA — NOT EVIDENCE")
            .put("createdAtUtc", "2026-11-07T00:00:00Z").put("software", "synthetic-test")
            .put("activeArtifact", "synthetic-source")
            .put("artifacts", new JSONArray().put(new JSONObject().put("id", "synthetic-source").put("sha256", "a".repeat(64))))
            .put("audit", new JSONArray().put(new JSONObject().put("seq", 1).put("entryHash", "b".repeat(64))));
    }
    private static byte[] sign(JSONObject value, KeyPair key) throws Exception {
        return SignedCheckpoint.sign(value, "SYNTHETIC_EXAMINER", "2026-11-07T01:02:03Z", key.getPrivate(), key.getPublic());
    }
    @Test public void signatureCoversExactHistoricalPrefixAndDisclosesTail() throws Exception {
        JSONObject original = snapshot(); KeyPair key = pair("secp256r1"); byte[] signed = sign(original, key);
        JSONObject current = new JSONObject(original.toString());
        current.getJSONArray("artifacts").put(new JSONObject().put("id", "later-artifact"));
        current.getJSONArray("audit").put(new JSONObject().put("seq", 2));
        var result = SignedCheckpoint.verify(signed, current);
        assertEquals(SignedCheckpoint.sha(key.getPublic().getEncoded()), result.keySha256);
        assertEquals(1, result.coveredArtifacts); assertEquals(1, result.coveredAuditEntries);
        assertEquals(1, result.subsequentArtifacts); assertEquals(1, result.subsequentAuditEntries);
        current.getJSONArray("artifacts").getJSONObject(0).put("sha256", "c".repeat(64));
        assertThrows(Exception.class, () -> SignedCheckpoint.verify(signed, current));
    }
    @Test public void changedPayloadKeyAndNoncanonicalEnvelopeFail() throws Exception {
        JSONObject original = snapshot(); byte[] signed = sign(original, pair("secp256r1"));
        JSONObject changed = new JSONObject(new String(signed, java.nio.charset.StandardCharsets.UTF_8));
        changed.getJSONObject("payload").put("actor", "Forged actor");
        assertThrows(Exception.class, () -> SignedCheckpoint.verify(SignedCheckpoint.canonical(changed), original));
        JSONObject changedKey = new JSONObject(new String(signed, java.nio.charset.StandardCharsets.UTF_8));
        changedKey.put("publicKeySpki", java.util.Base64.getEncoder().encodeToString(pair("secp256r1").getPublic().getEncoded()));
        assertThrows(Exception.class, () -> SignedCheckpoint.verify(SignedCheckpoint.canonical(changedKey), original));
        byte[] whitespace = (" " + new String(signed, java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> SignedCheckpoint.verify(whitespace, original));
        JSONObject changedHeader = new JSONObject(original.toString()).put("title", "Changed signed title");
        assertThrows(Exception.class, () -> SignedCheckpoint.verify(signed, changedHeader));
    }
    @Test public void unsupportedCurveAndMismatchedPairFail() throws Exception {
        KeyPair wrongCurve = pair("secp384r1");
        assertThrows(Exception.class, () -> sign(snapshot(), wrongCurve));
        KeyPair first = pair("secp256r1"), second = pair("secp256r1");
        assertThrows(Exception.class, () -> SignedCheckpoint.sign(snapshot(), "TEST", "2026-11-07T00:00:00Z", first.getPrivate(), second.getPublic()));
        assertThrows(Exception.class, () -> SignedCheckpoint.sign(snapshot(), " ", "2026-11-07T00:00:00Z", first.getPrivate(), first.getPublic()));
    }
    @Test public void crossLanguageFixtureUsesTheSameCanonicalSnapshot() throws Exception {
        String directory = System.getenv("ECHOSCRIPT_INTEROP_DIR");
        Assume.assumeTrue("Optional cross-runtime fixture is required by the dedicated CI job", directory != null);
        Path root = Path.of(directory); JSONObject manifest = new JSONObject(Files.readString(root.resolve("snapshot.json")));
        KeyPair key = pair("secp256r1"); byte[] signed = sign(manifest, key);
        SignedCheckpoint.verify(signed, manifest);
        Files.write(root.resolve("jvm-checkpoint.json"), signed);
        Files.writeString(root.resolve("jvm-key-sha256.txt"), SignedCheckpoint.sha(key.getPublic().getEncoded()) + "\n");
    }
}

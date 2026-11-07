package org.echoscript.security;

import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.erdtman.jcs.JsonCanonicalizer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Sign a committed case snapshot; never treats an embedded public key as trusted identity. */
public final class SignedCheckpoint {
    public static final String ALGORITHM = "ecdsa-p256-case-checkpoint";
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_CHECKPOINTS = 128;
    private static final String SCHEMA = "org.echoscript.checkpoint/1";
    private SignedCheckpoint() {}

    public static byte[] canonical(JSONObject value) throws Exception {
        return new JsonCanonicalizer(value.toString()).getEncodedUTF8();
    }

    public static String sha(byte[] bytes) throws GeneralSecurityException {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text = new StringBuilder();
        for (byte b : hash) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    private static void keys(JSONObject value, String... expected) {
        Set<String> actual = new HashSet<>(); value.keys().forEachRemaining(actual::add);
        if (!actual.equals(new HashSet<>(Arrays.asList(expected))))
            throw new IllegalArgumentException("Unexpected signed-checkpoint fields");
    }

    private static ECPublicKey p256(PublicKey publicKey) throws GeneralSecurityException {
        if (!(publicKey instanceof ECPublicKey)) throw new GeneralSecurityException("Expected an EC public key");
        ECPublicKey key = (ECPublicKey) publicKey;
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
        ECParameterSpec actual = key.getParams();
        if (!actual.getCurve().equals(expected.getCurve()) || !actual.getGenerator().equals(expected.getGenerator())
                || !actual.getOrder().equals(expected.getOrder()) || actual.getCofactor() != expected.getCofactor())
            throw new GeneralSecurityException("Checkpoint requires the P-256 curve");
        return key;
    }

    public static byte[] sign(JSONObject snapshot, String actor, String timestamp,
            PrivateKey privateKey, PublicKey publicKey) throws Exception {
        p256(publicKey);
        if (actor.trim().isEmpty() || actor.length() > 128 || actor.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("Invalid checkpoint actor label");
        Instant.parse(timestamp);
        byte[] snapshotBytes = canonical(snapshot);
        if (snapshotBytes.length > 8 * 1024 * 1024) throw new IllegalArgumentException("Checkpoint snapshot exceeds policy");
        JSONObject payload = new JSONObject().put("algorithm", "ECDSA_P256_SHA256")
                .put("actor", actor).put("createdAtUtc", timestamp).put("caseId", snapshot.getString("caseId"))
                .put("keySha256", sha(publicKey.getEncoded())).put("snapshotSha256", sha(snapshotBytes))
                .put("snapshot", new JSONObject(new String(snapshotBytes, StandardCharsets.UTF_8)));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey); signature.update(canonical(payload));
        byte[] signed = signature.sign();
        Signature pairCheck = Signature.getInstance("SHA256withECDSA");
        pairCheck.initVerify(publicKey); pairCheck.update(canonical(payload));
        if (!pairCheck.verify(signed)) throw new GeneralSecurityException("Checkpoint key pair does not match");
        byte[] bytes = canonical(new JSONObject().put("schema", SCHEMA).put("payload", payload)
                .put("publicKeySpki", Base64.getEncoder().encodeToString(publicKey.getEncoded()))
                .put("signature", Base64.getEncoder().encodeToString(signed)));
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Checkpoint exceeds policy");
        return bytes;
    }

    private static byte[] decode(String value, int max) {
        if (value.length() < 1 || value.length() > max * 2) throw new IllegalArgumentException("Invalid checkpoint encoding");
        byte[] bytes = Base64.getDecoder().decode(value);
        if (bytes.length > max || !Base64.getEncoder().encodeToString(bytes).equals(value))
            throw new IllegalArgumentException("Noncanonical checkpoint encoding");
        return bytes;
    }

    public static final class Verification {
        public final String keySha256;
        public final int coveredArtifacts;
        public final int coveredAuditEntries;
        public final int subsequentArtifacts;
        public final int subsequentAuditEntries;
        private Verification(String key, int artifacts, int audit, int tailArtifacts, int tailAudit) {
            keySha256 = key; coveredArtifacts = artifacts; coveredAuditEntries = audit;
            subsequentArtifacts = tailArtifacts; subsequentAuditEntries = tailAudit;
        }
    }

    public static Verification verify(byte[] bytes, JSONObject current) throws Exception {
        if (bytes.length < 1 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Checkpoint size exceeds policy");
        JSONObject envelope = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!Arrays.equals(canonical(envelope), bytes)) throw new GeneralSecurityException("Checkpoint must be canonical JSON");
        keys(envelope, "schema", "payload", "publicKeySpki", "signature");
        if (!SCHEMA.equals(envelope.getString("schema"))) throw new GeneralSecurityException("Unsupported checkpoint schema");
        JSONObject payload = envelope.getJSONObject("payload");
        keys(payload, "algorithm", "actor", "createdAtUtc", "caseId", "keySha256", "snapshotSha256", "snapshot");
        if (!"ECDSA_P256_SHA256".equals(payload.getString("algorithm")))
            throw new GeneralSecurityException("Unsupported checkpoint signature algorithm");
        String actor = payload.getString("actor");
        if (actor.trim().isEmpty() || actor.length() > 128 || actor.chars().anyMatch(c -> c < 32))
            throw new GeneralSecurityException("Invalid checkpoint actor label");
        Instant.parse(payload.getString("createdAtUtc"));
        byte[] spki = decode(envelope.getString("publicKeySpki"), 1024);
        ECPublicKey publicKey = p256(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki)));
        String fingerprint = sha(spki);
        if (!fingerprint.equals(payload.getString("keySha256"))) throw new GeneralSecurityException("Checkpoint key fingerprint differs");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey); verifier.update(canonical(payload));
        if (!verifier.verify(decode(envelope.getString("signature"), 80)))
            throw new GeneralSecurityException("Checkpoint signature is invalid");
        JSONObject snapshot = payload.getJSONObject("snapshot");
        keys(snapshot, "format", "schemaVersion", "caseId", "title", "createdAtUtc", "software", "activeArtifact", "artifacts", "audit");
        byte[] snapshotBytes = canonical(snapshot);
        if (snapshotBytes.length > 8 * 1024 * 1024 || !sha(snapshotBytes).equals(payload.getString("snapshotSha256"))
                || !current.getString("caseId").equals(payload.getString("caseId")))
            throw new GeneralSecurityException("Checkpoint snapshot commitment differs");
        for (String field : new String[] {"format", "schemaVersion", "caseId", "title", "createdAtUtc", "software"}) {
            byte[] signed = canonical(new JSONObject().put("value", snapshot.get(field)));
            byte[] present = canonical(new JSONObject().put("value", current.get(field)));
            if (!Arrays.equals(signed, present)) throw new GeneralSecurityException("Signed case header differs");
        }
        int artifactCount = prefix(snapshot.getJSONArray("artifacts"), current.getJSONArray("artifacts"), 10000);
        int auditCount = prefix(snapshot.getJSONArray("audit"), current.getJSONArray("audit"), 100000);
        boolean activeFound = false;
        for (int i = 0; i < artifactCount; i++)
            activeFound |= snapshot.getJSONArray("artifacts").getJSONObject(i).getString("id").equals(snapshot.getString("activeArtifact"));
        if (!activeFound || artifactCount == 0 || auditCount == 0) throw new GeneralSecurityException("Invalid signed case snapshot");
        return new Verification(fingerprint, artifactCount, auditCount,
                current.getJSONArray("artifacts").length() - artifactCount,
                current.getJSONArray("audit").length() - auditCount);
    }

    private static int prefix(JSONArray signed, JSONArray current, int limit) throws Exception {
        if (signed.length() > limit || signed.length() > current.length())
            throw new GeneralSecurityException("Signed history is not a current prefix");
        for (int i = 0; i < signed.length(); i++)
            if (!Arrays.equals(canonical(signed.getJSONObject(i)), canonical(current.getJSONObject(i))))
                throw new GeneralSecurityException("Signed history or artifact commitment was modified");
        return signed.length();
    }
}

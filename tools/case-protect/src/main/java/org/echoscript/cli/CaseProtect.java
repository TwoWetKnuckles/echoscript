package org.echoscript.cli;

import java.io.Console;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.security.MessageDigest;
import java.util.Arrays;
import org.echoscript.security.ProtectedCase;
import org.json.JSONObject;

/** Explicit destinations only. Does not repair evidence or silently create plaintext copies. */
public final class CaseProtect {
    public static void main(String[] args) {
        char[] passphrase = null;
        Path pending = null;
        int status = 0;
        try {
            if (args.length == 2 && args[0].equals("serve")) { serve(Path.of(args[1])); return; }
            if (args.length != 3 || !(args[0].equals("encrypt") || args[0].equals("decrypt")))
                throw new IllegalArgumentException("Usage: case-protect encrypt|decrypt INPUT OUTPUT (decryption OUTPUT is plaintext; select protected storage)");
            Path input = Path.of(args[1]); Path output = Path.of(args[2]).toAbsolutePath();
            if (!Files.isRegularFile(input) || Files.size(input) > ProtectedCase.MAX_BYTES + 2 * 1024 * 1024)
                throw new IllegalArgumentException("Unsupported input size or path");
            if (Files.exists(output)) throw new IllegalArgumentException("Destination already exists; no file was replaced");
            Console console = System.console();
            if (console != null) passphrase = console.readPassword("Transfer passphrase (never saved): ");
            else {
                String value = System.getenv("ECHOSCRIPT_PACKAGE_PASSPHRASE");
                if (value == null) throw new IllegalArgumentException("A console or ECHOSCRIPT_PACKAGE_PASSPHRASE is required");
                passphrase = value.toCharArray();
            }
            ProtectedCase.validatePassphrase(passphrase);
            pending = Files.createTempFile(output.getParent(), ".echoscript-", ".pending");
            try (InputStream source = Files.newInputStream(input); OutputStream target = Files.newOutputStream(pending)) {
                if (args[0].equals("encrypt")) {
                    ProtectedCase.encrypt(target, passphrase, secured -> ProtectedCase.copy(source, secured, () -> false, n -> {}));
                } else {
                    try (InputStream decrypted = ProtectedCase.decryptingStream(source, passphrase)) {
                        ProtectedCase.copy(decrypted, target, () -> false, n -> {});
                    }
                }
            }
            // Same-directory hard-link creation is atomic and fails if OUTPUT now exists.
            // It cannot overwrite an examiner's pre-existing file in a racing process.
            Files.createLink(output, pending);
            Files.delete(pending); pending = null;
            System.err.println("Completed. Decrypted ZIPs require independent case verification and protected storage.");
        } catch (Exception failure) {
            System.err.println("Transfer failed; no completed destination was created. Check the passphrase, input integrity and destination storage.");
            status = 1;
        } finally {
            if (passphrase != null) Arrays.fill(passphrase, '\0');
            if (pending != null) try { Files.deleteIfExists(pending); } catch (Exception ignored) {}
        }
        if (status != 0) System.exit(status);
    }

    private static String hash(FileChannel file) throws Exception {
        file.position(0); MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer block = ByteBuffer.allocate(65536);
        while (true) {
            int n = file.read(block); if (n < 0) break;
            block.flip(); digest.update(block); block.clear();
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    private static String request(BufferedReader input) throws Exception {
        StringBuilder line = new StringBuilder();
        while (true) {
            int c = input.read();
            if (c < 0) { if (line.length() != 0) throw new IllegalArgumentException("Truncated request"); return null; }
            if (c == '\n') return line.toString();
            if (line.length() >= 512) throw new IllegalArgumentException("Oversized request");
            line.append((char) c);
        }
    }

    /** Bounded authenticated byte ranges consumed by the Node ZIP verifier. */
    private static void serve(Path input) throws Exception {
        String value = System.getenv("ECHOSCRIPT_PACKAGE_PASSPHRASE");
        if (value == null) throw new IllegalArgumentException("Passphrase required");
        char[] password = value.toCharArray();
        try {
            BasicFileAttributes before = Files.readAttributes(input, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.size() > ProtectedCase.MAX_CONTAINER_BYTES)
                throw new IllegalArgumentException("Unsupported protected input");
            try (FileChannel raw = FileChannel.open(input, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                String cipherHash = hash(raw);
                try (SeekableByteChannel clear = ProtectedCase.decryptingChannel(raw, password)) {
                    long size = clear.size();
                    System.out.println(new JSONObject().put("size", size).put("packageSha256", cipherHash));
                    BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
                    while (true) {
                        String line = request(commands);
                        if (line == null) throw new IllegalArgumentException("Verification ended without a final integrity check");
                        JSONObject command = new JSONObject(line);
                        if (command.optBoolean("finish", false)) {
                            BasicFileAttributes after = Files.readAttributes(input, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            if (!java.util.Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                                    || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !cipherHash.equals(hash(raw)))
                                throw new IllegalArgumentException("Input changed during verification");
                            System.out.println(new JSONObject().put("finished", true));
                            break;
                        }
                        long start = command.getLong("offset"); int length = command.getInt("length");
                        if (start < 0 || length < 0 || length > 65536 || start > size - length)
                            throw new IllegalArgumentException("Invalid verification byte range");
                        ByteBuffer bytes = ByteBuffer.allocate(length); clear.position(start);
                        while (bytes.hasRemaining()) if (clear.read(bytes) <= 0) throw new java.io.EOFException();
                        System.out.println(new JSONObject().put("base64", java.util.Base64.getEncoder().encodeToString(bytes.array())));
                    }
                }
            }
        } finally { Arrays.fill(password, '\0'); }
    }
}

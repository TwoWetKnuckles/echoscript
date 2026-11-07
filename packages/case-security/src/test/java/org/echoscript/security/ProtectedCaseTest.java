package org.echoscript.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtectedCaseTest {
    private static final char[] PASSWORD = "SYNTHETIC test transfer password 42".toCharArray();
    private static byte[] encrypt(byte[] plain) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ProtectedCase.encrypt(output, PASSWORD, stream -> stream.write(plain));
        return output.toByteArray();
    }
    @Test public void multipleSegmentsRoundTripAndRandomAccess() throws Exception {
        byte[] plain = new byte[2 * 1024 * 1024 + 931]; new Random(42).nextBytes(plain);
        byte[] encrypted = encrypt(plain);
        try (var input = ProtectedCase.decryptingStream(new ByteArrayInputStream(encrypted), PASSWORD)) {
            assertArrayEquals(plain, input.readAllBytes());
        }
        Path path = Files.createTempFile("synthetic-protected-", ".enc");
        try {
            Files.write(path, encrypted);
            try (SeekableByteChannel raw = Files.newByteChannel(path, StandardOpenOption.READ);
                 SeekableByteChannel clear = ProtectedCase.decryptingChannel(raw, PASSWORD)) {
                assertEquals(plain.length, clear.size());
                for (int offset : new int[] {plain.length - 33, 1_048_560, 0}) {
                    ByteBuffer bytes = ByteBuffer.allocate(33); clear.position(offset);
                    while (bytes.hasRemaining()) assertTrue(clear.read(bytes) > 0);
                    assertArrayEquals(Arrays.copyOfRange(plain, offset, offset + 33), bytes.array());
                }
            }
        } finally { Files.deleteIfExists(path); }
    }
    @Test public void randomizedEncryptionDoesNotCloseCallerDestination() throws Exception {
        class Destination extends ByteArrayOutputStream {
            boolean closed;
            @Override public void close() { closed = true; }
        }
        Destination out = new Destination();
        byte[] plain = "SYNTHETIC TEST DATA — NOT EVIDENCE".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ProtectedCase.encrypt(out, PASSWORD, stream -> { stream.write(plain); stream.close(); });
        assertFalse(out.closed);
        byte[] second = encrypt(plain); assertFalse(Arrays.equals(out.toByteArray(), second));
        try (var in = ProtectedCase.decryptingStream(new ByteArrayInputStream(out.toByteArray()), PASSWORD)) {
            assertArrayEquals(plain, in.readAllBytes());
        }
    }
    @Test public void wrongPasswordHeaderTagAndTruncationFail() throws Exception {
        byte[] encrypted = encrypt(new byte[4096]);
        assertThrows(Exception.class, () -> {
            try (var in = ProtectedCase.decryptingStream(new ByteArrayInputStream(encrypted), "WRONG synthetic password".toCharArray())) { in.readAllBytes(); }
        });
        for (int position : new int[] {0, 16, 50, encrypted.length - 1}) {
            byte[] changed = encrypted.clone(); changed[position] ^= 1;
            assertThrows(Exception.class, () -> {
                try (var in = ProtectedCase.decryptingStream(new ByteArrayInputStream(changed), PASSWORD)) { in.readAllBytes(); }
            });
        }
        for (int length : new int[] {0, 11, 47, encrypted.length - 1}) {
            byte[] changed = Arrays.copyOf(encrypted, length);
            assertThrows(Exception.class, () -> {
                try (var in = ProtectedCase.decryptingStream(new ByteArrayInputStream(changed), PASSWORD)) { in.readAllBytes(); }
            });
        }
    }
    @Test public void hostileKdfAndKeysetLengthsRejectedBeforeCostlyWork() throws Exception {
        for (int cost : new int[] {-1, 0, 599999, 2000001, Integer.MAX_VALUE}) {
            byte[] header = new byte[48]; System.arraycopy("ECHOCS01".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, header, 0, 8);
            ByteBuffer.wrap(header).putInt(8, cost).putInt(44, 1);
            assertThrows(IOException.class, () -> ProtectedCase.decryptingStream(new ByteArrayInputStream(header), PASSWORD));
        }
        for (int length : new int[] {-1, 0, 65537, Integer.MAX_VALUE}) {
            byte[] header = new byte[48]; System.arraycopy("ECHOCS01".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, header, 0, 8);
            ByteBuffer.wrap(header).putInt(8, ProtectedCase.ITERATIONS).putInt(44, length);
            assertThrows(IOException.class, () -> ProtectedCase.decryptingStream(new ByteArrayInputStream(header), PASSWORD));
        }
    }
    @Test public void cancellationAndProducerFailurePropagate() throws Exception {
        assertThrows(IOException.class, () -> ProtectedCase.copy(new ByteArrayInputStream(new byte[100]), new ByteArrayOutputStream(), () -> true, n -> {}));
        assertThrows(IOException.class, () -> ProtectedCase.encrypt(new ByteArrayOutputStream(), PASSWORD, out -> { out.write(1); throw new IOException("Synthetic interruption"); }));
        assertThrows(IllegalArgumentException.class, () -> ProtectedCase.validatePassphrase("short".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> ProtectedCase.validatePassphrase("                ".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> ProtectedCase.validatePassphrase("SYNTHETIC malformed \ud800".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> ProtectedCase.validatePassphrase("SYNTHETIC malformed \udc00".toCharArray()));
    }
    @Test public void unicodePassphraseRoundTripsWithoutSilentNormalization() throws Exception {
        char[] password = "SYNTHETIC test 密码 é 😀 42".toCharArray();
        byte[] plain = "SYNTHETIC TEST DATA — NOT EVIDENCE".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ProtectedCase.encrypt(output, password, stream -> stream.write(plain));
        try (var input = ProtectedCase.decryptingStream(new ByteArrayInputStream(output.toByteArray()), password)) {
            assertArrayEquals(plain, input.readAllBytes());
        }
        char[] different = java.text.Normalizer.normalize(new String(password), java.text.Normalizer.Form.NFD).toCharArray();
        assertThrows(Exception.class, () -> {
            try (var input = ProtectedCase.decryptingStream(new ByteArrayInputStream(output.toByteArray()), different)) { input.readAllBytes(); }
        });
    }
}

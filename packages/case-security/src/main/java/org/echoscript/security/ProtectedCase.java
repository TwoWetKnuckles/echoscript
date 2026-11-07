package org.echoscript.security;

import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.TinkProtoKeysetFormat;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates;
import com.google.crypto.tink.subtle.AesGcmJce;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Versioned container integrating platform PBKDF2 and maintained Tink AEADs. */
public final class ProtectedCase {
    private static final byte[] MAGIC = {'E', 'C', 'H', 'O', 'C', 'S', '0', '1'};
    public static final int ITERATIONS = 600_000;
    private static final int MAX_ITERATIONS = 2_000_000;
    private static final int SALT_BYTES = 32;
    private static final int MAX_KEYSET_BYTES = 65_536;
    public static final long MAX_BYTES = 16L * 1024 * 1024 * 1024;
    public static final long MAX_CONTAINER_BYTES = MAX_BYTES + 2 * 1024 * 1024;

    private ProtectedCase() {}

    @FunctionalInterface public interface Writer {
        void write(OutputStream output) throws Exception;
    }

    public static boolean looksProtected(byte[] prefix) {
        return Arrays.equals(prefix, MAGIC);
    }

    public static void validatePassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length < 16 || passphrase.length > 1024)
            throw new IllegalArgumentException("Use a transfer passphrase of 16 to 1024 characters");
        boolean nonSpace = false;
        for (char c : passphrase) nonSpace |= !Character.isWhitespace(c);
        if (!nonSpace) throw new IllegalArgumentException("The transfer passphrase cannot be blank");
    }

    private static AesGcmJce wrappingKey(char[] passphrase, byte[] salt, int iterations)
            throws GeneralSecurityException {
        validatePassphrase(passphrase);
        if (iterations < ITERATIONS || iterations > MAX_ITERATIONS || salt.length != SALT_BYTES)
            throw new GeneralSecurityException("Unsupported protected-case KDF policy");
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
        byte[] key = null;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new AesGcmJce(key);
        } finally {
            spec.clearPassword();
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] prefix(byte[] salt, int iterations) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC); out.writeInt(iterations); out.write(salt); out.flush();
        return bytes.toByteArray();
    }

    private static byte[] header(byte[] prefix, byte[] wrappedKeyset) throws IOException {
        if (wrappedKeyset.length < 1 || wrappedKeyset.length > MAX_KEYSET_BYTES)
            throw new IOException("Invalid protected-case keyset size");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(prefix); out.writeInt(wrappedKeyset.length); out.write(wrappedKeyset); out.flush();
        return bytes.toByteArray();
    }

    /** Never closes the caller's destination; Tink still finalizes its authenticated stream. */
    public static void encrypt(OutputStream destination, char[] passphrase, Writer writer)
            throws Exception {
        validatePassphrase(passphrase);
        StreamingAeadConfig.register();
        byte[] salt = new byte[SALT_BYTES]; new SecureRandom().nextBytes(salt);
        byte[] prefix = prefix(salt, ITERATIONS);
        KeysetHandle keyset = KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB);
        byte[] wrapped = TinkProtoKeysetFormat.serializeEncryptedKeyset(
                keyset, wrappingKey(passphrase, salt, ITERATIONS), prefix);
        byte[] header = header(prefix, wrapped);
        StreamingAead aead = keyset.getPrimitive(RegistryConfiguration.get(), StreamingAead.class);
        destination.write(header);
        try (OutputStream encrypted = aead.newEncryptingStream(nonClosing(destination), header)) {
            OutputStream bounded = new FilterOutputStream(encrypted) {
                private long count;
                private void add(int n) throws IOException {
                    if (n < 0 || count > MAX_BYTES - n) throw new IOException("Protected case exceeds 16 GiB");
                    count += n;
                }
                @Override public void write(int value) throws IOException { add(1); out.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    add(length); out.write(bytes, offset, length);
                }
                @Override public void close() throws IOException { flush(); }
            };
            writer.write(bounded);
        }
        destination.flush();
    }

    public static InputStream decryptingStream(InputStream source, char[] passphrase)
            throws IOException, GeneralSecurityException {
        Header parsed = readHeader(source, passphrase);
        InputStream decrypted = parsed.aead.newDecryptingStream(source, parsed.bytes);
        return bounded(decrypted);
    }

    private static final class Header {
        final byte[] bytes;
        final StreamingAead aead;
        Header(byte[] value, StreamingAead primitive) { bytes = value; aead = primitive; }
    }

    private static Header readHeader(InputStream source, char[] passphrase)
            throws IOException, GeneralSecurityException {
        DataInputStream data = new DataInputStream(source);
        byte[] magic = new byte[MAGIC.length]; data.readFully(magic);
        if (!looksProtected(magic)) throw new IOException("Unsupported protected-case header");
        int iterations = data.readInt();
        if (iterations < ITERATIONS || iterations > MAX_ITERATIONS)
            throw new IOException("Protected-case KDF cost exceeds the supported policy");
        byte[] salt = new byte[SALT_BYTES]; data.readFully(salt);
        int size = data.readInt();
        if (size < 1 || size > MAX_KEYSET_BYTES) throw new IOException("Invalid protected-case keyset size");
        byte[] wrapped = new byte[size]; data.readFully(wrapped);
        byte[] prefix = prefix(salt, iterations);
        byte[] header = header(prefix, wrapped);
        StreamingAeadConfig.register();
        KeysetHandle keyset = TinkProtoKeysetFormat.parseEncryptedKeyset(
                wrapped, wrappingKey(passphrase, salt, iterations), prefix);
        return new Header(header, keyset.getPrimitive(RegistryConfiguration.get(), StreamingAead.class));
    }

    /** Authenticated random access for the independent ZIP verifier, without plaintext staging. */
    public static SeekableByteChannel decryptingChannel(SeekableByteChannel source, char[] passphrase)
            throws IOException, GeneralSecurityException {
        source.position(0);
        Header parsed = readHeader(Channels.newInputStream(source), passphrase);
        long offset = source.position();
        SeekableByteChannel body = new SeekableByteChannel() {
            private long position;
            @Override public int read(ByteBuffer buffer) throws IOException {
                source.position(offset + position);
                int n = source.read(buffer); if (n > 0) position += n; return n;
            }
            @Override public int write(ByteBuffer buffer) throws IOException { throw new IOException("Read only"); }
            @Override public long position() { return position; }
            @Override public SeekableByteChannel position(long value) throws IOException {
                if (value < 0 || value > size()) throw new IOException("Invalid protected-file position");
                position = value; return this;
            }
            @Override public long size() throws IOException { return source.size() - offset; }
            @Override public SeekableByteChannel truncate(long size) throws IOException { throw new IOException("Read only"); }
            @Override public boolean isOpen() { return source.isOpen(); }
            @Override public void close() throws IOException { source.close(); }
        };
        SeekableByteChannel decrypted = parsed.aead.newSeekableDecryptingChannel(body, parsed.bytes);
        try {
            decrypted.read(ByteBuffer.allocate(1));
            if (decrypted.size() > MAX_BYTES) throw new IOException("Protected case exceeds 16 GiB");
            decrypted.position(0); return decrypted;
        } catch (IOException | RuntimeException failure) { decrypted.close(); throw failure; }
    }

    private static InputStream bounded(InputStream decrypted) {
        return new FilterInputStream(decrypted) {
            private long count;
            private void add(int n) throws IOException {
                if (n > 0) {
                    if (count > MAX_BYTES - n) throw new IOException("Protected case exceeds 16 GiB");
                    count += n;
                }
            }
            @Override public int read() throws IOException { int value = in.read(); if (value >= 0) add(1); return value; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int n = in.read(bytes, offset, length); add(n); return n;
            }
            @Override public long skip(long length) throws IOException {
                if (length <= 0) return 0;
                byte[] bytes = new byte[(int) Math.min(65536, length)]; long n = 0;
                while (n < length) {
                    int read = read(bytes, 0, (int) Math.min(bytes.length, length - n));
                    if (read < 0) break;
                    n += read;
                }
                return n;
            }
        };
    }

    public static void copy(InputStream input, OutputStream output, BooleanSupplier cancelled,
            LongConsumer progress) throws IOException {
        byte[] buffer = new byte[65536]; long count = 0;
        while (true) {
            if (cancelled.getAsBoolean()) throw new IOException("Protected transfer cancelled");
            int n = input.read(buffer);
            if (n < 0) break;
            if (count > MAX_CONTAINER_BYTES - n) throw new IOException("Protected case exceeds its container limit");
            output.write(buffer, 0, n); count += n; progress.accept(count);
        }
        if (cancelled.getAsBoolean()) throw new IOException("Protected transfer cancelled");
    }

    private static OutputStream nonClosing(OutputStream destination) {
        return new FilterOutputStream(destination) {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
            }
            @Override public void close() throws IOException { flush(); }
        };
    }
}

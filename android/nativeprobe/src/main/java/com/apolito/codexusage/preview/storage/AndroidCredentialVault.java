package com.apolito.codexusage.preview.storage;

import android.content.Context;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Synthetic-only credential vault for the offline native lab. No account login uses this class. */
public final class AndroidCredentialVault {
    public static final int UNAVAILABLE = 1;
    public static final int CORRUPT = 2;
    public static final int KEY_MISSING = 3;
    public static final int WRITE_FAILED = 4;
    public static final int INVALID_INPUT = 5;
    public static final int COMMIT_UNCERTAIN = 6;
    public static final int CRYPTO_FAILED = 7;

    private static final int MAGIC = 0x43445856; // CDXV
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int MAX_SECRET_BYTES = 65_536;
    private static final int MAX_RECORD_BYTES = 4 + 1 + 1 + 4 + IV_BYTES
            + MAX_SECRET_BYTES + TAG_BYTES;
    private static final Object PROCESS_LOCK = new Object();

    private final Path directory;
    private final String namespace;
    private final String keyAlias;
    private final KeyStore keyStore;
    private boolean fixtureUnavailable;
    private boolean fixtureFailNextWrite;

    /** Fixed diagnostic namespaces prevent fixture operations from reaching a future account store. */
    public AndroidCredentialVault(Context context, String fixtureNamespace) throws VaultException {
        requireWorkerThread();
        if (context == null || fixtureNamespace == null
                || !fixtureNamespace.matches("synthetic-[a-z0-9-]{1,32}")) {
            throw failure(INVALID_INPUT);
        }
        Context app = context.getApplicationContext();
        if (app == null) throw failure(INVALID_INPUT);
        namespace = fixtureNamespace;
        keyAlias = "codex.native.lab.v1." + namespace;
        try {
            File noBackup = app.getNoBackupFilesDir();
            if (noBackup == null) throw failure(UNAVAILABLE);
            directory = noBackup.toPath().resolve("native-storage-fixtures")
                    .resolve(namespace).resolve("vault");
            Files.createDirectories(directory);
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
        } catch (VaultException e) {
            throw e;
        } catch (Exception e) {
            throw failure(UNAVAILABLE);
        }
    }

    /** A null result means only that this exact record does not exist. */
    public byte[] get(String identity) throws VaultException {
        byte[] identityBytes = identityBytes(identity);
        return locked(false, () -> {
            Path path = recordPath(identityBytes);
            if (!recordExists(path)) return null;
            SecretKey key = existingKey();
            if (key == null) throw failure(KEY_MISSING);
            try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
                if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION
                        || input.readUnsignedByte() != IV_BYTES) throw failure(CORRUPT);
                int length = input.readInt();
                if (length < TAG_BYTES || length > MAX_SECRET_BYTES + TAG_BYTES) {
                    throw failure(CORRUPT);
                }
                byte[] iv = new byte[IV_BYTES];
                byte[] encrypted = new byte[length];
                input.readFully(iv);
                input.readFully(encrypted);
                if (input.read() != -1) throw failure(CORRUPT);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, iv));
                cipher.updateAAD(aad(identityBytes));
                return cipher.doFinal(encrypted);
            } catch (java.io.EOFException | BadPaddingException | IllegalBlockSizeException e) {
                throw failure(CORRUPT);
            }
        });
    }

    public void put(String identity, byte[] secret) throws VaultException {
        byte[] identityBytes = identityBytes(identity);
        if (secret == null || secret.length > MAX_SECRET_BYTES) throw failure(INVALID_INPUT);
        locked(true, () -> {
            SecretKey key = existingKey();
            if (key == null) {
                // A lost key must never be silently replaced over surviving ciphertext.
                if (hasRecordsOrPendingWrites()) throw failure(KEY_MISSING);
                KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .setUserAuthenticationRequired(false)
                        .build());
                key = generator.generateKey();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key); // Provider generates a fresh IV.
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) throw failure(UNAVAILABLE);
            cipher.updateAAD(aad(identityBytes));
            byte[] encrypted = cipher.doFinal(secret);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                output.writeByte(IV_BYTES);
                output.writeInt(encrypted.length);
                output.write(iv);
                output.write(encrypted);
            }
            Path destination = recordPath(identityBytes);
            Path pending = directory.resolve(destination.getFileName() + ".pending");
            boolean committed = false;
            try {
                try (FileOutputStream output = new FileOutputStream(pending.toFile())) {
                    output.write(bytes.toByteArray()); // Ciphertext only, including temporary file.
                    output.getFD().sync();
                }
                if (fixtureFailNextWrite) {
                    fixtureFailNextWrite = false;
                    throw failure(WRITE_FAILED);
                }
                Os.rename(pending.toString(), destination.toString());
                committed = true;
                try { syncDirectory(); }
                catch (Exception e) { throw failure(COMMIT_UNCERTAIN); }
            } finally {
                if (!committed) Files.deleteIfExists(pending);
            }
            return null;
        });
    }

    public boolean delete(String identity) throws VaultException {
        byte[] identityBytes = identityBytes(identity);
        return locked(true, () -> {
            Path record = recordPath(identityBytes);
            // Deletion deliberately remains possible after ciphertext or key damage.
            boolean removed = Files.deleteIfExists(record);
            try {
                Files.deleteIfExists(directory.resolve(record.getFileName() + ".pending"));
                if (removed) syncDirectory();
            } catch (Exception e) {
                throw failure(removed ? COMMIT_UNCERTAIN : WRITE_FAILED);
            }
            return removed;
        });
    }

    private SecretKey existingKey() throws Exception {
        Key key = keyStore.getKey(keyAlias, null);
        if (key == null) return null;
        if (!(key instanceof SecretKey)) throw failure(CORRUPT);
        return (SecretKey) key;
    }

    private byte[] aad(byte[] identity) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            byte[] namespaceBytes = namespace.getBytes(StandardCharsets.UTF_8);
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeInt(namespaceBytes.length);
            output.write(namespaceBytes);
            output.writeInt(identity.length);
            output.write(identity);
        }
        return bytes.toByteArray();
    }

    private static byte[] identityBytes(String identity) throws VaultException {
        if (identity == null || identity.isEmpty()) throw failure(INVALID_INPUT);
        byte[] bytes = identity.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) throw failure(INVALID_INPUT);
        return bytes;
    }

    private Path recordPath(byte[] identity) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity);
        StringBuilder name = new StringBuilder(68);
        for (byte value : digest) {
            name.append(Character.forDigit((value >>> 4) & 15, 16));
            name.append(Character.forDigit(value & 15, 16));
        }
        return directory.resolve(name + ".bin");
    }

    private boolean recordExists(Path path) throws IOException, VaultException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MAX_RECORD_BYTES) {
                throw failure(CORRUPT);
            }
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    private boolean hasRecordsOrPendingWrites() throws IOException {
        try (java.nio.file.DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) if (!path.getFileName().toString().equals("vault.lock")) return true;
        }
        return false;
    }

    private void syncDirectory() throws Exception {
        FileDescriptor descriptor = Os.open(directory.toString(), OsConstants.O_RDONLY, 0);
        try {
            Os.fsync(descriptor);
        } finally {
            Os.close(descriptor);
        }
    }

    private <T> T locked(boolean writing, Operation<T> operation) throws VaultException {
        requireWorkerThread();
        synchronized (PROCESS_LOCK) {
            if (fixtureUnavailable) throw failure(UNAVAILABLE);
            try (FileChannel channel = FileChannel.open(directory.resolve("vault.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                if (!ignored.isValid()) throw failure(UNAVAILABLE);
                return operation.run();
            } catch (VaultException e) {
                throw e;
            } catch (UserNotAuthenticatedException | java.security.KeyStoreException e) {
                throw failure(UNAVAILABLE);
            } catch (GeneralSecurityException e) {
                throw failure(CRYPTO_FAILED);
            } catch (Exception e) {
                throw failure(writing ? WRITE_FAILED : UNAVAILABLE);
            }
        }
    }

    private static void requireWorkerThread() throws VaultException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw failure(UNAVAILABLE);
    }

    private interface Operation<T> { T run() throws Exception; }

    /** Sanitized code/message only: never retains a platform cause containing credential data. */
    public static final class VaultException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int code;
        private VaultException(int code, String message) { super(message); this.code = code; }
        public int getCode() { return code; }
    }

    private static VaultException failure(int code) {
        String message;
        switch (code) {
            case CORRUPT: message = "Encrypted fixture record is invalid"; break;
            case KEY_MISSING: message = "Fixture encryption key is missing"; break;
            case WRITE_FAILED: message = "Fixture storage write failed"; break;
            case INVALID_INPUT: message = "Invalid fixture storage input"; break;
            case COMMIT_UNCERTAIN: message = "Fixture change committed but durability is uncertain"; break;
            case CRYPTO_FAILED: message = "Fixture cryptographic operation failed"; break;
            default: message = "Fixture secure storage is unavailable";
        }
        return new VaultException(code, message);
    }

    // Package-private fault controls are exposed only by the separate synthetic diagnostics class.
    void fixtureFailNextWrite() {
        synchronized (PROCESS_LOCK) { fixtureFailNextWrite = true; }
    }

    void fixtureSetUnavailable(boolean unavailable) {
        synchronized (PROCESS_LOCK) { fixtureUnavailable = unavailable; }
    }

    void fixtureTamperOnlyRecord() throws VaultException {
        locked(true, () -> {
            Path selected = null;
            try (java.nio.file.DirectoryStream<Path> paths = Files.newDirectoryStream(directory, "*.bin")) {
                for (Path path : paths) {
                    if (selected != null) throw failure(INVALID_INPUT);
                    selected = path;
                }
            }
            if (selected == null || !recordExists(selected)) throw failure(INVALID_INPUT);
            byte[] record = Files.readAllBytes(selected);
            if (record.length == 0) throw failure(INVALID_INPUT);
            record[record.length - 1] ^= 1;
            Files.write(selected, record);
            return null;
        });
    }

    void fixtureRemoveKey() throws VaultException {
        locked(true, () -> { keyStore.deleteEntry(keyAlias); return null; });
    }

    boolean fixtureKeyNonExportable() throws VaultException {
        return locked(false, () -> {
            SecretKey key = existingKey();
            return key != null && key.getEncoded() == null;
        });
    }

    void fixtureClear() throws VaultException {
        fixtureSetUnavailable(false);
        locked(true, () -> {
            try (java.nio.file.DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
                for (Path path : paths) {
                    if (!path.getFileName().toString().equals("vault.lock")) Files.delete(path);
                }
            }
            keyStore.deleteEntry(keyAlias);
            fixtureFailNextWrite = false;
            syncDirectory();
            return null;
        });
    }

    boolean fixtureHasPlaintextMarker(byte[] marker) throws VaultException {
        if (marker == null || marker.length == 0) throw failure(INVALID_INPUT);
        return locked(false, () -> {
            try (java.nio.file.DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
                for (Path path : paths) {
                    if (path.getFileName().toString().equals("vault.lock")) continue;
                    if (!recordExists(path)) continue;
                    byte[] bytes = Files.readAllBytes(path);
                    for (int start = 0; start <= bytes.length - marker.length; start++) {
                        int matched = 0;
                        while (matched < marker.length && bytes[start + matched] == marker[matched]) matched++;
                        if (matched == marker.length) return true;
                    }
                }
            }
            return false;
        });
    }
}

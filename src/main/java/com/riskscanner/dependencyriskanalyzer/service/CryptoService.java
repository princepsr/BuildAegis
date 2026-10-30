package com.riskscanner.dependencyriskanalyzer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Small utility service for encrypting/decrypting sensitive values (currently the AI API key).
 *
 * <p>Encryption details:
 * <ul>
 *   <li>AES-GCM with random IV per ciphertext</li>
 *   <li>Key material derived from {@code buildaegis.encryption.secret} using SHA-256</li>
 * </ul>
 *
 * <p>Operational notes:
 * <ul>
 *   <li>If {@code buildaegis.encryption.secret} is blank, a random key is generated once and kept in the file
 *   {@code buildaegis.encryption.key-file} (default {@code ./data/buildaegis.key}), readable only by the current user
 *   where the platform allows it. Keep that file with the database: without it, stored keys cannot be decrypted.</li>
 *   <li>Only if that file cannot be created are values stored in plaintext (a warning is logged).</li>
 *   <li>If values were stored encrypted and the secret/key file is later removed/changed, decryption will fail.</li>
 *   <li>Values stored in plaintext by older versions stay readable (each stored value records whether it is encrypted).</li>
 * </ul>
 */
@Service
public class CryptoService {

    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    @Value("${buildaegis.encryption.secret:}")
    private String encryptionSecret;

    @Value("${buildaegis.encryption.key-file:./data/buildaegis.key}")
    private String keyFile;

    /** Resolved lazily: the configured secret, or the generated key from the key file; null if neither is available. */
    private volatile String effectiveSecret;
    private volatile boolean secretResolved;

    public boolean isEncryptionConfigured() {
        return secret() != null;
    }

    private String secret() {
        if (!secretResolved) {
            synchronized (this) {
                if (!secretResolved) {
                    effectiveSecret = resolveSecret();
                    secretResolved = true;
                }
            }
        }
        return effectiveSecret;
    }

    private String resolveSecret() {
        if (encryptionSecret != null && !encryptionSecret.isBlank()) {
            return encryptionSecret;
        }
        if (keyFile == null || keyFile.isBlank()) {
            return null;
        }
        try {
            Path path = Paths.get(keyFile);
            if (Files.isRegularFile(path)) {
                String existing = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            byte[] random = new byte[32];
            secureRandom.nextBytes(random);
            String generated = Base64.getEncoder().encodeToString(random);

            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, generated, StandardCharsets.UTF_8);
            restrictToOwner(path);
            log.info("Generated a local encryption key file at {}. Keep it together with the database.", path.toAbsolutePath());
            return generated;
        } catch (Exception e) {
            log.warn("Could not read or create the encryption key file '{}'; API keys will NOT be encrypted: {}", keyFile, e.toString());
            return null;
        }
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // Not a POSIX file system (Windows): fall back to the basic owner-only flags
            java.io.File f = path.toFile();
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
        }
    }

    public String encrypt(String plaintext) {
        if (!isEncryptionConfigured()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKeyFromSecret(secret()), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt value", e);
        }
    }

    public String decrypt(String ciphertextOrPlaintext, boolean encrypted) {
        if (!encrypted) {
            return ciphertextOrPlaintext;
        }
        if (!isEncryptionConfigured()) {
            throw new IllegalStateException("Encryption secret is not configured");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertextOrPlaintext);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKeyFromSecret(secret()), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt value", e);
        }
    }

    private SecretKey secretKeyFromSecret(String secret) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha256.digest(secret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    }
}

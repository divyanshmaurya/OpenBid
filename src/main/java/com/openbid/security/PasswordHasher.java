package com.openbid.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 with a random per-user salt and an application-level pepper.
 * The pepper is read from {@code OPENBID_PEPPER}; a documented development default
 * is used only when the variable is unset (never store that default in production).
 */
public final class PasswordHasher {

    public static final int ITERATIONS = 210_000;
    public static final int SALT_BYTES = 16;
    public static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static byte[] randomSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return salt;
    }

    public static byte[] hash(char[] password, byte[] salt) {
        char[] peppered = pepperedPassword(password);
        PBEKeySpec spec = new PBEKeySpec(peppered, salt, ITERATIONS, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 is required", e);
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(peppered, '\0');
        }
    }

    public static boolean verify(char[] password, byte[] salt, byte[] expectedHash) {
        byte[] actual = hash(password, salt);
        return MessageDigest.isEqual(actual, expectedHash);
    }

    static byte[] pepperBytes() {
        String pepper = System.getenv("OPENBID_PEPPER");
        if (pepper == null || pepper.isBlank()) {
            pepper = "openbid-dev-pepper-change-me";
        }
        return pepper.getBytes(StandardCharsets.UTF_8);
    }

    private static char[] pepperedPassword(char[] password) {
        byte[] pepper = pepperBytes();
        char[] pepperChars = new String(pepper, StandardCharsets.UTF_8).toCharArray();
        char[] out = new char[password.length + pepperChars.length];
        System.arraycopy(password, 0, out, 0, password.length);
        System.arraycopy(pepperChars, 0, out, password.length, pepperChars.length);
        java.util.Arrays.fill(pepperChars, '\0');
        return out;
    }
}

package com.openbid.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    @Test
    void hashAndVerifyRoundTrip() {
        char[] password = "correct-horse".toCharArray();
        byte[] salt = PasswordHasher.randomSalt();
        byte[] hash = PasswordHasher.hash(password, salt);
        assertTrue(PasswordHasher.verify("correct-horse".toCharArray(), salt, hash));
    }

    @Test
    void wrongPasswordIsRejected() {
        byte[] salt = PasswordHasher.randomSalt();
        byte[] hash = PasswordHasher.hash("letmein".toCharArray(), salt);
        assertFalse(PasswordHasher.verify("letmeout".toCharArray(), salt, hash));
    }
}

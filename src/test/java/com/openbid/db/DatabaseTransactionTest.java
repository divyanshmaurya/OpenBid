package com.openbid.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.openbid.security.PasswordHasher;

class DatabaseTransactionTest {

    private Database db;
    private UserDao users;
    private byte[] salt;
    private byte[] hash;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.openInMemory();
        users = new UserDao(db);
        salt = PasswordHasher.randomSalt();
        hash = PasswordHasher.hash("secret12".toCharArray(), salt);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    void forcedFailureAfterFirstWriteRollsBack() throws Exception {
        assertThrows(SQLException.class, () -> db.inTransaction(() -> {
            users.insert("alice", salt, hash);
            throw new SQLException("forced");
        }));
        assertNull(users.findByUsername("alice"));
        assertEquals(0, users.count());
    }

    @Test
    void uniqueViolationRollsBackEarlierWrite() throws Exception {
        users.insert("taken", salt, hash);
        assertThrows(SQLException.class, () -> db.inTransaction(() -> {
            users.insert("newcomer", salt, hash);
            users.insert("taken", salt, hash);
            return null;
        }));
        assertNull(users.findByUsername("newcomer"));
        assertEquals(1, users.count());
    }
}

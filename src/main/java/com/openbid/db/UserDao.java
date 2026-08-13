package com.openbid.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class UserDao {

    private final Database db;

    public UserDao(Database db) {
        this.db = db;
    }

    public long insert(String username, byte[] salt, byte[] passwordHash) throws SQLException {
        String sql = "INSERT INTO users (username, salt, password_hash, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setBytes(2, salt);
            ps.setBytes(3, passwordHash);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("User insert did not return an id");
                }
                return keys.getLong(1);
            }
        }
    }

    public void updatePassword(long id, byte[] salt, byte[] passwordHash) throws SQLException {
        String sql = "UPDATE users SET salt = ?, password_hash = ? WHERE id = ?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setBytes(1, salt);
            ps.setBytes(2, passwordHash);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public UserRecord findByUsername(String username) throws SQLException {
        String sql = "SELECT id, username, salt, password_hash, created_at FROM users WHERE username = ?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public UserRecord findById(long id) throws SQLException {
        String sql = "SELECT id, username, salt, password_hash, created_at FROM users WHERE id = ?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public int count() throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT COUNT(*) FROM users");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static UserRecord map(ResultSet rs) throws SQLException {
        return new UserRecord(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getBytes("salt"),
                rs.getBytes("password_hash"),
                rs.getLong("created_at")
        );
    }
}

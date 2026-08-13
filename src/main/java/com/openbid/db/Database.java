package com.openbid.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the single SQLite connection and the transaction boundary.
 * SQLite allows one writer at a time; {@link com.openbid.server.BidManager}
 * already serializes mutations, and this class is synchronized as a second guard.
 */
public final class Database implements AutoCloseable {

    private final Connection connection;
    private final Path path;

    private Database(Connection connection, Path path) {
        this.connection = connection;
        this.path = path;
    }

    public static Database open(Path file) throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent() == null
                    ? Path.of(".")
                    : file.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new SQLException("Cannot create database directory", e);
        }
        return openUrl("jdbc:sqlite:" + file.toAbsolutePath(), file);
    }

    public static Database openInMemory() throws SQLException {
        return openUrl("jdbc:sqlite::memory:", null);
    }

    private static Database openUrl(String url, Path path) throws SQLException {
        Connection conn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
            if (path != null) {
                st.execute("PRAGMA journal_mode = WAL");
            }
        }
        Database db = new Database(conn, path);
        db.applySchema();
        db.migrate();
        return db;
    }

    public Path path() {
        return path;
    }

    public synchronized Connection connection() {
        return connection;
    }

    /**
     * Auto-commit off, run the work, commit — or roll back on any exception and rethrow.
     * Nested calls on the same thread join the outer transaction (no extra commit).
     */
    public synchronized <T> T inTransaction(SqlWork<T> work) throws Exception {
        boolean outermost = connection.getAutoCommit();
        if (outermost) {
            connection.setAutoCommit(false);
        }
        try {
            T result = work.run();
            if (outermost) {
                connection.commit();
            }
            return result;
        } catch (Exception e) {
            if (outermost) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // original exception is the one that matters
                }
            }
            throw e;
        } finally {
            if (outermost) {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // connection may already be closed in tests
                }
            }
        }
    }

    private void applySchema() throws SQLException {
        String sql;
        try (InputStream in = Database.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new SQLException("schema.sql missing from classpath");
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("Failed to read schema.sql", e);
        }
        for (String raw : sql.split(";")) {
            String stmt = stripComments(raw).trim();
            if (stmt.isEmpty()) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.execute(stmt);
            }
        }
    }

    private void migrate() {
        addColumn("auctions", "category", "TEXT NOT NULL DEFAULT 'Other'");
        addColumn("auctions", "reserve_cents", "INTEGER NOT NULL DEFAULT 0");
        addColumn("auctions", "buy_now_cents", "INTEGER NOT NULL DEFAULT 0");
        addColumn("auctions", "snipe_extended", "INTEGER NOT NULL DEFAULT 0");
        addColumn("auctions", "original_end_time", "INTEGER NOT NULL DEFAULT 0");
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    UPDATE auctions SET original_end_time = end_time
                    WHERE original_end_time = 0 OR original_end_time IS NULL
                    """);
        } catch (SQLException ignored) {
            // fresh databases already have the column populated
        }
    }

    private void addColumn(String table, String column, String spec) {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + spec);
        } catch (SQLException ignored) {
            // column already exists
        }
    }

    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n")) {
            String t = line.trim();
            if (t.startsWith("--")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    @Override
    public synchronized void close() throws SQLException {
        if (!connection.isClosed()) {
            connection.close();
        }
    }
}

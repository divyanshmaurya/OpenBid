package com.openbid.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class SaleDao {

    private final Database db;

    public SaleDao(Database db) {
        this.db = db;
    }

    public SaleRecord insert(long auctionId, long buyerId, long amountCents, long createdAt) throws SQLException {
        String sql = "INSERT INTO sales (auction_id, buyer_id, amount_cents, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, auctionId);
            ps.setLong(2, buyerId);
            ps.setLong(3, amountCents);
            ps.setLong(4, createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Sale insert did not return an id");
                }
                return new SaleRecord(keys.getLong(1), auctionId, buyerId, amountCents, createdAt);
            }
        }
    }

    public SaleRecord findByAuction(long auctionId) throws SQLException {
        String sql = "SELECT id, auction_id, buyer_id, amount_cents, created_at FROM sales WHERE auction_id = ?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new SaleRecord(
                        rs.getLong("id"),
                        rs.getLong("auction_id"),
                        rs.getLong("buyer_id"),
                        rs.getLong("amount_cents"),
                        rs.getLong("created_at")
                );
            }
        }
    }

    public int count() throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT COUNT(*) FROM sales");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

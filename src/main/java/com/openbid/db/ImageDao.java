package com.openbid.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class ImageDao {

    private final Database db;

    public ImageDao(Database db) {
        this.db = db;
    }

    public void upsert(long auctionId, byte[] jpeg) throws SQLException {
        if (jpeg == null || jpeg.length == 0) {
            return;
        }
        String sql = """
                INSERT INTO auction_images (auction_id, jpeg) VALUES (?, ?)
                ON CONFLICT (auction_id) DO UPDATE SET jpeg = excluded.jpeg
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setBytes(2, jpeg);
            ps.executeUpdate();
        }
    }

    public byte[] find(long auctionId) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT jpeg FROM auction_images WHERE auction_id = ?")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }
}

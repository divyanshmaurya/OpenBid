package com.openbid.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class ProxyBidDao {

    private final Database db;

    public ProxyBidDao(Database db) {
        this.db = db;
    }

    public void upsert(long auctionId, long bidderId, long maxCents, long createdAt) throws SQLException {
        String sql = """
                INSERT INTO proxy_bids (auction_id, bidder_id, max_cents, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (auction_id, bidder_id)
                DO UPDATE SET max_cents = excluded.max_cents
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setLong(2, bidderId);
            ps.setLong(3, maxCents);
            ps.setLong(4, createdAt);
            ps.executeUpdate();
        }
    }

    public List<ProxyBid> listByAuction(long auctionId) throws SQLException {
        String sql = """
                SELECT auction_id, bidder_id, max_cents, created_at
                FROM proxy_bids WHERE auction_id = ?
                ORDER BY created_at ASC
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ProxyBid> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ProxyBid(
                            rs.getLong("auction_id"),
                            rs.getLong("bidder_id"),
                            rs.getLong("max_cents"),
                            rs.getLong("created_at")
                    ));
                }
                return out;
            }
        }
    }

    public ProxyBid find(long auctionId, long bidderId) throws SQLException {
        String sql = """
                SELECT auction_id, bidder_id, max_cents, created_at
                FROM proxy_bids WHERE auction_id = ? AND bidder_id = ?
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setLong(2, bidderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ProxyBid(
                        rs.getLong("auction_id"),
                        rs.getLong("bidder_id"),
                        rs.getLong("max_cents"),
                        rs.getLong("created_at")
                );
            }
        }
    }
}

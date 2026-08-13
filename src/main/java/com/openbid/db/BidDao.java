package com.openbid.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.openbid.shared.BidInfo;

public final class BidDao {

    private final Database db;

    public BidDao(Database db) {
        this.db = db;
    }

    public BidInfo insert(long auctionId, long bidderId, long amountCents, boolean proxy, long createdAt)
            throws SQLException {
        String sql = """
                INSERT INTO bids (auction_id, bidder_id, amount_cents, is_proxy, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, auctionId);
            ps.setLong(2, bidderId);
            ps.setLong(3, amountCents);
            ps.setInt(4, proxy ? 1 : 0);
            ps.setLong(5, createdAt);
            ps.executeUpdate();
            long id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Bid insert did not return an id");
                }
                id = keys.getLong(1);
            }
            return findById(id);
        }
    }

    public BidInfo findById(long id) throws SQLException {
        String sql = """
                SELECT b.id, b.auction_id, b.bidder_id, u.username, b.amount_cents, b.is_proxy, b.created_at
                FROM bids b JOIN users u ON u.id = b.bidder_id
                WHERE b.id = ?
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public List<BidInfo> listByAuction(long auctionId) throws SQLException {
        String sql = """
                SELECT b.id, b.auction_id, b.bidder_id, u.username, b.amount_cents, b.is_proxy, b.created_at
                FROM bids b JOIN users u ON u.id = b.bidder_id
                WHERE b.auction_id = ?
                ORDER BY b.created_at ASC, b.id ASC
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<BidInfo> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        }
    }

    public int countByAuction(long auctionId) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COUNT(*) FROM bids WHERE auction_id = ?")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public List<Long> auctionIdsByBidder(long bidderId) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT DISTINCT auction_id FROM bids WHERE bidder_id = ?")) {
            ps.setLong(1, bidderId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
                return out;
            }
        }
    }

    private static BidInfo map(ResultSet rs) throws SQLException {
        return new BidInfo(
                rs.getLong("id"),
                rs.getLong("auction_id"),
                rs.getLong("bidder_id"),
                rs.getString("username"),
                rs.getLong("amount_cents"),
                rs.getInt("is_proxy") != 0,
                rs.getLong("created_at")
        );
    }
}

package com.openbid.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.openbid.shared.AuctionInfo;
import com.openbid.shared.Categories;

public final class AuctionDao {

    private static final String SELECT = """
            SELECT a.id, a.title, a.description, a.seller_id, s.username AS seller_name,
                   a.starting_price_cents, a.current_price_cents, a.leader_id,
                   l.username AS leader_name, a.bid_count, a.start_time, a.end_time,
                   a.status, a.version, a.category, a.reserve_cents, a.buy_now_cents,
                   a.original_end_time, a.snipe_extended,
                   CASE WHEN img.auction_id IS NULL THEN 0 ELSE 1 END AS has_image
            FROM auctions a
            JOIN users s ON s.id = a.seller_id
            LEFT JOIN users l ON l.id = a.leader_id
            LEFT JOIN auction_images img ON img.auction_id = a.id
            """;

    private final Database db;

    public AuctionDao(Database db) {
        this.db = db;
    }

    public long insert(long sellerId, String title, String description,
                       long startingPriceCents, long startTime, long endTime,
                       String category, long reserveCents, long buyNowCents) throws SQLException {
        String sql = """
                INSERT INTO auctions (seller_id, title, description, starting_price_cents,
                    current_price_cents, leader_id, start_time, end_time, original_end_time,
                    status, version, bid_count, category, reserve_cents, buy_now_cents, snipe_extended)
                VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, 'OPEN', 0, 0, ?, ?, ?, 0)
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sellerId);
            ps.setString(2, title);
            ps.setString(3, description);
            ps.setLong(4, startingPriceCents);
            ps.setLong(5, startingPriceCents);
            ps.setLong(6, startTime);
            ps.setLong(7, endTime);
            ps.setLong(8, endTime);
            ps.setString(9, Categories.normalize(category));
            ps.setLong(10, Math.max(0, reserveCents));
            ps.setLong(11, Math.max(0, buyNowCents));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Auction insert did not return an id");
                }
                return keys.getLong(1);
            }
        }
    }

    public AuctionInfo findById(long id) throws SQLException {
        String sql = SELECT + " WHERE a.id = ?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public List<AuctionInfo> listAll() throws SQLException {
        String sql = SELECT + " ORDER BY CASE a.status WHEN 'OPEN' THEN 0 ELSE 1 END, a.end_time ASC";
        try (PreparedStatement ps = db.connection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<AuctionInfo> out = new ArrayList<>();
            while (rs.next()) {
                out.add(map(rs));
            }
            return out;
        }
    }

    public List<AuctionInfo> listOpen() throws SQLException {
        String sql = SELECT + " WHERE a.status = 'OPEN'";
        try (PreparedStatement ps = db.connection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<AuctionInfo> out = new ArrayList<>();
            while (rs.next()) {
                out.add(map(rs));
            }
            return out;
        }
    }

    public int updateAfterBid(long id, long newPriceCents, long leaderId, long endTime,
                              int bidCount, int expectedVersion, boolean snipeExtended) throws SQLException {
        String sql = """
                UPDATE auctions
                SET current_price_cents = ?, leader_id = ?, end_time = ?, bid_count = ?,
                    snipe_extended = CASE WHEN ? = 1 THEN 1 ELSE snipe_extended END,
                    version = version + 1
                WHERE id = ? AND status = 'OPEN' AND version = ?
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, newPriceCents);
            ps.setLong(2, leaderId);
            ps.setLong(3, endTime);
            ps.setInt(4, bidCount);
            ps.setInt(5, snipeExtended ? 1 : 0);
            ps.setLong(6, id);
            ps.setInt(7, expectedVersion);
            return ps.executeUpdate();
        }
    }

    public int close(long id, int expectedVersion) throws SQLException {
        String sql = """
                UPDATE auctions
                SET status = 'CLOSED', version = version + 1
                WHERE id = ? AND status = 'OPEN' AND version = ?
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setInt(2, expectedVersion);
            return ps.executeUpdate();
        }
    }

    /**
     * Demo-only: reopen an unsold lot with a fresh clock. Sold rows (a matching
     * {@code sales} record) are left untouched.
     */
    public int reopenWithSchedule(long id, long startTime, long endTime, int expectedVersion)
            throws SQLException {
        String sql = """
                UPDATE auctions
                SET status = 'OPEN',
                    start_time = ?,
                    end_time = ?,
                    original_end_time = ?,
                    snipe_extended = 0,
                    version = version + 1
                WHERE id = ? AND version = ?
                  AND id NOT IN (SELECT auction_id FROM sales)
                """;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, startTime);
            ps.setLong(2, endTime);
            ps.setLong(3, endTime);
            ps.setLong(4, id);
            ps.setInt(5, expectedVersion);
            return ps.executeUpdate();
        }
    }

    private static AuctionInfo map(ResultSet rs) throws SQLException {
        long leaderRaw = rs.getLong("leader_id");
        Long leaderId = rs.wasNull() ? null : leaderRaw;
        return new AuctionInfo(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getLong("seller_id"),
                rs.getString("seller_name"),
                rs.getLong("starting_price_cents"),
                rs.getLong("current_price_cents"),
                leaderId,
                rs.getString("leader_name"),
                rs.getInt("bid_count"),
                rs.getLong("start_time"),
                rs.getLong("end_time"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getString("category"),
                rs.getLong("reserve_cents"),
                rs.getLong("buy_now_cents"),
                rs.getLong("original_end_time"),
                rs.getInt("snipe_extended") != 0,
                rs.getInt("has_image") != 0
        );
    }
}

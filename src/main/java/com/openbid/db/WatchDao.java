package com.openbid.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class WatchDao {

    private final Database db;

    public WatchDao(Database db) {
        this.db = db;
    }

    public void add(long userId, long auctionId) throws SQLException {
        String sql = "INSERT OR IGNORE INTO watches (user_id, auction_id) VALUES (?, ?)";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, auctionId);
            ps.executeUpdate();
        }
    }

    public void remove(long userId, long auctionId) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM watches WHERE user_id = ? AND auction_id = ?")) {
            ps.setLong(1, userId);
            ps.setLong(2, auctionId);
            ps.executeUpdate();
        }
    }

    public List<Long> listAuctionIds(long userId) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT auction_id FROM watches WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
                return out;
            }
        }
    }

    public List<Long> listWatcherIds(long auctionId) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT user_id FROM watches WHERE auction_id = ?")) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
                return out;
            }
        }
    }
}

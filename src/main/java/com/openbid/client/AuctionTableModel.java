package com.openbid.client;

import java.util.ArrayList;
import java.util.List;

import javax.swing.table.AbstractTableModel;

import com.openbid.shared.AuctionInfo;
import com.openbid.shared.Money;

final class AuctionTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {
            "Item", "Category", "Seller", "Price", "Leader", "Time left", "Status"
    };

    private final List<AuctionInfo> rows = new ArrayList<>();

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        AuctionInfo a = rows.get(rowIndex);
        long now = System.currentTimeMillis();
        return switch (columnIndex) {
            case 0 -> a.title();
            case 1 -> a.category();
            case 2 -> a.sellerName();
            case 3 -> Money.format(a.currentPriceCents());
            case 4 -> a.leaderName() == null || a.leaderName().isEmpty() ? "—" : a.leaderName();
            case 5 -> a.isOpen() ? UiTheme.formatRemaining(a.endTime(), now) : "Ended";
            case 6 -> status(a, now);
            default -> "";
        };
    }

    static String status(AuctionInfo a, long now) {
        if (a.isOpen()) {
            if (a.snipeExtended()) {
                return "Extended +30s";
            }
            if (a.endingSoon(now)) {
                return "Ending soon";
            }
            if (a.hasReserve() && !a.reserveMet()) {
                return "Reserve not met";
            }
            if (a.hasReserve()) {
                return "Reserve met";
            }
            return "Open";
        }
        if (a.isSold()) {
            return "Sold";
        }
        if (a.hasReserve() && a.leaderId() != null) {
            return "Unsold · reserve";
        }
        return "Unsold";
    }

    AuctionInfo get(int row) {
        if (row < 0 || row >= rows.size()) {
            return null;
        }
        return rows.get(row);
    }

    int indexOf(long auctionId) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id() == auctionId) {
                return i;
            }
        }
        return -1;
    }

    void replaceAll(List<AuctionInfo> auctions) {
        rows.clear();
        rows.addAll(auctions);
        fireTableDataChanged();
    }

    void upsert(AuctionInfo auction) {
        int idx = indexOf(auction.id());
        if (idx < 0) {
            rows.add(0, auction);
            fireTableRowsInserted(0, 0);
        } else {
            rows.set(idx, auction);
            fireTableRowsUpdated(idx, idx);
        }
    }

    void tickCountdowns() {
        if (!rows.isEmpty()) {
            fireTableRowsUpdated(0, rows.size() - 1);
        }
    }
}

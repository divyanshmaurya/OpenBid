package com.openbid.shared;

/**
 * Snapshot of an auction as sent over the wire and shown in the UI.
 * {@code leaderId} is {@code null} when nobody has bid yet.
 * Reserve amount is on the wire but the UI only shows the number to the seller.
 */
public record AuctionInfo(
        long id,
        String title,
        String description,
        long sellerId,
        String sellerName,
        long startingPriceCents,
        long currentPriceCents,
        Long leaderId,
        String leaderName,
        int bidCount,
        long startTime,
        long endTime,
        String status,
        int version,
        String category,
        long reserveCents,
        long buyNowCents,
        long originalEndTime,
        boolean snipeExtended,
        boolean hasImage
) {
    public static final int WIRE_FIELD_COUNT = 20;
    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    public boolean isOpen() {
        return OPEN.equals(status);
    }

    public boolean hasReserve() {
        return reserveCents > 0;
    }

    public boolean reserveMet() {
        if (reserveCents <= 0) {
            return true;
        }
        return leaderId != null && currentPriceCents >= reserveCents;
    }

    public boolean isSold() {
        return !isOpen() && leaderId != null && reserveMet();
    }

    public boolean buyNowAvailable() {
        return isOpen() && buyNowCents > 0 && currentPriceCents < buyNowCents;
    }

    public boolean endingSoon(long now) {
        return isOpen() && endTime - now > 0 && endTime - now <= 120_000L;
    }

    public String[] toWireFields() {
        return new String[] {
                Long.toString(id),
                title,
                description,
                Long.toString(sellerId),
                sellerName == null ? "" : sellerName,
                Long.toString(startingPriceCents),
                Long.toString(currentPriceCents),
                leaderId == null ? "" : Long.toString(leaderId),
                leaderName == null ? "" : leaderName,
                Integer.toString(bidCount),
                Long.toString(startTime),
                Long.toString(endTime),
                status,
                Integer.toString(version),
                category == null ? "Other" : category,
                Long.toString(reserveCents),
                Long.toString(buyNowCents),
                Long.toString(originalEndTime == 0 ? endTime : originalEndTime),
                snipeExtended ? "1" : "0",
                hasImage ? "1" : "0"
        };
    }

    public static AuctionInfo fromWire(String[] f, int offset) {
        Long leader = f[offset + 7].isEmpty() ? null : Long.parseLong(f[offset + 7]);
        String leaderName = f[offset + 8].isEmpty() ? null : f[offset + 8];
        String category = f.length > offset + 14 ? f[offset + 14] : "Other";
        long reserve = f.length > offset + 15 ? parse(f[offset + 15], 0) : 0;
        long buyNow = f.length > offset + 16 ? parse(f[offset + 16], 0) : 0;
        long original = f.length > offset + 17 ? parse(f[offset + 17], Long.parseLong(f[offset + 11])) : Long.parseLong(f[offset + 11]);
        boolean extended = f.length > offset + 18 && "1".equals(f[offset + 18]);
        boolean image = f.length > offset + 19 && "1".equals(f[offset + 19]);
        return new AuctionInfo(
                Long.parseLong(f[offset]),
                f[offset + 1],
                f[offset + 2],
                Long.parseLong(f[offset + 3]),
                f[offset + 4],
                Long.parseLong(f[offset + 5]),
                Long.parseLong(f[offset + 6]),
                leader,
                leaderName,
                Integer.parseInt(f[offset + 9]),
                Long.parseLong(f[offset + 10]),
                Long.parseLong(f[offset + 11]),
                f[offset + 12],
                Integer.parseInt(f[offset + 13]),
                category,
                reserve,
                buyNow,
                original,
                extended,
                image
        );
    }

    private static long parse(String s, long fallback) {
        if (s == null || s.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String encodeList(java.util.List<AuctionInfo> auctions) {
        String[] fields = new String[2 + auctions.size() * WIRE_FIELD_COUNT];
        fields[0] = Protocol.GET_AUCTIONS_OK;
        fields[1] = Integer.toString(auctions.size());
        int i = 2;
        for (AuctionInfo a : auctions) {
            String[] w = a.toWireFields();
            System.arraycopy(w, 0, fields, i, WIRE_FIELD_COUNT);
            i += WIRE_FIELD_COUNT;
        }
        return Protocol.encode(fields);
    }

    public static String encodeEvent(String type, AuctionInfo a) {
        String[] w = a.toWireFields();
        String[] fields = new String[1 + WIRE_FIELD_COUNT];
        fields[0] = type;
        System.arraycopy(w, 0, fields, 1, WIRE_FIELD_COUNT);
        return Protocol.encode(fields);
    }
}

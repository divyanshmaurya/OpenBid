package com.openbid.shared;

import java.util.List;

public record BidInfo(
        long id,
        long auctionId,
        long bidderId,
        String bidderName,
        long amountCents,
        boolean proxy,
        long createdAt
) {
    public static final int WIRE_FIELD_COUNT = 7;

    public String[] toWireFields() {
        return new String[] {
                Long.toString(id),
                Long.toString(auctionId),
                Long.toString(bidderId),
                bidderName == null ? "" : bidderName,
                Long.toString(amountCents),
                proxy ? "1" : "0",
                Long.toString(createdAt)
        };
    }

    public static BidInfo fromWire(String[] f, int offset) {
        return new BidInfo(
                Long.parseLong(f[offset]),
                Long.parseLong(f[offset + 1]),
                Long.parseLong(f[offset + 2]),
                f[offset + 3],
                Long.parseLong(f[offset + 4]),
                "1".equals(f[offset + 5]),
                Long.parseLong(f[offset + 6])
        );
    }

    public static String encodeList(long auctionId, List<BidInfo> bids) {
        String[] fields = new String[3 + bids.size() * WIRE_FIELD_COUNT];
        fields[0] = Protocol.GET_BIDS_OK;
        fields[1] = Long.toString(auctionId);
        fields[2] = Integer.toString(bids.size());
        int i = 3;
        for (BidInfo b : bids) {
            String[] w = b.toWireFields();
            System.arraycopy(w, 0, fields, i, WIRE_FIELD_COUNT);
            i += WIRE_FIELD_COUNT;
        }
        return Protocol.encode(fields);
    }
}

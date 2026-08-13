package com.openbid.server;

import com.openbid.shared.AuctionInfo;
import com.openbid.shared.BidInfo;

public final class BidResult {

    private final boolean accepted;
    private final String reason;
    private final long currentPriceCents;
    private final BidInfo bid;
    private final AuctionInfo auction;
    private final boolean extended;

    private BidResult(boolean accepted, String reason, long currentPriceCents,
                      BidInfo bid, AuctionInfo auction, boolean extended) {
        this.accepted = accepted;
        this.reason = reason;
        this.currentPriceCents = currentPriceCents;
        this.bid = bid;
        this.auction = auction;
        this.extended = extended;
    }

    public static BidResult accepted(BidInfo bid, AuctionInfo auction, boolean extended) {
        return new BidResult(true, null, auction.currentPriceCents(), bid, auction, extended);
    }

    public static BidResult rejected(String reason, long currentPriceCents) {
        return new BidResult(false, reason, currentPriceCents, null, null, false);
    }

    public boolean accepted() {
        return accepted;
    }

    public String reason() {
        return reason;
    }

    public long currentPriceCents() {
        return currentPriceCents;
    }

    public BidInfo bid() {
        return bid;
    }

    public AuctionInfo auction() {
        return auction;
    }

    public boolean extended() {
        return extended;
    }
}

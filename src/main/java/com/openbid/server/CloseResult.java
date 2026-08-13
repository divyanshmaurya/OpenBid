package com.openbid.server;

import com.openbid.db.SaleRecord;
import com.openbid.shared.AuctionInfo;

public final class CloseResult {

    public enum Kind { CLOSED, REARMED, ALREADY_CLOSED, MISSING }

    private final Kind kind;
    private final AuctionInfo auction;
    private final SaleRecord sale;

    private CloseResult(Kind kind, AuctionInfo auction, SaleRecord sale) {
        this.kind = kind;
        this.auction = auction;
        this.sale = sale;
    }

    public static CloseResult closed(AuctionInfo auction, SaleRecord sale) {
        return new CloseResult(Kind.CLOSED, auction, sale);
    }

    public static CloseResult rearmed(AuctionInfo auction) {
        return new CloseResult(Kind.REARMED, auction, null);
    }

    public static CloseResult alreadyClosed(AuctionInfo auction) {
        return new CloseResult(Kind.ALREADY_CLOSED, auction, null);
    }

    public static CloseResult missing() {
        return new CloseResult(Kind.MISSING, null, null);
    }

    public Kind kind() {
        return kind;
    }

    public AuctionInfo auction() {
        return auction;
    }

    public SaleRecord sale() {
        return sale;
    }
}

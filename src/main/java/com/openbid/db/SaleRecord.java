package com.openbid.db;

public record SaleRecord(long id, long auctionId, long buyerId, long amountCents, long createdAt) {}

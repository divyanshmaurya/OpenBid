package com.openbid.db;

public record ProxyBid(long auctionId, long bidderId, long maxCents, long createdAt) {}

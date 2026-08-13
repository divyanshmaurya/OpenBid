package com.openbid.server;

public record ListingRequest(
        String title,
        String description,
        long startingPriceCents,
        long durationMs,
        String category,
        long reserveCents,
        long buyNowCents,
        byte[] imageJpeg
) {
    public static ListingRequest basic(String title, String description, long startingPriceCents, long durationMs) {
        return new ListingRequest(title, description, startingPriceCents, durationMs, "Other", 0, 0, null);
    }
}

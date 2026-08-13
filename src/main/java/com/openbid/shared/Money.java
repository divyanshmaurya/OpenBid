package com.openbid.shared;

import java.text.NumberFormat;
import java.util.Locale;

/** Money is stored and compared as integer cents end-to-end. */
public final class Money {

    private Money() {}

    public static String format(long cents) {
        NumberFormat nf = NumberFormat.getCurrencyInstance(Locale.US);
        return nf.format(cents / 100.0);
    }

    /**
     * Accepts {@code 12}, {@code 12.5}, {@code 12.50}, {@code $12.50}.
     *
     * @throws IllegalArgumentException if the text is not a non-negative amount
     */
    public static long parseToCents(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        String t = text.trim();
        if (t.startsWith("$")) {
            t = t.substring(1).trim();
        }
        t = t.replace(",", "");
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Amount is required");
        }
        try {
            double dollars = Double.parseDouble(t);
            if (dollars < 0 || Double.isNaN(dollars) || Double.isInfinite(dollars)) {
                throw new IllegalArgumentException("Amount must be non-negative");
            }
            return Math.round(dollars * 100.0);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid amount: " + text);
        }
    }
}

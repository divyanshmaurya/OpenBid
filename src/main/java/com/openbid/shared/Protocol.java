package com.openbid.shared;

import java.util.ArrayList;
import java.util.List;

/**
 * Line-based wire protocol: fields separated by {@code |}, with {@code \},
 * {@code |} and newlines escaped so titles and descriptions can contain anything.
 */
public final class Protocol {

    public static final int DEFAULT_PORT = 9000;

    public static final String REGISTER = "REGISTER";
    public static final String REGISTER_OK = "REGISTER_OK";
    public static final String REGISTER_FAIL = "REGISTER_FAIL";

    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_OK = "LOGIN_OK";
    public static final String LOGIN_FAIL = "LOGIN_FAIL";

    public static final String LIST_ITEM = "LIST_ITEM";
    public static final String LIST_ITEM_OK = "LIST_ITEM_OK";
    public static final String LIST_ITEM_FAIL = "LIST_ITEM_FAIL";

    public static final String BID = "BID";
    public static final String BID_OK = "BID_OK";
    public static final String BID_REJECTED = "BID_REJECTED";

    public static final String PROXY_BID = "PROXY_BID";
    public static final String PROXY_BID_OK = "PROXY_BID_OK";
    public static final String PROXY_BID_FAIL = "PROXY_BID_FAIL";

    public static final String GET_AUCTIONS = "GET_AUCTIONS";
    public static final String GET_AUCTIONS_OK = "GET_AUCTIONS_OK";
    public static final String GET_AUCTIONS_FAIL = "GET_AUCTIONS_FAIL";

    public static final String GET_BIDS = "GET_BIDS";
    public static final String GET_BIDS_OK = "GET_BIDS_OK";
    public static final String GET_BIDS_FAIL = "GET_BIDS_FAIL";

    public static final String NEW_BID = "NEW_BID";
    public static final String AUCTION_UPDATED = "AUCTION_UPDATED";
    public static final String AUCTION_EXTENDED = "AUCTION_EXTENDED";
    public static final String AUCTION_CLOSED = "AUCTION_CLOSED";
    public static final String AUCTION_NEW = "AUCTION_NEW";

    public static final String BUY_NOW = "BUY_NOW";
    public static final String BUY_NOW_OK = "BUY_NOW_OK";
    public static final String BUY_NOW_FAIL = "BUY_NOW_FAIL";

    public static final String RELIST = "RELIST";
    public static final String RELIST_OK = "RELIST_OK";
    public static final String RELIST_FAIL = "RELIST_FAIL";

    public static final String WATCH = "WATCH";
    public static final String UNWATCH = "UNWATCH";
    public static final String GET_WATCHES = "GET_WATCHES";
    public static final String WATCHES_OK = "WATCHES_OK";
    public static final String WATCH_FAIL = "WATCH_FAIL";

    public static final String GET_MY_BIDS = "GET_MY_BIDS";
    public static final String MY_BIDS_OK = "MY_BIDS_OK";

    public static final String GET_IMAGE = "GET_IMAGE";
    public static final String IMAGE_OK = "IMAGE_OK";
    public static final String IMAGE_FAIL = "IMAGE_FAIL";

    public static final String YOU_OUTBID = "YOU_OUTBID";
    public static final String YOU_WON = "YOU_WON";
    public static final String TICKER = "TICKER";
    public static final String WATCH_ALERT = "WATCH_ALERT";

    public static final String QUIT = "QUIT";

    private Protocol() {}

    public static String encode(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(escape(fields[i]));
        }
        return sb.toString();
    }

    public static String[] decode(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                if (c == 'n') {
                    cur.append('\n');
                } else {
                    cur.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '|') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields.toArray(String[]::new);
    }

    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '|' -> sb.append("\\|");
                case '\n' -> sb.append("\\n");
                case '\r' -> { /* drop CR */ }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String typeOf(String[] fields) {
        return fields.length == 0 ? "" : fields[0];
    }

    public static long parseLong(String[] fields, int index, long fallback) {
        if (index >= fields.length || fields[index].isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(fields[index]);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static int parseInt(String[] fields, int index, int fallback) {
        return (int) parseLong(fields, index, fallback);
    }
}

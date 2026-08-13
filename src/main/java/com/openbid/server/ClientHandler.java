package com.openbid.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import com.openbid.db.UserRecord;
import com.openbid.security.PasswordHasher;
import com.openbid.shared.AuctionInfo;
import com.openbid.shared.BidInfo;
import com.openbid.shared.Protocol;

/**
 * One handler per TCP connection, run on the server thread pool.
 * Outbound writes are synchronized so a reply and a broadcast cannot interleave.
 */
public final class ClientHandler implements Runnable {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,20}$");

    private final Socket socket;
    private final AuctionServer server;
    private final BidManager bidManager;
    private final Object writeLock = new Object();

    private PrintWriter out;
    private volatile boolean loggedIn;
    private volatile long userId;
    private volatile String username;

    public ClientHandler(Socket socket, AuctionServer server, BidManager bidManager) {
        this.socket = socket;
        this.server = server;
        this.bidManager = bidManager;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public long userId() {
        return userId;
    }

    public void send(String message) {
        synchronized (writeLock) {
            if (out != null) {
                out.println(message);
                out.flush();
            }
        }
    }

    public void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closing
        }
    }

    @Override
    public void run() {
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            this.out = writer;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                handle(line);
            }
        } catch (IOException e) {
            // client disconnected or the socket was closed
        } finally {
            this.out = null;
            server.remove(this);
            closeQuietly();
            System.out.println("Disconnected "
                    + (username == null ? socket.getRemoteSocketAddress() : username));
        }
    }

    private void handle(String line) {
        String[] f = Protocol.decode(line);
        String type = Protocol.typeOf(f);
        try {
            switch (type) {
                case Protocol.REGISTER -> register(f);
                case Protocol.LOGIN -> login(f);
                case Protocol.LIST_ITEM -> listItem(f);
                case Protocol.BID -> bid(f);
                case Protocol.PROXY_BID -> proxyBid(f);
                case Protocol.BUY_NOW -> buyNow(f);
                case Protocol.RELIST -> relist(f);
                case Protocol.WATCH -> watch(f, true);
                case Protocol.UNWATCH -> watch(f, false);
                case Protocol.GET_WATCHES -> getWatches();
                case Protocol.GET_MY_BIDS -> getMyBids();
                case Protocol.GET_IMAGE -> getImage(f);
                case Protocol.GET_AUCTIONS -> getAuctions();
                case Protocol.GET_BIDS -> getBids(f);
                case Protocol.QUIT -> closeQuietly();
                default -> send(Protocol.encode("ERROR", "Unknown command: " + type));
            }
        } catch (Exception e) {
            send(Protocol.encode("ERROR", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void register(String[] f) throws Exception {
        if (f.length < 3) {
            send(Protocol.encode(Protocol.REGISTER_FAIL, "Usage: REGISTER|username|password"));
            return;
        }
        String name = f[1].trim();
        String password = f[2];
        String err = validateNewUser(name, password);
        if (err != null) {
            send(Protocol.encode(Protocol.REGISTER_FAIL, err));
            return;
        }
        if (bidManager.users().findByUsername(name) != null) {
            send(Protocol.encode(Protocol.REGISTER_FAIL, "Username already taken"));
            return;
        }
        byte[] salt = PasswordHasher.randomSalt();
        byte[] hash = PasswordHasher.hash(password.toCharArray(), salt);
        long id = bidManager.users().insert(name, salt, hash);
        this.userId = id;
        this.username = name;
        this.loggedIn = true;
        send(Protocol.encode(Protocol.REGISTER_OK, Long.toString(id), name));
    }

    private void login(String[] f) throws Exception {
        if (f.length < 3) {
            send(Protocol.encode(Protocol.LOGIN_FAIL, "Usage: LOGIN|username|password"));
            return;
        }
        String name = f[1].trim();
        String password = f[2];
        UserRecord user = bidManager.users().findByUsername(name);
        if (user == null || !PasswordHasher.verify(password.toCharArray(), user.salt(), user.passwordHash())) {
            send(Protocol.encode(Protocol.LOGIN_FAIL, "Invalid username or password"));
            return;
        }
        this.userId = user.id();
        this.username = user.username();
        this.loggedIn = true;
        send(Protocol.encode(Protocol.LOGIN_OK, Long.toString(user.id()), user.username()));
    }

    private void listItem(String[] f) {
        if (!requireLogin(Protocol.LIST_ITEM_FAIL)) {
            return;
        }
        if (f.length < 5) {
            send(Protocol.encode(Protocol.LIST_ITEM_FAIL, "Usage: LIST_ITEM|title|description|priceCents|durationSeconds"));
            return;
        }
        try {
            long price = Long.parseLong(f[3]);
            long durationMs = Long.parseLong(f[4]) * 1000L;
            String category = f.length > 5 ? f[5] : "Other";
            long reserve = f.length > 6 ? Long.parseLong(emptyToZero(f[6])) : 0;
            long buyNow = f.length > 7 ? Long.parseLong(emptyToZero(f[7])) : 0;
            byte[] image = f.length > 8 && !f[8].isEmpty() ? java.util.Base64.getDecoder().decode(f[8]) : null;
            AuctionInfo created = bidManager.createAuction(userId, new ListingRequest(
                    f[1], f[2], price, durationMs, category, reserve, buyNow, image));
            send(Protocol.encode(Protocol.LIST_ITEM_OK, Long.toString(created.id())));
        } catch (IllegalArgumentException e) {
            send(Protocol.encode(Protocol.LIST_ITEM_FAIL, e.getMessage() == null ? "Invalid listing" : e.getMessage()));
        }
    }

    private void bid(String[] f) {
        if (!requireLogin(Protocol.BID_REJECTED)) {
            return;
        }
        if (f.length < 3) {
            send(Protocol.encode(Protocol.BID_REJECTED, "0", "Usage: BID|auctionId|amountCents", "0"));
            return;
        }
        long auctionId = Protocol.parseLong(f, 1, -1);
        long amount = Protocol.parseLong(f, 2, -1);
        BidResult result = bidManager.placeBid(auctionId, userId, amount);
        if (result.accepted()) {
            send(Protocol.encode(
                    Protocol.BID_OK,
                    Long.toString(auctionId),
                    Long.toString(amount),
                    Long.toString(result.currentPriceCents())
            ));
        } else {
            send(Protocol.encode(
                    Protocol.BID_REJECTED,
                    Long.toString(auctionId),
                    result.reason(),
                    Long.toString(result.currentPriceCents())
            ));
        }
    }

    private void proxyBid(String[] f) {
        if (!requireLogin(Protocol.PROXY_BID_FAIL)) {
            return;
        }
        if (f.length < 3) {
            send(Protocol.encode(Protocol.PROXY_BID_FAIL, "Usage: PROXY_BID|auctionId|maxCents"));
            return;
        }
        long auctionId = Protocol.parseLong(f, 1, -1);
        long max = Protocol.parseLong(f, 2, -1);
        BidResult result = bidManager.setProxyBid(auctionId, userId, max);
        if (result.accepted()) {
            send(Protocol.encode(Protocol.PROXY_BID_OK, Long.toString(auctionId), Long.toString(max)));
        } else {
            send(Protocol.encode(Protocol.PROXY_BID_FAIL, result.reason()));
        }
    }

    private void getAuctions() throws Exception {
        if (!requireLogin(Protocol.GET_AUCTIONS_FAIL)) {
            return;
        }
        List<AuctionInfo> list = bidManager.auctions().listAll();
        send(AuctionInfo.encodeList(list));
    }

    private void getBids(String[] f) throws Exception {
        if (!requireLogin(Protocol.GET_BIDS_FAIL)) {
            return;
        }
        long auctionId = Protocol.parseLong(f, 1, -1);
        List<BidInfo> list = bidManager.bids().listByAuction(auctionId);
        send(BidInfo.encodeList(auctionId, list));
    }

    private void buyNow(String[] f) {
        if (!requireLogin(Protocol.BUY_NOW_FAIL)) {
            return;
        }
        long auctionId = Protocol.parseLong(f, 1, -1);
        BidResult result = bidManager.buyNow(auctionId, userId);
        if (result.accepted()) {
            send(Protocol.encode(Protocol.BUY_NOW_OK, Long.toString(auctionId),
                    Long.toString(result.currentPriceCents())));
        } else {
            send(Protocol.encode(Protocol.BUY_NOW_FAIL, result.reason()));
        }
    }

    private void relist(String[] f) {
        if (!requireLogin(Protocol.RELIST_FAIL)) {
            return;
        }
        try {
            long auctionId = Protocol.parseLong(f, 1, -1);
            long durationMs = Protocol.parseLong(f, 2, 300) * 1000L;
            AuctionInfo created = bidManager.relist(userId, auctionId, durationMs);
            send(Protocol.encode(Protocol.RELIST_OK, Long.toString(created.id())));
        } catch (IllegalArgumentException e) {
            send(Protocol.encode(Protocol.RELIST_FAIL, e.getMessage()));
        }
    }

    private void watch(String[] f, boolean add) throws Exception {
        if (!requireLogin(Protocol.WATCH_FAIL)) {
            return;
        }
        long auctionId = Protocol.parseLong(f, 1, -1);
        if (add) {
            bidManager.watch(userId, auctionId);
        } else {
            bidManager.unwatch(userId, auctionId);
        }
        getWatches();
    }

    private void getWatches() throws Exception {
        if (!requireLogin(Protocol.WATCH_FAIL)) {
            return;
        }
        List<Long> ids = bidManager.watches().listAuctionIds(userId);
        String[] fields = new String[1 + ids.size()];
        fields[0] = Protocol.WATCHES_OK;
        for (int i = 0; i < ids.size(); i++) {
            fields[i + 1] = Long.toString(ids.get(i));
        }
        send(Protocol.encode(fields));
    }

    private void getMyBids() throws Exception {
        if (!requireLogin(Protocol.GET_AUCTIONS_FAIL)) {
            return;
        }
        List<Long> ids = bidManager.bids().auctionIdsByBidder(userId);
        String[] fields = new String[1 + ids.size()];
        fields[0] = Protocol.MY_BIDS_OK;
        for (int i = 0; i < ids.size(); i++) {
            fields[i + 1] = Long.toString(ids.get(i));
        }
        send(Protocol.encode(fields));
    }

    private void getImage(String[] f) throws Exception {
        if (!requireLogin(Protocol.IMAGE_FAIL)) {
            return;
        }
        long auctionId = Protocol.parseLong(f, 1, -1);
        byte[] jpeg;
        try {
            jpeg = bidManager.imageJpeg(auctionId);
        } catch (RuntimeException e) {
            send(Protocol.encode(Protocol.IMAGE_FAIL, Long.toString(auctionId), "No image"));
            return;
        }
        if (jpeg == null || jpeg.length == 0) {
            send(Protocol.encode(Protocol.IMAGE_FAIL, Long.toString(auctionId), "No image"));
            return;
        }
        send(Protocol.encode(Protocol.IMAGE_OK, Long.toString(auctionId),
                java.util.Base64.getEncoder().encodeToString(jpeg)));
    }

    private static String emptyToZero(String s) {
        return s == null || s.isBlank() ? "0" : s;
    }

    private boolean requireLogin(String failType) {
        if (!loggedIn) {
            send(Protocol.encode(failType, "Not logged in"));
            return false;
        }
        return true;
    }

    private static String validateNewUser(String name, String password) {
        if (!USERNAME.matcher(name).matches()) {
            return "Username must be 3–20 letters, digits or underscores";
        }
        if (password == null || password.length() < 6) {
            return "Password must be at least 6 characters";
        }
        return null;
    }
}

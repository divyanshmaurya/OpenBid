package com.openbid.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.openbid.shared.Protocol;

/**
 * Scripted simultaneous-bid test: many socket clients fire at once against one
 * live auction and then check the database. Run against a started server:
 *
 * <pre>
 *   java -jar target/openbid-server.jar --demo
 *   java -cp target/openbid-server.jar com.openbid.tools.StressTestClient
 * </pre>
 */
public final class StressTestClient {

    private static final int BIDDERS = 12;
    private static final int BIDS_EACH = 20;

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = Protocol.DEFAULT_PORT;
        Path dbPath = Path.of("openbid.db");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--db" -> dbPath = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(1);
                }
            }
        }

        String suffix = Long.toString(System.currentTimeMillis() % 100_000_000L);
        SocketClient seller = SocketClient.connect(host, port);
        seller.request(Protocol.encode(Protocol.REGISTER, "ss" + suffix, "password123"));
        seller.expect(Protocol.REGISTER_OK);

        List<SocketClient> bidders = new ArrayList<>();
        for (int i = 0; i < BIDDERS; i++) {
            SocketClient c = SocketClient.connect(host, port);
            c.request(Protocol.encode(Protocol.REGISTER, "sb" + suffix + i, "password123"));
            c.expect(Protocol.REGISTER_OK);
            bidders.add(c);
        }

        seller.request(Protocol.encode(Protocol.LIST_ITEM,
                "Stress Lot", "Concurrent bid target", "1000", "600"));
        String[] listed = seller.expect(Protocol.LIST_ITEM_OK);
        long auctionId = Long.parseLong(listed[1]);

        CountDownLatch ready = new CountDownLatch(BIDDERS);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(BIDDERS);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int b = 0; b < BIDDERS; b++) {
            final int bidder = b;
            SocketClient client = bidders.get(b);
            Thread t = new Thread(() -> {
                try {
                    ready.countDown();
                    fire.await();
                    for (int i = 0; i < BIDS_EACH; i++) {
                        long amount = 1000 + 100L * (i * BIDDERS + bidder + 1);
                        client.request(Protocol.encode(Protocol.BID,
                                Long.toString(auctionId), Long.toString(amount)));
                        String[] reply = client.expectEither(Protocol.BID_OK, Protocol.BID_REJECTED);
                        if (Protocol.BID_OK.equals(reply[0])) {
                            accepted.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("bidder " + bidder + ": " + e.getMessage());
                } finally {
                    done.countDown();
                }
            }, "stress-bidder-" + b);
            t.start();
        }

        if (!ready.await(15, TimeUnit.SECONDS)) {
            System.err.println("Bidders did not become ready");
            System.exit(2);
        }
        long t0 = System.currentTimeMillis();
        fire.countDown();
        if (!done.await(60, TimeUnit.SECONDS)) {
            System.err.println("Timed out waiting for bids");
            System.exit(3);
        }
        long elapsed = System.currentTimeMillis() - t0;

        int fired = BIDDERS * BIDS_EACH;
        DbCheck db = DbCheck.open(dbPath, auctionId);

        boolean recordedMatch = db.bidCount == accepted.get();
        boolean increasing = db.strictlyIncreasing;
        boolean priceMatch = db.topBidCents == db.currentPriceCents;
        boolean singleLeader = db.leaderId != 0 && db.leaderId == db.topBidderId;

        System.out.println();
        System.out.println("bids fired     : " + fired + " (in " + elapsed + " ms)");
        System.out.println("accepted       : " + accepted.get());
        System.out.println("rejected       : " + rejected.get());
        System.out.println("recorded in DB : " + db.bidCount);
        System.out.println("checks         : recorded==accepted " + flag(recordedMatch)
                + " | strictly increasing " + flag(increasing));
        System.out.println("               | price matches top bid " + flag(priceMatch)
                + " | single leader " + flag(singleLeader));
        boolean pass = recordedMatch && increasing && priceMatch && singleLeader
                && accepted.get() + rejected.get() == fired;
        System.out.println("RESULT: " + (pass ? "PASS" : "FAIL")
                + " — the server serialized all concurrent bids correctly");

        seller.close();
        for (SocketClient c : bidders) {
            c.close();
        }
        System.exit(pass ? 0 : 1);
    }

    private static String flag(boolean ok) {
        return ok ? "PASS" : "FAIL";
    }

    private static final class DbCheck {
        final int bidCount;
        final boolean strictlyIncreasing;
        final long currentPriceCents;
        final long leaderId;
        final long topBidCents;
        final long topBidderId;

        private DbCheck(int bidCount, boolean strictlyIncreasing, long currentPriceCents,
                        long leaderId, long topBidCents, long topBidderId) {
            this.bidCount = bidCount;
            this.strictlyIncreasing = strictlyIncreasing;
            this.currentPriceCents = currentPriceCents;
            this.leaderId = leaderId;
            this.topBidCents = topBidCents;
            this.topBidderId = topBidderId;
        }

        static DbCheck open(Path dbPath, long auctionId) throws Exception {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
                int count;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM bids WHERE auction_id = ?")) {
                    ps.setLong(1, auctionId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        count = rs.getInt(1);
                    }
                }
                boolean increasing = true;
                long prev = -1;
                long topAmount = 0;
                long topBidder = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT amount_cents, bidder_id FROM bids WHERE auction_id = ? ORDER BY created_at, id")) {
                    ps.setLong(1, auctionId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long amt = rs.getLong(1);
                            if (amt <= prev) {
                                increasing = false;
                            }
                            prev = amt;
                            topAmount = amt;
                            topBidder = rs.getLong(2);
                        }
                    }
                }
                long price;
                long leader;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT current_price_cents, leader_id FROM auctions WHERE id = ?")) {
                    ps.setLong(1, auctionId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        price = rs.getLong(1);
                        leader = rs.getLong(2);
                    }
                }
                return new DbCheck(count, increasing, price, leader, topAmount, topBidder);
            }
        }
    }

    private static final class SocketClient {
        private final Socket socket;
        private final PrintWriter out;
        private final List<String> inbox = new CopyOnWriteArrayList<>();
        private final Object lock = new Object();

        private SocketClient(Socket socket, PrintWriter out) {
            this.socket = socket;
            this.out = out;
        }

        static SocketClient connect(String host, int port) throws Exception {
            Socket socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            SocketClient client = new SocketClient(socket, out);
            Thread t = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (client.lock) {
                            client.inbox.add(line);
                            client.lock.notifyAll();
                        }
                    }
                } catch (Exception ignored) {
                    // closed
                }
            }, "stress-reader");
            t.setDaemon(true);
            t.start();
            return client;
        }

        void request(String message) {
            out.println(message);
            out.flush();
        }

        String[] expect(String type) throws InterruptedException {
            return expectEither(type);
        }

        String[] expectEither(String... types) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 15_000;
            synchronized (lock) {
                while (System.currentTimeMillis() < deadline) {
                    for (int i = 0; i < inbox.size(); i++) {
                        String[] fields = Protocol.decode(inbox.get(i));
                        for (String type : types) {
                            if (type.equals(fields[0])) {
                                inbox.remove(i);
                                return fields;
                            }
                        }
                    }
                    long wait = deadline - System.currentTimeMillis();
                    if (wait <= 0) {
                        break;
                    }
                    lock.wait(wait);
                }
            }
            throw new IllegalStateException("Timed out waiting for " + String.join("/", types));
        }

        void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
                // done
            }
        }
    }
}

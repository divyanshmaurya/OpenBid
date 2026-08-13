package com.openbid.server;

import java.nio.file.Path;
import java.sql.SQLException;

import com.openbid.db.Database;
import com.openbid.db.UserRecord;
import com.openbid.security.PasswordHasher;
import com.openbid.shared.CatalogImage;
import com.openbid.shared.Protocol;

public final class ServerMain {

    public static final String DEMO_PASSWORD = "password000";
    public static final String[] DEMO_USERS = {"peter", "harry", "jane", "mohan"};

    public static void main(String[] args) throws Exception {
        int port = Protocol.DEFAULT_PORT;
        Path dbPath = Path.of("openbid.db");
        boolean demo = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--db" -> dbPath = Path.of(args[++i]);
                case "--demo" -> demo = true;
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(1);
                }
            }
        }

        Database db = Database.open(dbPath);
        BidManager bidManager = new BidManager(db);
        AuctionScheduler scheduler = new AuctionScheduler();
        AuctionServer server = new AuctionServer(port, bidManager, scheduler);
        bidManager.setScheduler(scheduler);
        bidManager.setServer(server);
        scheduler.setBidManager(bidManager);

        if (demo) {
            seedDemo(bidManager);
        }
        bidManager.recoverOpenAuctions();

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "openbid-shutdown"));
        System.out.println("Database: " + dbPath.toAbsolutePath());
        if (demo) {
            System.out.println("Demo accounts: peter / harry / jane / mohan  (" + DEMO_PASSWORD + ")");
        }
        server.start();
    }

    static void seedDemo(BidManager bidManager) throws Exception {
        for (String name : DEMO_USERS) {
            ensureUser(bidManager, name, DEMO_PASSWORD);
        }
        if (!bidManager.auctions().listAll().isEmpty()) {
            refreshDemoListings(bidManager);
            return;
        }
        UserRecord peter = bidManager.users().findByUsername("peter");
        UserRecord harry = bidManager.users().findByUsername("harry");
        UserRecord jane = bidManager.users().findByUsername("jane");
        UserRecord mohan = bidManager.users().findByUsername("mohan");

        list(bidManager, peter.id(), "Vintage Rangefinder Camera",
                "A well-loved 35mm rangefinder from the 1960s. Meter works, light seals replaced last year.",
                2500, 45_000, "Electronics", 0, 0);
        list(bidManager, harry.id(), "Signed Home Jersey",
                "Match-worn jersey, signed on the number. Certificate of authenticity included.",
                5000, 10 * 60 * 1000L, "Sports", 8000, 0);
        list(bidManager, jane.id(), "Rare Pressing — Kind of Blue",
                "Original Columbia six-eye pressing. Play-graded VG+. No skips, original sleeve.",
                1000, 2 * 60 * 1000L, "Music", 0, 4000);
        list(bidManager, mohan.id(), "Italian Leather Jacket",
                "Soft lambskin, size M. Barely worn. Comes with the original garment bag.",
                3500, 15 * 60 * 1000L, "Fashion", 0, 0);
        list(bidManager, peter.id(), "Mechanical Keyboard (hot-swap)",
                "75% layout, gasket mount, lubed switches. Includes extra keycaps.",
                2000, 8 * 60 * 1000L, "Electronics", 3000, 0);
        var comic = list(bidManager, harry.id(), "Amazing Fantasy #15 (reprint, VF)",
                "High-grade reprint of the first Spider-Man appearance. Bagged and boarded.",
                1500, 90_000, "Collectibles", 0, 0);
        list(bidManager, jane.id(), "Espresso Machine",
                "15-bar pump, PID temperature, included tamper and pitcher. Used twice.",
                8000, 12 * 60 * 1000L, "Home", 0, 15_000);
        list(bidManager, mohan.id(), "Trail Hiking Boots",
                "Waterproof, size 10. Vibram sole, barely scuffed. Original box.",
                2200, 5 * 60 * 1000L, "Sports", 0, 0);
        list(bidManager, peter.id(), "Polaroid 600 Film Pack (expired, cold-stored)",
                "Shot a test frame — still has that washed pastel look. Eight shots left.",
                800, 20 * 60 * 1000L, "Collectibles", 0, 0);
        list(bidManager, harry.id(), "Brass Desk Lamp",
                "Weighted base, linen shade. Rewired last year with a dimmer switch.",
                1200, 70_000, "Home", 0, 0);

        bidManager.setProxyBid(comic.id(), peter.id(), 5000);
        bidManager.setProxyBid(comic.id(), jane.id(), 3000);
        System.out.println("Seeded 10 open auctions (including a live proxy battle on the comic).");
    }

    /**
     * Package-visible so tests can drive the same path {@code --demo} uses on an
     * existing database. No-op when there is nothing unsold to reopen.
     */
    static int refreshDemoListings(BidManager bidManager) {
        int n = bidManager.refreshDemoSchedule();
        if (n > 0) {
            System.out.println("Refreshed " + n + " demo listings (still open)");
        }
        return n;
    }

    private static com.openbid.shared.AuctionInfo list(BidManager bidManager, long sellerId, String title,
                                                       String description, long start, long durationMs,
                                                       String category, long reserve, long buyNow) {
        byte[] jpeg = CatalogImage.jpeg(title, category);
        return bidManager.createAuction(sellerId, new ListingRequest(
                title, description, start, durationMs, category, reserve, buyNow, jpeg));
    }

    private static void ensureUser(BidManager bidManager, String username, String password) throws SQLException {
        byte[] salt = PasswordHasher.randomSalt();
        byte[] hash = PasswordHasher.hash(password.toCharArray(), salt);
        UserRecord existing = bidManager.users().findByUsername(username);
        if (existing != null) {
            bidManager.users().updatePassword(existing.id(), salt, hash);
            return;
        }
        bidManager.users().insert(username, salt, hash);
    }

    private static void printUsage() {
        System.out.println("""
                OpenBid server
                  --port N       listen port (default 9000)
                  --db PATH      SQLite file (default ./openbid.db)
                  --demo         seed peter/harry/jane/mohan (password000) and sample auctions;
                                 on restart, refresh unsold listing clocks (sold lots stay sold)
                """);
    }
}

package com.openbid.server;

import java.util.ArrayList;
import java.util.List;

import com.openbid.db.AuctionDao;
import com.openbid.db.BidDao;
import com.openbid.db.Database;
import com.openbid.db.ImageDao;
import com.openbid.db.OptimisticLockException;
import com.openbid.db.ProxyBid;
import com.openbid.db.ProxyBidDao;
import com.openbid.db.SaleDao;
import com.openbid.db.SaleRecord;
import com.openbid.db.UserDao;
import com.openbid.db.UserRecord;
import com.openbid.db.WatchDao;
import com.openbid.shared.AuctionInfo;
import com.openbid.shared.BidInfo;
import com.openbid.shared.Protocol;

/**
 * Single serialization point for every auction mutation. {@code synchronized}
 * methods guarantee that two last-second bids cannot both win; JDBC transactions
 * plus an optimistic version column are the second safeguard.
 */
public final class BidManager {

    public static final long MIN_INCREMENT_CENTS = 100L;
    public static final long ANTI_SNIPE_WINDOW_MS = 30_000L;
    public static final int MIN_DURATION_MS = 5_000;

    /** Stagger used when {@code --demo} restarts against an existing database. */
    static final int MAX_DEMO_LIVE_LOTS = 10;
    static final long[] DEMO_CLOCK_DURATIONS_MS = {
            45_000L,
            60_000L,
            2 * 60_000L,
            5 * 60_000L,
            10 * 60_000L,
            15 * 60_000L,
            30 * 60_000L,
            8 * 60_000L,
            12 * 60_000L,
            90_000L
    };

    private final Database db;
    private final UserDao users;
    private final AuctionDao auctions;
    private final BidDao bids;
    private final SaleDao sales;
    private final ProxyBidDao proxies;
    private final ImageDao images;
    private final WatchDao watches;

    private AuctionScheduler scheduler;
    private AuctionServer server;

    public BidManager(Database db) {
        this.db = db;
        this.users = new UserDao(db);
        this.auctions = new AuctionDao(db);
        this.bids = new BidDao(db);
        this.sales = new SaleDao(db);
        this.proxies = new ProxyBidDao(db);
        this.images = new ImageDao(db);
        this.watches = new WatchDao(db);
    }

    public Database database() {
        return db;
    }

    public UserDao users() {
        return users;
    }

    public AuctionDao auctions() {
        return auctions;
    }

    public BidDao bids() {
        return bids;
    }

    public SaleDao sales() {
        return sales;
    }

    public ImageDao images() {
        return images;
    }

    /**
     * SQLite uses one connection; GET_IMAGE must take the same lock as mutations
     * so handler threads do not read while another thread is writing.
     */
    public synchronized byte[] imageJpeg(long auctionId) {
        try {
            return images.find(auctionId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load image", e);
        }
    }

    public WatchDao watches() {
        return watches;
    }

    public void setScheduler(AuctionScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void setServer(AuctionServer server) {
        this.server = server;
    }

    public synchronized AuctionInfo createAuction(long sellerId, String title, String description,
                                                  long startingPriceCents, long durationMs) {
        return createAuction(sellerId, ListingRequest.basic(title, description, startingPriceCents, durationMs));
    }

    public synchronized AuctionInfo createAuction(long sellerId, ListingRequest listing) {
        if (listing.title() == null || listing.title().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        String description = listing.description() == null ? "" : listing.description();
        if (listing.startingPriceCents() < 0) {
            throw new IllegalArgumentException("Starting price cannot be negative");
        }
        if (listing.durationMs() < MIN_DURATION_MS) {
            throw new IllegalArgumentException("Duration is too short");
        }
        if (listing.buyNowCents() > 0 && listing.buyNowCents() < listing.startingPriceCents()) {
            throw new IllegalArgumentException("Buy It Now must be at least the starting price");
        }
        if (listing.reserveCents() > 0 && listing.reserveCents() < listing.startingPriceCents()) {
            throw new IllegalArgumentException("Reserve must be at least the starting price");
        }
        long now = System.currentTimeMillis();
        long end = now + listing.durationMs();
        try {
            String desc = description;
            long id = db.inTransaction(() -> {
                long auctionId = auctions.insert(sellerId, listing.title().trim(), desc,
                        listing.startingPriceCents(), now, end, listing.category(),
                        listing.reserveCents(), listing.buyNowCents());
                byte[] jpeg = listing.imageJpeg();
                if (jpeg == null || jpeg.length == 0) {
                    jpeg = com.openbid.shared.CatalogImage.jpeg(listing.title(), listing.category());
                }
                images.upsert(auctionId, jpeg);
                return auctionId;
            });
            AuctionInfo created = auctions.findById(id);
            if (scheduler != null) {
                scheduler.arm(id, end);
            }
            broadcast(AuctionInfo.encodeEvent(Protocol.AUCTION_NEW, created));
            ticker(created.sellerName() + " listed “" + created.title() + "”");
            return created;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list item", e);
        }
    }

    public synchronized BidResult placeBid(long auctionId, long bidderId, long amountCents) {
        BidResult result = placeBidInternal(auctionId, bidderId, amountCents, false);
        if (result.accepted()) {
            resolveProxies(auctionId);
        }
        return result;
    }

    public synchronized BidResult setProxyBid(long auctionId, long bidderId, long maxCents) {
        try {
            AuctionInfo a = auctions.findById(auctionId);
            if (a == null) {
                return BidResult.rejected("No such auction", 0);
            }
            if (!a.isOpen()) {
                return BidResult.rejected("Auction is closed", a.currentPriceCents());
            }
            if (a.sellerId() == bidderId) {
                return BidResult.rejected("Seller cannot bid", a.currentPriceCents());
            }
            long min = minNextBid(a);
            boolean alreadyLeading = a.leaderId() != null && a.leaderId() == bidderId;
            if (!alreadyLeading && maxCents < min) {
                return BidResult.rejected("Maximum must be at least " + min + " cents", a.currentPriceCents());
            }
            if (alreadyLeading && maxCents <= a.currentPriceCents()) {
                return BidResult.rejected("Maximum must be above the current price", a.currentPriceCents());
            }
            proxies.upsert(auctionId, bidderId, maxCents, System.currentTimeMillis());
            resolveProxies(auctionId);
            AuctionInfo updated = auctions.findById(auctionId);
            return BidResult.accepted(null, updated, false);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to set proxy bid", e);
        }
    }

    public synchronized CloseResult closeAuction(long auctionId) {
        return closeAuction(auctionId, false);
    }

    public synchronized BidResult buyNow(long auctionId, long buyerId) {
        try {
            AuctionInfo a = auctions.findById(auctionId);
            if (a == null) {
                return BidResult.rejected("No such auction", 0);
            }
            if (!a.buyNowAvailable()) {
                return BidResult.rejected("Buy It Now is not available", a.currentPriceCents());
            }
            if (a.sellerId() == buyerId) {
                return BidResult.rejected("Seller cannot buy their own item", a.currentPriceCents());
            }
            BidResult bid = placeBidInternal(auctionId, buyerId, a.buyNowCents(), false);
            if (!bid.accepted()) {
                return bid;
            }
            closeAuction(auctionId, true);
            AuctionInfo closed = auctions.findById(auctionId);
            return BidResult.accepted(bid.bid(), closed, false);
        } catch (Exception e) {
            throw new IllegalStateException("Buy It Now failed", e);
        }
    }

    public synchronized AuctionInfo relist(long sellerId, long auctionId, long durationMs) {
        try {
            AuctionInfo a = auctions.findById(auctionId);
            if (a == null) {
                throw new IllegalArgumentException("No such auction");
            }
            if (a.sellerId() != sellerId) {
                throw new IllegalArgumentException("Only the seller can relist");
            }
            if (a.isOpen()) {
                throw new IllegalArgumentException("Auction is still open");
            }
            if (a.isSold()) {
                throw new IllegalArgumentException("Sold items cannot be relisted");
            }
            byte[] jpeg = images.find(auctionId);
            return createAuction(sellerId, new ListingRequest(
                    a.title(), a.description(), a.startingPriceCents(), durationMs,
                    a.category(), a.reserveCents(), a.buyNowCents(), jpeg));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to relist", e);
        }
    }

    public synchronized void watch(long userId, long auctionId) {
        try {
            watches.add(userId, auctionId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to watch", e);
        }
    }

    public synchronized void unwatch(long userId, long auctionId) {
        try {
            watches.remove(userId, auctionId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to unwatch", e);
        }
    }

    private CloseResult closeAuction(long auctionId, boolean force) {
        try {
            AuctionInfo a = auctions.findById(auctionId);
            if (a == null) {
                return CloseResult.missing();
            }
            if (!a.isOpen()) {
                return CloseResult.alreadyClosed(a);
            }
            long now = System.currentTimeMillis();
            if (!force && now < a.endTime()) {
                if (scheduler != null) {
                    scheduler.arm(auctionId, a.endTime());
                }
                return CloseResult.rearmed(a);
            }
            boolean sold = a.leaderId() != null && a.reserveMet();
            SaleRecord sale = db.inTransaction(() -> {
                int n = auctions.close(auctionId, a.version());
                if (n != 1) {
                    throw new OptimisticLockException("close lost the version check");
                }
                if (!sold) {
                    return null;
                }
                return sales.insert(auctionId, a.leaderId(), a.currentPriceCents(), now);
            });
            if (scheduler != null) {
                scheduler.cancel(auctionId);
            }
            AuctionInfo closed = auctions.findById(auctionId);
            String winnerId = sale == null || closed.leaderId() == null ? "" : Long.toString(closed.leaderId());
            String winnerName = sale == null || closed.leaderName() == null ? "" : closed.leaderName();
            String saleId = sale == null ? "" : Long.toString(sale.id());
            broadcast(Protocol.encode(
                    Protocol.AUCTION_CLOSED,
                    Long.toString(auctionId),
                    winnerId,
                    winnerName,
                    Long.toString(closed.currentPriceCents()),
                    saleId
            ));
            broadcast(AuctionInfo.encodeEvent(Protocol.AUCTION_UPDATED, closed));
            if (sale != null && closed.leaderId() != null) {
                sendTo(closed.leaderId(), Protocol.encode(
                        Protocol.YOU_WON,
                        Long.toString(closed.id()),
                        closed.title(),
                        Long.toString(closed.currentPriceCents())
                ));
                ticker("Sold: “" + closed.title() + "” to " + closed.leaderName()
                        + " for " + com.openbid.shared.Money.format(closed.currentPriceCents()));
            } else {
                ticker("Ended unsold: “" + closed.title() + "”"
                        + (closed.hasReserve() && !closed.reserveMet() ? " (reserve not met)" : ""));
            }
            return CloseResult.closed(closed, sale);
        } catch (OptimisticLockException e) {
            try {
                AuctionInfo fresh = auctions.findById(auctionId);
                if (fresh != null && !fresh.isOpen()) {
                    return CloseResult.alreadyClosed(fresh);
                }
                if (fresh != null && scheduler != null) {
                    scheduler.arm(auctionId, fresh.endTime());
                }
                return CloseResult.rearmed(fresh);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to recover from close race", ex);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to close auction", e);
        }
    }

    public synchronized void recoverOpenAuctions() {
        try {
            for (AuctionInfo a : auctions.listOpen()) {
                if (System.currentTimeMillis() >= a.endTime()) {
                    closeAuction(a.id());
                } else if (scheduler != null) {
                    scheduler.arm(a.id(), a.endTime());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to recover open auctions", e);
        }
    }

    /**
     * Demo restart only: give unsold lots a fresh open window and arm the
     * scheduler. Sold lots (sale recorded or {@link AuctionInfo#isSold()}) are
     * left alone. Returns how many listings were reopened.
     */
    synchronized int refreshDemoSchedule() {
        try {
            long now = System.currentTimeMillis();
            List<AuctionInfo> unsold = new ArrayList<>();
            for (AuctionInfo a : auctions.listAll()) {
                if (a.isSold() || sales.findByAuction(a.id()) != null) {
                    continue;
                }
                unsold.add(a);
            }
            unsold.sort((a, b) -> {
                int cmp = Boolean.compare(demoLotNeedsRevival(b, now), demoLotNeedsRevival(a, now));
                return cmp != 0 ? cmp : Long.compare(a.id(), b.id());
            });
            int limit = Math.min(MAX_DEMO_LIVE_LOTS, unsold.size());
            int refreshed = 0;
            for (int i = 0; i < limit; i++) {
                AuctionInfo a = unsold.get(i);
                long duration = DEMO_CLOCK_DURATIONS_MS[i % DEMO_CLOCK_DURATIONS_MS.length];
                long end = now + duration;
                int n = auctions.reopenWithSchedule(a.id(), now, end, a.version());
                if (n != 1) {
                    continue;
                }
                if (scheduler != null) {
                    scheduler.arm(a.id(), end);
                }
                refreshed++;
            }
            return refreshed;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to refresh demo schedule", e);
        }
    }

    private static boolean demoLotNeedsRevival(AuctionInfo a, long now) {
        return !a.isOpen() || a.endTime() <= now;
    }

    static long minNextBid(AuctionInfo a) {
        if (a.leaderId() == null) {
            return a.currentPriceCents();
        }
        return a.currentPriceCents() + MIN_INCREMENT_CENTS;
    }

    /**
     * Places one bid without running proxy resolution. Re-entrant under the
     * BidManager monitor so the proxy loop can call it safely.
     */
    private BidResult placeBidInternal(long auctionId, long bidderId, long amountCents, boolean isProxy) {
        try {
            AuctionInfo a = auctions.findById(auctionId);
            if (a == null) {
                return BidResult.rejected("No such auction", 0);
            }
            if (!a.isOpen()) {
                return BidResult.rejected("Auction is closed", a.currentPriceCents());
            }
            if (a.sellerId() == bidderId) {
                return BidResult.rejected("Seller cannot bid", a.currentPriceCents());
            }
            boolean isLeader = a.leaderId() != null && a.leaderId() == bidderId;
            if (isLeader && !isProxy) {
                return BidResult.rejected("You are already the leading bidder", a.currentPriceCents());
            }
            long min = isLeader ? a.currentPriceCents() + MIN_INCREMENT_CENTS : minNextBid(a);
            if (amountCents < min) {
                return BidResult.rejected("Bid too low", a.currentPriceCents());
            }

            Long previousLeader = a.leaderId();

            long now = System.currentTimeMillis();
            long endTime = a.endTime();
            boolean extended = endTime - now <= ANTI_SNIPE_WINDOW_MS;
            if (extended) {
                endTime = now + ANTI_SNIPE_WINDOW_MS;
            }
            int newBidCount = a.bidCount() + 1;
            int expectedVersion = a.version();
            long finalEnd = endTime;

            BidInfo bid;
            try {
                bid = db.inTransaction(() -> {
                    BidInfo inserted = bids.insert(auctionId, bidderId, amountCents, isProxy, now);
                    int n = auctions.updateAfterBid(
                            auctionId, amountCents, bidderId, finalEnd, newBidCount, expectedVersion, extended);
                    if (n != 1) {
                        throw new OptimisticLockException("stale auction version");
                    }
                    return inserted;
                });
            } catch (OptimisticLockException e) {
                AuctionInfo fresh = auctions.findById(auctionId);
                long price = fresh == null ? 0 : fresh.currentPriceCents();
                return BidResult.rejected("Bid lost a race, try again", price);
            }

            AuctionInfo updated = auctions.findById(auctionId);
            if (extended && scheduler != null) {
                scheduler.arm(auctionId, updated.endTime());
            }
            broadcastNewBid(bid, updated, extended, previousLeader);
            return BidResult.accepted(bid, updated, extended);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to place bid", e);
        }
    }

    /**
     * Classical proxy outcome without bid-feed spam: jump to the deciding
     * amounts (loser's maximum, then winner one increment above).
     */
    private void resolveProxies(long auctionId) {
        for (int i = 0; i < 32; i++) {
            AuctionInfo a;
            List<ProxyBid> all;
            try {
                a = auctions.findById(auctionId);
                if (a == null || !a.isOpen()) {
                    return;
                }
                all = proxies.listByAuction(auctionId);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load proxies", e);
            }

            long minNext = minNextBid(a);
            ProxyBid challenger = strongestChallenger(all, a.leaderId(), minNext);
            if (challenger == null) {
                return;
            }

            ProxyBid leaderProxy = findProxy(all, a.leaderId());
            if (a.leaderId() == null) {
                long price = Math.min(challenger.maxCents(), a.currentPriceCents());
                BidResult r = placeBidInternal(auctionId, challenger.bidderId(), price, true);
                if (!r.accepted()) {
                    return;
                }
                continue;
            }

            long defense = leaderProxy == null ? a.currentPriceCents() : leaderProxy.maxCents();

            if (challenger.maxCents() > defense) {
                long newPrice = Math.max(minNext, defense + MIN_INCREMENT_CENTS);
                if (newPrice > challenger.maxCents()) {
                    newPrice = challenger.maxCents();
                }
                BidResult r = placeBidInternal(auctionId, challenger.bidderId(), newPrice, true);
                if (!r.accepted()) {
                    return;
                }
            } else if (challenger.maxCents() < defense) {
                BidResult c = placeBidInternal(auctionId, challenger.bidderId(), challenger.maxCents(), true);
                if (!c.accepted()) {
                    return;
                }
                AuctionInfo after = c.auction();
                long defendPrice = Math.min(defense, challenger.maxCents() + MIN_INCREMENT_CENTS);
                if (defendPrice > after.currentPriceCents()) {
                    placeBidInternal(auctionId, a.leaderId(), defendPrice, true);
                }
            } else {
                // Equal maxima: earlier proxy (the current leader, who set first) keeps the lead.
                long target = challenger.maxCents();
                if (leaderProxy != null && target > a.currentPriceCents()) {
                    long defendPrice = Math.min(defense, Math.max(minNext, target));
                    if (defendPrice > a.currentPriceCents()) {
                        placeBidInternal(auctionId, a.leaderId(), defendPrice, true);
                    }
                }
                return;
            }
        }
    }

    private static ProxyBid strongestChallenger(List<ProxyBid> all, Long leaderId, long minNext) {
        ProxyBid best = null;
        for (ProxyBid p : all) {
            if (leaderId != null && p.bidderId() == leaderId) {
                continue;
            }
            if (p.maxCents() < minNext) {
                continue;
            }
            if (best == null
                    || p.maxCents() > best.maxCents()
                    || (p.maxCents() == best.maxCents() && p.createdAt() < best.createdAt())) {
                best = p;
            }
        }
        return best;
    }

    private static ProxyBid findProxy(List<ProxyBid> all, Long userId) {
        if (userId == null) {
            return null;
        }
        for (ProxyBid p : all) {
            if (p.bidderId() == userId) {
                return p;
            }
        }
        return null;
    }

    private void broadcastNewBid(BidInfo bid, AuctionInfo updated, boolean extended, Long previousLeader) {
        broadcast(Protocol.encode(
                Protocol.NEW_BID,
                Long.toString(bid.id()),
                Long.toString(bid.auctionId()),
                Long.toString(bid.bidderId()),
                bid.bidderName() == null ? "" : bid.bidderName(),
                Long.toString(bid.amountCents()),
                bid.proxy() ? "1" : "0",
                Long.toString(bid.createdAt())
        ));
        broadcast(AuctionInfo.encodeEvent(Protocol.AUCTION_UPDATED, updated));
        ticker(bid.bidderName() + " bid " + com.openbid.shared.Money.format(bid.amountCents())
                + " on “" + updated.title() + "”" + (bid.proxy() ? " (auto)" : ""));
        if (extended) {
            broadcast(Protocol.encode(
                    Protocol.AUCTION_EXTENDED,
                    Long.toString(updated.id()),
                    Long.toString(updated.endTime())
            ));
            ticker("Anti-snipe: “" + updated.title() + "” extended +30s");
        }
        if (previousLeader != null && previousLeader != bid.bidderId()) {
            sendTo(previousLeader, Protocol.encode(
                    Protocol.YOU_OUTBID,
                    Long.toString(updated.id()),
                    updated.title(),
                    Long.toString(updated.currentPriceCents()),
                    bid.bidderName() == null ? "" : bid.bidderName()
            ));
        }
        try {
            for (Long watcher : watches.listWatcherIds(updated.id())) {
                if (watcher == bid.bidderId()) {
                    continue;
                }
                sendTo(watcher, Protocol.encode(
                        Protocol.WATCH_ALERT,
                        Long.toString(updated.id()),
                        updated.title(),
                        "New bid " + com.openbid.shared.Money.format(bid.amountCents())
                ));
            }
        } catch (Exception ignored) {
            // watchers are best-effort
        }
    }

    private void ticker(String text) {
        broadcast(Protocol.encode(Protocol.TICKER, text));
    }

    private void sendTo(long userId, String message) {
        if (server != null) {
            server.sendTo(userId, message);
        }
    }

    private void broadcast(String message) {
        if (server != null) {
            server.broadcast(message);
        }
    }

    public UserRecord requireUser(long id) {
        try {
            UserRecord u = users.findById(id);
            if (u == null) {
                throw new IllegalArgumentException("Unknown user");
            }
            return u;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

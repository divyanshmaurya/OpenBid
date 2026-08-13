package com.openbid.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.openbid.db.Database;
import com.openbid.db.OptimisticLockException;
import com.openbid.security.PasswordHasher;
import com.openbid.shared.AuctionInfo;
import com.openbid.shared.BidInfo;

class BidManagerTest {

    private Database db;
    private BidManager bm;
    private byte[] salt;
    private byte[] hash;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.openInMemory();
        bm = new BidManager(db);
        salt = PasswordHasher.randomSalt();
        hash = PasswordHasher.hash("secret12".toCharArray(), salt);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private long user(String name) throws Exception {
        return bm.users().insert(name, salt, hash);
    }

    private AuctionInfo listed(long sellerId) {
        return bm.createAuction(sellerId, "Test Item", "A thing", 1000, 60_000);
    }

    private void expire(long auctionId) throws Exception {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE auctions SET end_time = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis() - 1);
            ps.setLong(2, auctionId);
            ps.executeUpdate();
        }
    }

    @Test
    void listItemAppearsOpen() throws Exception {
        long seller = user("seller");
        AuctionInfo a = listed(seller);
        assertTrue(a.isOpen());
        assertEquals("Test Item", a.title());
        assertEquals(1000, a.currentPriceCents());
        assertNull(a.leaderId());
        assertEquals(1, bm.auctions().listAll().size());
    }

    @Test
    void uploadedPhotoIsStoredAndReadableUnderLock() throws Exception {
        long seller = user("seller");
        byte[] jpeg = new byte[] { (byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4 };
        AuctionInfo a = bm.createAuction(seller, new ListingRequest(
                "Photo lot", "desc", 1000, 60_000, "Other", 0, 0, jpeg));
        assertTrue(a.hasImage());
        byte[] loaded = bm.imageJpeg(a.id());
        assertNotNull(loaded);
        assertEquals(jpeg.length, loaded.length);
        assertEquals(jpeg[0], loaded[0]);
        assertEquals(jpeg[jpeg.length - 1], loaded[loaded.length - 1]);
    }

    @Test
    void firstBidAtStartingPrice() throws Exception {
        long seller = user("seller");
        long bob = user("bob");
        AuctionInfo a = listed(seller);
        BidResult r = bm.placeBid(a.id(), bob, 1000);
        assertTrue(r.accepted(), r.reason());
        assertEquals(1000, r.auction().currentPriceCents());
        assertEquals(bob, r.auction().leaderId());
    }

    @Test
    void rejectTooLow() throws Exception {
        long seller = user("seller");
        long bob = user("bob");
        long carol = user("carol");
        AuctionInfo a = listed(seller);
        assertTrue(bm.placeBid(a.id(), bob, 1000).accepted());
        BidResult r = bm.placeBid(a.id(), carol, 1050);
        assertFalse(r.accepted());
        assertEquals("Bid too low", r.reason());
        assertEquals(1000, r.currentPriceCents());
    }

    @Test
    void rejectSeller() throws Exception {
        long seller = user("seller");
        AuctionInfo a = listed(seller);
        BidResult r = bm.placeBid(a.id(), seller, 2000);
        assertFalse(r.accepted());
        assertEquals("Seller cannot bid", r.reason());
    }

    @Test
    void rejectSelfOutbid() throws Exception {
        long seller = user("seller");
        long bob = user("bob");
        AuctionInfo a = listed(seller);
        assertTrue(bm.placeBid(a.id(), bob, 1000).accepted());
        BidResult r = bm.placeBid(a.id(), bob, 2000);
        assertFalse(r.accepted());
        assertEquals("You are already the leading bidder", r.reason());
    }

    @Test
    void antiSnipeExtendsEndTime() throws Exception {
        long seller = user("seller");
        long bob = user("bob");
        AuctionInfo a = bm.createAuction(seller, "Snipe me", "d", 1000, 10_000);
        long originalEnd = a.endTime();
        BidResult r = bm.placeBid(a.id(), bob, 1000);
        assertTrue(r.accepted());
        assertTrue(r.extended());
        assertTrue(r.auction().endTime() > originalEnd);
        long remaining = r.auction().endTime() - System.currentTimeMillis();
        assertTrue(remaining >= 29_000 && remaining <= 31_000, "remaining=" + remaining);
    }

    @Test
    void closeRecordsSale() throws Exception {
        long seller = user("seller");
        long bob = user("bob");
        AuctionInfo a = listed(seller);
        assertTrue(bm.placeBid(a.id(), bob, 1500).accepted());
        expire(a.id());
        CloseResult close = bm.closeAuction(a.id());
        assertEquals(CloseResult.Kind.CLOSED, close.kind());
        assertNotNull(close.sale());
        assertEquals(bob, close.sale().buyerId());
        assertEquals(1500, close.sale().amountCents());
        assertEquals("CLOSED", close.auction().status());
    }

    @Test
    void closeTwiceRecordsOneSale() throws Exception {
        long seller = user("seller");
        long bob = user("bob");
        AuctionInfo a = listed(seller);
        assertTrue(bm.placeBid(a.id(), bob, 1500).accepted());
        expire(a.id());
        assertEquals(CloseResult.Kind.CLOSED, bm.closeAuction(a.id()).kind());
        CloseResult second = bm.closeAuction(a.id());
        assertEquals(CloseResult.Kind.ALREADY_CLOSED, second.kind());
        assertEquals(1, bm.sales().count());
    }

    @Test
    void bidAfterCloseRejected() throws Exception {
        long seller = user("seller");
        long bob = user("bob");
        long carol = user("carol");
        AuctionInfo a = listed(seller);
        assertTrue(bm.placeBid(a.id(), bob, 1500).accepted());
        expire(a.id());
        bm.closeAuction(a.id());
        BidResult r = bm.placeBid(a.id(), carol, 5000);
        assertFalse(r.accepted());
        assertEquals("Auction is closed", r.reason());
    }

    @Test
    void unsoldWhenNoBids() throws Exception {
        long seller = user("seller");
        AuctionInfo a = listed(seller);
        expire(a.id());
        CloseResult close = bm.closeAuction(a.id());
        assertEquals(CloseResult.Kind.CLOSED, close.kind());
        assertNull(close.sale());
        assertEquals(0, bm.sales().count());
    }

    @Test
    void proxyHighestWinsAtIncrementOverSecond() throws Exception {
        long seller = user("seller");
        long alice = user("alice");
        long bob = user("bob");
        AuctionInfo a = listed(seller);
        assertTrue(bm.setProxyBid(a.id(), alice, 5000).accepted());
        assertTrue(bm.setProxyBid(a.id(), bob, 3000).accepted());
        AuctionInfo finalA = bm.auctions().findById(a.id());
        assertEquals(alice, finalA.leaderId());
        assertEquals(3100, finalA.currentPriceCents());
        List<BidInfo> bids = bm.bids().listByAuction(a.id());
        assertFalse(bids.isEmpty());
        assertTrue(bids.stream().anyMatch(BidInfo::proxy));
    }

    @Test
    void proxySellerRejected() throws Exception {
        long seller = user("seller");
        AuctionInfo a = listed(seller);
        BidResult r = bm.setProxyBid(a.id(), seller, 9000);
        assertFalse(r.accepted());
        assertEquals("Seller cannot bid", r.reason());
    }

    @Test
    void reserveNotMetEndsUnsold() throws Exception {
        long seller = user("seller");
        long bob = user("bob");
        AuctionInfo a = bm.createAuction(seller, new ListingRequest(
                "Reserve lot", "d", 1000, 60_000, "Other", 5000, 0, null));
        assertTrue(bm.placeBid(a.id(), bob, 1500).accepted());
        expire(a.id());
        CloseResult close = bm.closeAuction(a.id());
        assertEquals(CloseResult.Kind.CLOSED, close.kind());
        assertNull(close.sale());
        assertFalse(close.auction().isSold());
        assertEquals(0, bm.sales().count());
    }

    @Test
    void buyNowClosesAndRecordsSale() throws Exception {
        long seller = user("seller");
        long bob = user("bob");
        AuctionInfo a = bm.createAuction(seller, new ListingRequest(
                "BIN lot", "d", 1000, 60_000, "Electronics", 0, 4000, null));
        BidResult r = bm.buyNow(a.id(), bob);
        assertTrue(r.accepted(), r.reason());
        AuctionInfo closed = bm.auctions().findById(a.id());
        assertFalse(closed.isOpen());
        assertTrue(closed.isSold());
        assertEquals(bob, closed.leaderId());
        assertEquals(4000, closed.currentPriceCents());
        assertEquals(1, bm.sales().count());
    }

    @Test
    void buyNowStillAvailableAfterALowerBid() throws Exception {
        long seller = user("seller");
        long bob = user("bob");
        long cara = user("cara");
        AuctionInfo a = bm.createAuction(seller, new ListingRequest(
                "BIN after bid", "d", 1000, 60_000, "Electronics", 0, 4000, null));
        assertTrue(bm.placeBid(a.id(), bob, 1500).accepted());
        AuctionInfo mid = bm.auctions().findById(a.id());
        assertTrue(mid.buyNowAvailable());
        BidResult r = bm.buyNow(a.id(), cara);
        assertTrue(r.accepted(), r.reason());
        AuctionInfo closed = bm.auctions().findById(a.id());
        assertTrue(closed.isSold());
        assertEquals(cara, closed.leaderId());
        assertEquals(4000, closed.currentPriceCents());
    }

    @Test
    void refreshDemoListingsReopensUnsoldLeavesSold() throws Exception {
        long seller = user("seller");
        long bob = user("bob");

        AuctionInfo openExpired = listed(seller);
        expire(openExpired.id());

        AuctionInfo closedUnsold = listed(seller);
        expire(closedUnsold.id());
        bm.closeAuction(closedUnsold.id());

        AuctionInfo reserveUnsold = bm.createAuction(seller, new ListingRequest(
                "Reserve lot", "d", 1000, 60_000, "Other", 5000, 0, null));
        assertTrue(bm.placeBid(reserveUnsold.id(), bob, 1500).accepted());
        expire(reserveUnsold.id());
        bm.closeAuction(reserveUnsold.id());
        assertFalse(bm.auctions().findById(reserveUnsold.id()).isSold());

        AuctionInfo sold = listed(seller);
        assertTrue(bm.placeBid(sold.id(), bob, 1500).accepted());
        expire(sold.id());
        bm.closeAuction(sold.id());
        assertTrue(bm.auctions().findById(sold.id()).isSold());
        long soldEnd = bm.auctions().findById(sold.id()).endTime();

        AuctionInfo extra1 = listed(seller);
        expire(extra1.id());
        AuctionInfo extra2 = listed(seller);
        expire(extra2.id());
        AuctionInfo extra3 = listed(seller);
        expire(extra3.id());

        int n = ServerMain.refreshDemoListings(bm);
        assertTrue(n >= 6, "refreshed=" + n);
        bm.recoverOpenAuctions();

        long[] unsoldIds = {
                openExpired.id(), closedUnsold.id(), reserveUnsold.id(),
                extra1.id(), extra2.id(), extra3.id()
        };
        Set<Long> endTimes = new HashSet<>();
        long now = System.currentTimeMillis();
        for (long id : unsoldIds) {
            AuctionInfo fresh = bm.auctions().findById(id);
            assertTrue(fresh.isOpen(), "lot " + id + " should be OPEN");
            assertTrue(fresh.endTime() > now, "lot " + id + " endTime=" + fresh.endTime());
            endTimes.add(fresh.endTime());
        }
        assertTrue(endTimes.size() > 1, "demo clocks should be staggered");

        AuctionInfo stillSold = bm.auctions().findById(sold.id());
        assertFalse(stillSold.isOpen());
        assertTrue(stillSold.isSold());
        assertEquals(soldEnd, stillSold.endTime());
        assertEquals(1, bm.sales().count());
    }

    @Test
    void relistUnsoldCreatesNewOpenLot() throws Exception {
        long seller = user("seller");
        AuctionInfo a = listed(seller);
        expire(a.id());
        bm.closeAuction(a.id());
        AuctionInfo copy = bm.relist(seller, a.id(), 60_000);
        assertTrue(copy.isOpen());
        assertEquals(a.title(), copy.title());
        assertNotEquals(a.id(), copy.id());
    }

    @Test
    void versionMismatchRollsBackInsertedBid() throws Exception {
        long seller = user("seller");
        long bob = user("bob");
        AuctionInfo a = listed(seller);
        Exception thrown = assertThrows(Exception.class, () -> db.inTransaction(() -> {
            bm.bids().insert(a.id(), bob, 1000, false, System.currentTimeMillis());
            int n = bm.auctions().updateAfterBid(
                    a.id(), 1000, bob, a.endTime(), 1, a.version() + 99, false);
            if (n != 1) {
                throw new OptimisticLockException("stale auction version");
            }
            return null;
        }));
        assertTrue(thrown instanceof OptimisticLockException
                || thrown.getCause() instanceof OptimisticLockException);
        assertEquals(0, bm.bids().countByAuction(a.id()));
        assertEquals(a.version(), bm.auctions().findById(a.id()).version());
        assertEquals(1000, bm.auctions().findById(a.id()).currentPriceCents());
    }

    @Test
    @Timeout(30)
    void concurrentBidsSingleWinner() throws Exception {
        int threads = 16;
        int perThread = 25;
        long seller = user("seller");
        long[] bidders = new long[threads];
        for (int i = 0; i < threads; i++) {
            bidders[i] = user("u" + i);
        }
        AuctionInfo a = listed(seller);

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        long amount = 1000 + BidManager.MIN_INCREMENT_CENTS * (i * threads + tid + 1);
                        BidResult r = bm.placeBid(a.id(), bidders[tid], amount);
                        if (r.accepted()) {
                            accepted.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(25, TimeUnit.SECONDS), "workers did not finish");
        pool.shutdownNow();

        assertEquals(threads * perThread, accepted.get() + rejected.get());
        List<BidInfo> bids = bm.bids().listByAuction(a.id());
        assertEquals(accepted.get(), bids.size(), "recorded==accepted");
        for (int i = 1; i < bids.size(); i++) {
            assertTrue(bids.get(i).amountCents() > bids.get(i - 1).amountCents(),
                    "prices must be strictly increasing");
        }
        AuctionInfo finalA = bm.auctions().findById(a.id());
        assertNotNull(finalA.leaderId());
        if (!bids.isEmpty()) {
            BidInfo top = bids.get(bids.size() - 1);
            assertEquals(top.amountCents(), finalA.currentPriceCents());
            assertEquals(top.bidderId(), finalA.leaderId());
        }
    }
}

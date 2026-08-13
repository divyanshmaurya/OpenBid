package com.openbid.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One scheduled close task per open auction. Anti-snipe cancels and re-arms.
 * The close callback goes through {@link BidManager#closeAuction} so expiry
 * cannot interleave with a bid that is still in flight.
 */
public final class AuctionScheduler {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "auction-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private BidManager bidManager;

    public void setBidManager(BidManager bidManager) {
        this.bidManager = bidManager;
    }

    public void arm(long auctionId, long endTimeMs) {
        cancel(auctionId);
        long delay = Math.max(0, endTimeMs - System.currentTimeMillis());
        ScheduledFuture<?> future = executor.schedule(() -> {
            BidManager bm = bidManager;
            if (bm != null) {
                bm.closeAuction(auctionId);
            }
        }, delay, TimeUnit.MILLISECONDS);
        tasks.put(auctionId, future);
    }

    public void cancel(long auctionId) {
        ScheduledFuture<?> future = tasks.remove(auctionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        tasks.clear();
    }
}

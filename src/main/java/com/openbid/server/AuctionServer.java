package com.openbid.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Accept loop on the calling thread; each client is handed to a fixed pool of
 * 64 handler threads. Broadcasts go to every logged-in connection.
 */
public final class AuctionServer {

    private final int port;
    private final BidManager bidManager;
    private final AuctionScheduler scheduler;
    private final ExecutorService pool;
    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    public AuctionServer(int port, BidManager bidManager, AuctionScheduler scheduler) {
        this.port = port;
        this.bidManager = bidManager;
        this.scheduler = scheduler;
        this.pool = Executors.newFixedThreadPool(64, r -> {
            Thread t = new Thread(r, "openbid-handler");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((th, ex) -> {
                System.err.println("Handler thread crashed: " + ex);
                ex.printStackTrace();
            });
            return t;
        });
    }

    public BidManager bidManager() {
        return bidManager;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running.set(true);
        System.out.println("OpenBid server listening on port " + port);
        try {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setTcpNoDelay(true);
                    ClientHandler handler = new ClientHandler(socket, this, bidManager);
                    clients.add(handler);
                    pool.submit(handler);
                    System.out.println("Accepted " + socket.getRemoteSocketAddress()
                            + " (" + clients.size() + " connections)");
                } catch (SocketException e) {
                    if (running.get()) {
                        System.err.println("Accept failed: " + e.getMessage());
                    }
                }
            }
        } finally {
            shutdown();
        }
    }

    public void remove(ClientHandler handler) {
        clients.remove(handler);
    }

    public void broadcast(String message) {
        for (ClientHandler handler : clients) {
            if (handler.isLoggedIn()) {
                handler.send(message);
            }
        }
    }

    public void sendTo(long userId, String message) {
        for (ClientHandler handler : clients) {
            if (handler.isLoggedIn() && handler.userId() == userId) {
                handler.send(message);
            }
        }
    }

    public void shutdown() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // shutting down
        }
        for (ClientHandler handler : clients) {
            handler.closeQuietly();
        }
        clients.clear();
        pool.shutdownNow();
        scheduler.shutdown();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

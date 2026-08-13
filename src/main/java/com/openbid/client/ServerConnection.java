package com.openbid.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

/**
 * Owns the TCP socket and a dedicated listener thread. Incoming lines are
 * never applied to Swing components here — they are posted to the EDT with
 * {@link SwingUtilities#invokeLater}.
 */
public final class ServerConnection {

    private final Object writeLock = new Object();
    private Socket socket;
    private PrintWriter out;
    private Thread listener;
    private volatile boolean open;
    private volatile Consumer<String> onMessage = line -> {};
    private volatile Runnable onDisconnect = () -> {};

    public void setOnMessage(Consumer<String> onMessage) {
        this.onMessage = onMessage == null ? line -> {} : onMessage;
    }

    public void setOnDisconnect(Runnable onDisconnect) {
        this.onDisconnect = onDisconnect == null ? () -> {} : onDisconnect;
    }

    public void connect(String host, int port) throws IOException {
        close();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5_000);
        s.setTcpNoDelay(true);
        this.socket = s;
        this.out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
        this.open = true;
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        listener = new Thread(() -> listen(in), "openbid-listener");
        listener.setDaemon(true);
        listener.start();
    }

    public void send(String message) {
        synchronized (writeLock) {
            if (out != null) {
                out.println(message);
                out.flush();
            }
        }
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // closing
        }
        socket = null;
        out = null;
    }

    private void listen(BufferedReader in) {
        try {
            String line;
            while (open && (line = in.readLine()) != null) {
                String copy = line;
                Consumer<String> handler = onMessage;
                SwingUtilities.invokeLater(() -> handler.accept(copy));
            }
        } catch (IOException ignored) {
            // disconnect
        } finally {
            boolean wasOpen = open;
            open = false;
            if (wasOpen) {
                Runnable hook = onDisconnect;
                SwingUtilities.invokeLater(hook);
            }
        }
    }
}

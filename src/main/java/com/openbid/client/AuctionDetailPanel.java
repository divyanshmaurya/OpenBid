package com.openbid.client;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

import com.openbid.server.BidManager;
import com.openbid.shared.AuctionInfo;
import com.openbid.shared.BidInfo;
import com.openbid.shared.Money;
import com.openbid.shared.Protocol;

final class AuctionDetailPanel extends JPanel {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int PHOTO_W = 160;
    private static final int PHOTO_H = 90;

    private final ServerConnection connection;
    private final long currentUserId;
    private final Consumer<String> statusHook;

    private final JLabel photoLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel titleLabel = new JLabel("Select an auction");
    private final JLabel sellerLabel = new JLabel(" ");
    private final JLabel descriptionLabel = new JLabel(" ");
    private final JLabel priceLabel = new JLabel(" ");
    private final JLabel leaderLabel = new JLabel(" ");
    private final JLabel countdownLabel = new JLabel(" ");
    private final JLabel reserveLabel = new JLabel(" ");
    private final JLabel extendedChip = new JLabel(" ");
    private final DefaultListModel<BidInfo> bidModel = new DefaultListModel<>();
    private final JList<BidInfo> bidList = new JList<>(bidModel);
    private final JTextField bidField = new JTextField(10);
    private final JTextField proxyField = new JTextField(10);
    private final JButton bidButton = new JButton("Place bid");
    private final JButton proxyButton = new JButton("Set proxy max");
    private final JButton buyNowButton = new JButton("Buy It Now");
    private final JButton watchButton = new JButton("Watch");
    private final JButton relistButton = new JButton("Relist");
    private final JButton receiptButton = new JButton("Save receipt");
    private final JLabel messageLabel = new JLabel(" ");

    private AuctionInfo auction;
    private boolean watching;
    private Runnable onReceipt;

    AuctionDetailPanel(ServerConnection connection, long currentUserId, Consumer<String> statusHook) {
        this.connection = connection;
        this.currentUserId = currentUserId;
        this.statusHook = statusHook;
        setLayout(new BorderLayout(8, 6));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD5D8DC)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        setMinimumSize(new Dimension(280, 0));

        photoLabel.setPreferredSize(new Dimension(PHOTO_W, PHOTO_H));
        photoLabel.setMinimumSize(new Dimension(PHOTO_W, PHOTO_H));
        photoLabel.setMaximumSize(new Dimension(PHOTO_W, PHOTO_H));
        photoLabel.setOpaque(true);
        photoLabel.setBackground(UiTheme.SURFACE);
        photoLabel.setBorder(BorderFactory.createLineBorder(new Color(0xD5D8DC)));

        titleLabel.setFont(UiTheme.headingFont().deriveFont(14f));
        titleLabel.setForeground(UiTheme.NAVY);
        descriptionLabel.setForeground(UiTheme.MUTED);
        descriptionLabel.setFont(descriptionLabel.getFont().deriveFont(11f));
        sellerLabel.setForeground(UiTheme.MUTED);
        sellerLabel.setFont(sellerLabel.getFont().deriveFont(11f));
        priceLabel.setFont(UiTheme.priceFont().deriveFont(16f));
        priceLabel.setForeground(UiTheme.TEAL);
        countdownLabel.setFont(UiTheme.priceFont().deriveFont(16f));
        extendedChip.setForeground(UiTheme.SNIPE_RED);
        extendedChip.setFont(UiTheme.headingFont().deriveFont(11f));
        leaderLabel.setFont(leaderLabel.getFont().deriveFont(11f));
        reserveLabel.setFont(reserveLabel.getFont().deriveFont(11f));
        leaderLabel.setAlignmentX(LEFT_ALIGNMENT);
        reserveLabel.setAlignmentX(LEFT_ALIGNMENT);
        extendedChip.setAlignmentX(LEFT_ALIGNMENT);
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        sellerLabel.setAlignmentX(LEFT_ALIGNMENT);
        descriptionLabel.setAlignmentX(LEFT_ALIGNMENT);

        JPanel photoWrap = new JPanel(new BorderLayout());
        photoWrap.setOpaque(false);
        photoWrap.add(photoLabel, BorderLayout.NORTH);

        JPanel priceTime = new JPanel(new GridLayout(1, 2, 12, 2));
        priceTime.setOpaque(false);
        priceTime.setAlignmentX(LEFT_ALIGNMENT);
        priceTime.add(statColumn("Current price", priceLabel));
        priceTime.add(statColumn("Time remaining", countdownLabel));
        priceTime.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int width = priceTime.getWidth();
                boolean stack = width > 0 && width < 220;
                GridLayout layout = (GridLayout) priceTime.getLayout();
                int rows = stack ? 2 : 1;
                int cols = stack ? 1 : 2;
                if (layout.getRows() != rows || layout.getColumns() != cols) {
                    priceTime.setLayout(new GridLayout(rows, cols, 12, 2));
                    priceTime.revalidate();
                }
            }
        });

        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setOpaque(false);
        info.add(titleLabel);
        info.add(Box.createVerticalStrut(2));
        info.add(sellerLabel);
        info.add(descriptionLabel);
        info.add(Box.createVerticalStrut(4));
        info.add(priceTime);
        info.add(Box.createVerticalStrut(2));
        info.add(leaderLabel);
        info.add(reserveLabel);
        info.add(extendedChip);

        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);
        header.add(photoWrap, BorderLayout.WEST);
        header.add(info, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        bidList.setCellRenderer(new BidRenderer());
        bidList.setFixedCellHeight(22);
        JScrollPane feed = new JScrollPane(bidList);
        feed.setBorder(BorderFactory.createTitledBorder("Live bid feed"));
        feed.setPreferredSize(new Dimension(200, 80));
        add(feed, BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridBagLayout());
        actions.setOpaque(false);
        GridBagConstraints a = new GridBagConstraints();
        a.insets = new Insets(2, 0, 2, 6);
        a.anchor = GridBagConstraints.WEST;
        a.gridx = 0;
        a.gridy = 0;
        actions.add(new JLabel("Your bid"), a);
        a.gridx = 1;
        a.fill = GridBagConstraints.HORIZONTAL;
        a.weightx = 1;
        actions.add(bidField, a);
        a.gridx = 2;
        a.weightx = 0;
        a.fill = GridBagConstraints.NONE;
        actions.add(bidButton, a);
        a.gridx = 3;
        actions.add(buyNowButton, a);
        a.gridx = 0;
        a.gridy = 1;
        actions.add(new JLabel("Proxy max"), a);
        a.gridx = 1;
        a.fill = GridBagConstraints.HORIZONTAL;
        a.weightx = 1;
        actions.add(proxyField, a);
        a.gridx = 2;
        a.weightx = 0;
        a.fill = GridBagConstraints.NONE;
        actions.add(proxyButton, a);
        a.gridx = 0;
        a.gridy = 2;
        a.gridwidth = 4;
        JPanel extra = new JPanel();
        extra.setOpaque(false);
        extra.add(watchButton);
        extra.add(relistButton);
        extra.add(receiptButton);
        actions.add(extra, a);
        a.gridy = 3;
        messageLabel.setForeground(UiTheme.SNIPE_RED);
        actions.add(messageLabel, a);

        add(actions, BorderLayout.SOUTH);

        bidButton.addActionListener(e -> submitBid(false));
        proxyButton.addActionListener(e -> submitBid(true));
        bidField.addActionListener(e -> submitBid(false));
        proxyField.addActionListener(e -> submitBid(true));
        buyNowButton.addActionListener(e -> {
            if (auction != null) {
                connection.send(Protocol.encode(Protocol.BUY_NOW, Long.toString(auction.id())));
            }
        });
        watchButton.addActionListener(e -> {
            if (auction == null) {
                return;
            }
            connection.send(Protocol.encode(watching ? Protocol.UNWATCH : Protocol.WATCH,
                    Long.toString(auction.id())));
        });
        relistButton.addActionListener(e -> {
            if (auction != null) {
                connection.send(Protocol.encode(Protocol.RELIST, Long.toString(auction.id()), "600"));
            }
        });
        receiptButton.addActionListener(e -> {
            if (onReceipt != null) {
                onReceipt.run();
            }
        });
        showEmpty();
    }

    void setOnReceipt(Runnable onReceipt) {
        this.onReceipt = onReceipt;
    }

    void setWatching(boolean watching) {
        this.watching = watching;
        watchButton.setText(watching ? "Watching ★" : "Watch");
    }

    void setPhoto(ImageIcon icon) {
        if (icon == null) {
            photoLabel.setIcon(null);
            photoLabel.setText("No photo");
        } else {
            photoLabel.setText("");
            photoLabel.setIcon(icon);
        }
    }

    void show(AuctionInfo info) {
        this.auction = info;
        if (info == null) {
            showEmpty();
            return;
        }
        titleLabel.setText("<html>" + escape(info.title()) + "</html>");
        sellerLabel.setText(info.category() + "  ·  Seller  " + info.sellerName());
        descriptionLabel.setText("<html><body style='width:240px'>"
                + escape(twoLine(info.description())) + "</body></html>");
        priceLabel.setText(Money.format(info.currentPriceCents()));
        if (info.leaderName() == null || info.leaderName().isEmpty()) {
            leaderLabel.setText("No bids yet  ·  starting " + Money.format(info.startingPriceCents()));
        } else {
            leaderLabel.setText("Leading bidder  ·  " + info.leaderName());
        }
        if (info.hasReserve()) {
            if (info.sellerId() == currentUserId) {
                reserveLabel.setText("Reserve " + Money.format(info.reserveCents())
                        + (info.reserveMet() ? "  ·  met" : "  ·  not met"));
            } else {
                reserveLabel.setText(info.reserveMet() ? "Reserve met" : "Reserve not met");
            }
        } else {
            reserveLabel.setText("No reserve");
        }
        extendedChip.setText(info.snipeExtended() ? "Anti-snipe  ·  extended +30 seconds" : " ");
        refreshCountdown();
        boolean canBid = info.isOpen() && info.sellerId() != currentUserId;
        bidButton.setEnabled(canBid);
        proxyButton.setEnabled(canBid);
        bidField.setEnabled(canBid);
        proxyField.setEnabled(canBid);
        boolean showBuyNow = info.buyNowAvailable() && info.sellerId() != currentUserId;
        buyNowButton.setVisible(showBuyNow);
        buyNowButton.setText(showBuyNow ? "Buy It Now  " + Money.format(info.buyNowCents()) : "Buy It Now");
        watchButton.setVisible(true);
        relistButton.setVisible(!info.isOpen() && !info.isSold() && info.sellerId() == currentUserId);
        receiptButton.setVisible(info.isSold() && info.leaderId() != null && info.leaderId() == currentUserId);
        if (!info.isOpen()) {
            messageLabel.setForeground(UiTheme.MUTED);
            if (info.isSold()) {
                messageLabel.setText("Sold to " + info.leaderName() + " for " + Money.format(info.currentPriceCents()));
            } else if (info.hasReserve() && info.leaderId() != null) {
                messageLabel.setText("Ended — reserve was not met. Highest bid did not win.");
            } else {
                messageLabel.setText("This auction ended with no bids.");
            }
        } else if (info.sellerId() == currentUserId) {
            messageLabel.setForeground(UiTheme.MUTED);
            messageLabel.setText("You listed this item — you cannot bid on it.");
        } else {
            messageLabel.setText(" ");
        }
        connection.send(Protocol.encode(Protocol.GET_BIDS, Long.toString(info.id())));
        connection.send(Protocol.encode(Protocol.GET_IMAGE, Long.toString(info.id())));
        revalidate();
        repaint();
    }

    void refreshCountdown() {
        if (auction == null) {
            countdownLabel.setText(" ");
            return;
        }
        if (!auction.isOpen()) {
            countdownLabel.setText("Ended");
            countdownLabel.setForeground(UiTheme.SOLD);
            return;
        }
        long now = System.currentTimeMillis();
        long remaining = auction.endTime() - now;
        countdownLabel.setText(UiTheme.formatRemaining(auction.endTime(), now));
        if (remaining > 0 && remaining <= BidManager.ANTI_SNIPE_WINDOW_MS) {
            countdownLabel.setForeground(UiTheme.SNIPE_RED);
        } else if (auction.endingSoon(now)) {
            countdownLabel.setForeground(new Color(0xB9770E));
        } else {
            countdownLabel.setForeground(UiTheme.NAVY);
        }
    }

    void replaceBids(List<BidInfo> bids) {
        bidModel.clear();
        for (int i = bids.size() - 1; i >= 0; i--) {
            bidModel.addElement(bids.get(i));
        }
    }

    void appendBid(BidInfo bid) {
        if (auction == null || bid.auctionId() != auction.id()) {
            return;
        }
        for (int i = 0; i < bidModel.size(); i++) {
            if (bidModel.get(i).id() == bid.id()) {
                return;
            }
        }
        bidModel.add(0, bid);
    }

    void onBidRejected(String reason) {
        messageLabel.setForeground(UiTheme.SNIPE_RED);
        messageLabel.setText(reason);
    }

    void onProxyOk() {
        messageLabel.setForeground(UiTheme.TEAL);
        messageLabel.setText("Proxy maximum saved. The server will bid for you up to that amount.");
        proxyField.setText("");
    }

    AuctionInfo current() {
        return auction;
    }

    private void showEmpty() {
        auction = null;
        titleLabel.setText("Select an auction");
        sellerLabel.setText("Choose a row to see the photo, live feed and place a bid.");
        descriptionLabel.setText(" ");
        priceLabel.setText(" ");
        leaderLabel.setText(" ");
        countdownLabel.setText(" ");
        reserveLabel.setText(" ");
        extendedChip.setText(" ");
        bidModel.clear();
        setPhoto(null);
        bidButton.setEnabled(false);
        proxyButton.setEnabled(false);
        bidField.setEnabled(false);
        proxyField.setEnabled(false);
        buyNowButton.setVisible(false);
        watchButton.setVisible(false);
        relistButton.setVisible(false);
        receiptButton.setVisible(false);
        messageLabel.setText(" ");
    }

    private void submitBid(boolean proxy) {
        if (auction == null) {
            return;
        }
        JTextField field = proxy ? proxyField : bidField;
        try {
            long cents = Money.parseToCents(field.getText());
            if (proxy) {
                connection.send(Protocol.encode(Protocol.PROXY_BID,
                        Long.toString(auction.id()), Long.toString(cents)));
            } else {
                connection.send(Protocol.encode(Protocol.BID,
                        Long.toString(auction.id()), Long.toString(cents)));
            }
            messageLabel.setForeground(UiTheme.MUTED);
            messageLabel.setText(proxy ? "Sending proxy maximum…" : "Sending bid…");
            if (statusHook != null) {
                statusHook.accept(proxy ? "Proxy sent" : "Bid sent");
            }
        } catch (IllegalArgumentException ex) {
            messageLabel.setForeground(UiTheme.SNIPE_RED);
            messageLabel.setText(ex.getMessage());
        }
    }

    private static JPanel statColumn(String caption, JLabel value) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setAlignmentX(LEFT_ALIGNMENT);
        JLabel cap = label(caption);
        cap.setAlignmentX(LEFT_ALIGNMENT);
        value.setAlignmentX(LEFT_ALIGNMENT);
        col.add(cap);
        col.add(Box.createVerticalStrut(1));
        col.add(value);
        return col;
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(UiTheme.MUTED);
        l.setFont(l.getFont().deriveFont(11f));
        return l;
    }

    private static String twoLine(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (one.length() <= 140) {
            return one;
        }
        return one.substring(0, 137) + "…";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    private static final class BidRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof BidInfo bid) {
                String time = TIME.format(Instant.ofEpochMilli(bid.createdAt()));
                String auto = bid.proxy() ? "   auto" : "";
                setText(time + "   " + bid.bidderName() + "   " + Money.format(bid.amountCents()) + auto);
                setFont(UiTheme.monoFont());
                if (!isSelected && bid.proxy()) {
                    setForeground(UiTheme.TEAL);
                }
            }
            return this;
        }
    }
}

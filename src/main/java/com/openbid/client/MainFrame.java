package com.openbid.client;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;

import com.openbid.server.BidManager;
import com.openbid.shared.AuctionInfo;
import com.openbid.shared.BidInfo;
import com.openbid.shared.Categories;
import com.openbid.shared.Money;
import com.openbid.shared.Protocol;

final class MainFrame extends JFrame {

    private enum View { ALL, ENDING_SOON, WATCHING, MY_LISTINGS, MY_BIDS, WON }

    private final ServerConnection connection;
    private final long userId;
    private final String username;
    private final Runnable onLogout;

    private final List<AuctionInfo> allAuctions = new ArrayList<>();
    private final Set<Long> watchIds = new HashSet<>();
    private final Set<Long> myBidIds = new HashSet<>();
    private final Map<Long, ImageIcon> photos = new HashMap<>();
    private final AuctionTableModel tableModel = new AuctionTableModel();
    private final JTable table = new JTable(tableModel);
    private final AuctionDetailPanel detail;
    private final JLabel statusLabel = new JLabel("Connected");
    private final JLabel toastLabel = new JLabel(" ");
    private final DefaultListModel<String> tickerModel = new DefaultListModel<>();
    private final JTextField searchField = new JTextField(18);
    private final JComboBox<String> categoryBox = new JComboBox<>();
    private final JList<String> viewList = new JList<>(new String[] {
            "All auctions", "Ending soon", "Watching", "My listings", "My bids", "Won"
    });
    private final Timer countdownTimer;
    private View view = View.ALL;
    private Long pendingSelectId;

    MainFrame(ServerConnection connection, long userId, String username, Runnable onLogout) {
        super("OpenBid  ·  " + username);
        this.connection = connection;
        this.userId = userId;
        this.username = username;
        this.onLogout = onLogout;
        this.detail = new AuctionDetailPanel(connection, userId, this::setStatus);
        this.detail.setOnReceipt(this::saveReceipt);

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 640));
        setSize(1280, 760);

        JPanel root = new JPanel(new BorderLayout());
        root.add(buildHeader(), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        center.add(buildFilters(), BorderLayout.NORTH);

        viewList.setSelectedIndex(0);
        viewList.setFixedCellHeight(28);
        viewList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                view = View.values()[Math.max(0, viewList.getSelectedIndex())];
                applyFilter();
            }
        });
        JScrollPane nav = new JScrollPane(viewList);
        nav.setPreferredSize(new Dimension(150, 120));
        nav.setBorder(BorderFactory.createTitledBorder("Views"));

        configureTable();
        JScrollPane tableScroll = new JScrollPane(table);
        detail.setMinimumSize(new Dimension(320, 200));
        JSplitPane tableAndDetail = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, detail);
        tableAndDetail.setResizeWeight(0.55);
        JSplitPane withNav = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, nav, tableAndDetail);
        withNav.setDividerLocation(160);
        center.add(withNav, BorderLayout.CENTER);

        JList<String> ticker = new JList<>(tickerModel);
        ticker.setVisibleRowCount(2);
        ticker.setFont(UiTheme.monoFont());
        JScrollPane tickerScroll = new JScrollPane(ticker);
        tickerScroll.setBorder(BorderFactory.createTitledBorder("Live activity"));
        tickerScroll.setPreferredSize(new Dimension(100, 64));
        center.add(tickerScroll, BorderLayout.SOUTH);

        toastLabel.setOpaque(true);
        toastLabel.setBackground(new Color(0x148F77));
        toastLabel.setForeground(Color.WHITE);
        toastLabel.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        toastLabel.setVisible(false);

        JPanel south = new JPanel(new BorderLayout());
        south.add(toastLabel, BorderLayout.NORTH);
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        statusLabel.setForeground(UiTheme.MUTED);
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(new JLabel("Live  ·  no refresh needed"), BorderLayout.EAST);
        south.add(statusBar, BorderLayout.SOUTH);

        root.add(center, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        setLocationRelativeTo(null);

        connection.setOnMessage(this::onMessage);
        connection.setOnDisconnect(this::onDisconnect);
        connection.send(Protocol.encode(Protocol.GET_AUCTIONS));
        connection.send(Protocol.encode(Protocol.GET_WATCHES));
        connection.send(Protocol.encode(Protocol.GET_MY_BIDS));

        countdownTimer = new Timer(1000, e -> {
            tableModel.tickCountdowns();
            detail.refreshCountdown();
            if (view == View.ENDING_SOON) {
                applyFilter();
            }
        });
        countdownTimer.start();

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                countdownTimer.stop();
                connection.close();
            }
        });
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(UiTheme.NAVY);
        header.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        JLabel brand = new JLabel("OpenBid");
        brand.setFont(UiTheme.titleFont());
        brand.setForeground(Color.WHITE);
        JLabel who = new JLabel("Signed in as  " + username);
        who.setForeground(new Color(0xD4E6F1));
        who.setFont(new Font("SansSerif", Font.PLAIN, 13));
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(brand, BorderLayout.NORTH);
        left.add(who, BorderLayout.SOUTH);

        JButton theme = new JButton(UiTheme.isDark() ? "Light mode" : "Dark mode");
        theme.addActionListener(e -> {
            UiTheme.setDark(!UiTheme.isDark());
            SwingUtilities.updateComponentTreeUI(this);
            theme.setText(UiTheme.isDark() ? "Light mode" : "Dark mode");
        });
        JButton sell = new JButton("Sell an item");
        sell.addActionListener(e -> new ListItemDialog(this, connection).setVisible(true));
        JButton logout = new JButton("Log out");
        logout.addActionListener(e -> logout());
        JPanel right = new JPanel();
        right.setOpaque(false);
        right.add(theme);
        right.add(sell);
        right.add(logout);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JPanel buildFilters() {
        JPanel bar = new JPanel();
        bar.setOpaque(false);
        bar.add(new JLabel("Search"));
        searchField.addActionListener(e -> applyFilter());
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        bar.add(searchField);
        bar.add(new JLabel("Category"));
        categoryBox.addItem("All categories");
        for (String c : Categories.ALL) {
            categoryBox.addItem(c);
        }
        categoryBox.addActionListener(e -> applyFilter());
        bar.add(categoryBox);
        return bar;
    }

    private void configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(32);
        table.getTableHeader().setReorderingAllowed(false);
        TableColumnModel cols = table.getColumnModel();
        cols.getColumn(0).setPreferredWidth(220);
        cols.getColumn(5).setPreferredWidth(90);
        DefaultTableCellRenderer timeRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                           boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                int modelRow = t.convertRowIndexToModel(row);
                AuctionInfo a = tableModel.get(modelRow);
                if (!selected && a != null && a.isOpen()) {
                    long remaining = a.endTime() - System.currentTimeMillis();
                    if (remaining > 0 && remaining <= BidManager.ANTI_SNIPE_WINDOW_MS) {
                        setForeground(UiTheme.SNIPE_RED);
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else if (a.endingSoon(System.currentTimeMillis())) {
                        setForeground(new Color(0xB9770E));
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else {
                        setForeground(UiTheme.NAVY);
                    }
                }
                setHorizontalAlignment(SwingConstants.LEFT);
                return this;
            }
        };
        cols.getColumn(5).setCellRenderer(timeRenderer);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) {
                detail.show(null);
                return;
            }
            AuctionInfo a = tableModel.get(table.convertRowIndexToModel(viewRow));
            detail.show(a);
            detail.setWatching(a != null && watchIds.contains(a.id()));
            if (a != null) {
                applyCachedPhoto(a.id());
                requestPhoto(a.id(), true);
            }
        });
    }

    private void applyFilter() {
        Long selected = pendingSelectId != null
                ? pendingSelectId
                : (detail.current() == null ? null : detail.current().id());
        String q = searchField.getText().trim().toLowerCase();
        String cat = (String) categoryBox.getSelectedItem();
        long now = System.currentTimeMillis();
        List<AuctionInfo> shown = new ArrayList<>();
        for (AuctionInfo a : allAuctions) {
            if (cat != null && !"All categories".equals(cat) && !cat.equals(a.category())) {
                continue;
            }
            if (!q.isEmpty() && !a.title().toLowerCase().contains(q)
                    && !(a.description() != null && a.description().toLowerCase().contains(q))
                    && !(a.sellerName() != null && a.sellerName().toLowerCase().contains(q))) {
                continue;
            }
            boolean keep = switch (view) {
                case ALL -> true;
                case ENDING_SOON -> a.endingSoon(now);
                case WATCHING -> watchIds.contains(a.id());
                case MY_LISTINGS -> a.sellerId() == userId;
                case MY_BIDS -> myBidIds.contains(a.id());
                case WON -> a.isSold() && a.leaderId() != null && a.leaderId() == userId;
            };
            if (keep) {
                shown.add(a);
            }
        }
        tableModel.replaceAll(shown);
        if (selected != null) {
            int idx = tableModel.indexOf(selected);
            if (idx >= 0) {
                int vr = table.convertRowIndexToView(idx);
                table.getSelectionModel().setSelectionInterval(vr, vr);
                table.scrollRectToVisible(table.getCellRect(vr, 0, true));
                if (pendingSelectId != null && pendingSelectId.equals(selected)) {
                    pendingSelectId = null;
                }
            }
        }
    }

    private void showListedAuction(long id) {
        if (id < 0) {
            return;
        }
        pendingSelectId = id;
        if (!searchField.getText().isEmpty()) {
            searchField.setText("");
        }
        if (categoryBox.getSelectedIndex() != 0) {
            categoryBox.setSelectedIndex(0);
        }
        if (view != View.ALL && view != View.MY_LISTINGS) {
            view = View.MY_LISTINGS;
            viewList.setSelectedIndex(View.MY_LISTINGS.ordinal());
        } else {
            applyFilter();
        }
    }

    private void upsertAll(AuctionInfo a) {
        int idx = -1;
        for (int i = 0; i < allAuctions.size(); i++) {
            if (allAuctions.get(i).id() == a.id()) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            allAuctions.add(0, a);
        } else {
            allAuctions.set(idx, a);
        }
        applyFilter();
    }

    private void onMessage(String line) {
        String[] f = Protocol.decode(line);
        String type = Protocol.typeOf(f);
        switch (type) {
            case Protocol.GET_AUCTIONS_OK -> {
                int n = Protocol.parseInt(f, 1, 0);
                allAuctions.clear();
                int offset = 2;
                for (int i = 0; i < n; i++) {
                    allAuctions.add(AuctionInfo.fromWire(f, offset));
                    offset += AuctionInfo.WIRE_FIELD_COUNT;
                }
                applyFilter();
                prefetchMissingPhotos();
            }
            case Protocol.AUCTION_NEW, Protocol.AUCTION_UPDATED -> {
                AuctionInfo a = AuctionInfo.fromWire(f, 1);
                upsertAll(a);
                requestPhoto(a.id(), false);
                if (Protocol.AUCTION_NEW.equals(type) && a.sellerId() == userId) {
                    showListedAuction(a.id());
                } else if (detail.current() != null && detail.current().id() == a.id()) {
                    detail.show(a);
                    detail.setWatching(watchIds.contains(a.id()));
                    applyCachedPhoto(a.id());
                }
            }
            case Protocol.AUCTION_EXTENDED -> toast("Anti-snipe: lot extended +30 seconds", false);
            case Protocol.AUCTION_CLOSED -> {
                String winner = f.length > 3 ? f[3] : "";
                long price = Protocol.parseLong(f, 4, 0);
                if (winner.isEmpty()) {
                    setStatus("A lot ended unsold");
                } else {
                    setStatus("Sold to " + winner + " for " + Money.format(price));
                }
            }
            case Protocol.NEW_BID -> {
                BidInfo bid = new BidInfo(
                        Protocol.parseLong(f, 1, 0),
                        Protocol.parseLong(f, 2, 0),
                        Protocol.parseLong(f, 3, 0),
                        f.length > 4 ? f[4] : "",
                        Protocol.parseLong(f, 5, 0),
                        "1".equals(f.length > 6 ? f[6] : "0"),
                        Protocol.parseLong(f, 7, 0)
                );
                if (bid.bidderId() == userId) {
                    myBidIds.add(bid.auctionId());
                }
                detail.appendBid(bid);
            }
            case Protocol.GET_BIDS_OK -> {
                long auctionId = Protocol.parseLong(f, 1, -1);
                int n = Protocol.parseInt(f, 2, 0);
                List<BidInfo> bids = new ArrayList<>(n);
                int offset = 3;
                for (int i = 0; i < n; i++) {
                    bids.add(BidInfo.fromWire(f, offset));
                    offset += BidInfo.WIRE_FIELD_COUNT;
                }
                if (detail.current() != null && detail.current().id() == auctionId) {
                    detail.replaceBids(bids);
                }
            }
            case Protocol.WATCHES_OK -> {
                watchIds.clear();
                for (int i = 1; i < f.length; i++) {
                    if (!f[i].isEmpty()) {
                        watchIds.add(Long.parseLong(f[i]));
                    }
                }
                if (detail.current() != null) {
                    detail.setWatching(watchIds.contains(detail.current().id()));
                }
                if (view == View.WATCHING) {
                    applyFilter();
                }
            }
            case Protocol.MY_BIDS_OK -> {
                myBidIds.clear();
                for (int i = 1; i < f.length; i++) {
                    if (!f[i].isEmpty()) {
                        myBidIds.add(Long.parseLong(f[i]));
                    }
                }
            }
            case Protocol.IMAGE_OK -> {
                long id = Protocol.parseLong(f, 1, -1);
                ImageIcon icon = decodePhoto(f.length > 2 ? f[2] : "");
                if (icon != null) {
                    photos.put(id, icon);
                    if (detail.current() != null && detail.current().id() == id) {
                        detail.setPhoto(icon);
                    }
                }
            }
            case Protocol.IMAGE_FAIL -> {
                long id = Protocol.parseLong(f, 1, -1);
                if (id >= 0 && !photos.containsKey(id)
                        && detail.current() != null && detail.current().id() == id) {
                    detail.setPhoto(null);
                }
            }
            case Protocol.TICKER -> {
                String text = f.length > 1 ? f[1] : "";
                tickerModel.add(0, timestamp() + "  " + text);
                while (tickerModel.size() > 40) {
                    tickerModel.remove(tickerModel.size() - 1);
                }
            }
            case Protocol.YOU_OUTBID -> toast("You've been outbid on “" + (f.length > 2 ? f[2] : "a lot")
                    + "” — now " + Money.format(Protocol.parseLong(f, 3, 0)), true);
            case Protocol.YOU_WON -> {
                toast("You won “" + (f.length > 2 ? f[2] : "a lot") + "” for "
                        + Money.format(Protocol.parseLong(f, 3, 0)), false);
                connection.send(Protocol.encode(Protocol.GET_AUCTIONS));
            }
            case Protocol.WATCH_ALERT -> toast("Watching: " + (f.length > 3 ? f[3] : "update")
                    + " — " + (f.length > 2 ? f[2] : ""), false);
            case Protocol.BID_OK, Protocol.BUY_NOW_OK -> setStatus("Bid accepted");
            case Protocol.BID_REJECTED -> {
                String reason = f.length > 2 ? f[2] : "Bid rejected";
                detail.onBidRejected(reason);
                setStatus(reason);
            }
            case Protocol.PROXY_BID_OK -> {
                detail.onProxyOk();
                setStatus("Proxy maximum saved");
            }
            case Protocol.PROXY_BID_FAIL, Protocol.LIST_ITEM_FAIL, Protocol.BUY_NOW_FAIL, Protocol.RELIST_FAIL -> {
                String reason = f.length > 1 ? f[1] : "Request failed";
                JOptionPane.showMessageDialog(this, reason, "OpenBid", JOptionPane.WARNING_MESSAGE);
                setStatus(reason);
            }
            case Protocol.LIST_ITEM_OK, Protocol.RELIST_OK -> {
                setStatus("Item listed");
                showListedAuction(Protocol.parseLong(f, 1, -1));
            }
            default -> {
            }
        }
    }

    private void toast(String text, boolean warning) {
        toastLabel.setText(text);
        toastLabel.setBackground(warning ? UiTheme.SNIPE_RED : UiTheme.TEAL);
        toastLabel.setVisible(true);
        setStatus(text);
        Timer hide = new Timer(5000, e -> toastLabel.setVisible(false));
        hide.setRepeats(false);
        hide.start();
    }

    private void saveReceipt() {
        AuctionInfo a = detail.current();
        if (a == null || !a.isSold()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("openbid-receipt-" + a.id() + ".csv"));
        chooser.setFileFilter(new FileNameExtensionFilter("CSV", "csv", "txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(a.endTime()));
        String csv = "item,category,seller,buyer,price,ended\n"
                + csv(a.title()) + "," + csv(a.category()) + "," + csv(a.sellerName()) + ","
                + csv(username) + "," + csv(Money.format(a.currentPriceCents())) + "," + csv(when) + "\n";
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), csv, StandardCharsets.UTF_8);
            setStatus("Receipt saved");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save the receipt.", "OpenBid", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applyCachedPhoto(long id) {
        ImageIcon icon = photos.get(id);
        if (icon != null) {
            detail.setPhoto(icon);
        }
    }

    private void prefetchMissingPhotos() {
        for (AuctionInfo a : allAuctions) {
            requestPhoto(a.id(), false);
        }
    }

    private void requestPhoto(long id, boolean force) {
        if (id < 0) {
            return;
        }
        if (!force && photos.containsKey(id)) {
            return;
        }
        connection.send(Protocol.encode(Protocol.GET_IMAGE, Long.toString(id)));
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static ImageIcon decodePhoto(String b64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                return null;
            }
            int tw = 160;
            int th = 90;
            BufferedImage scaled = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, tw, th);
            double scale = Math.min(tw / (double) img.getWidth(), th / (double) img.getHeight());
            int nw = Math.max(1, (int) Math.round(img.getWidth() * scale));
            int nh = Math.max(1, (int) Math.round(img.getHeight() * scale));
            int x = (tw - nw) / 2;
            int y = (th - nh) / 2;
            g.drawImage(img, x, y, nw, nh, null);
            g.dispose();
            return new ImageIcon(scaled);
        } catch (Exception e) {
            return null;
        }
    }

    private static String timestamp() {
        return DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now());
    }

    private void onDisconnect() {
        countdownTimer.stop();
        JOptionPane.showMessageDialog(this, "Lost connection to the server.", "Disconnected",
                JOptionPane.ERROR_MESSAGE);
        logout();
    }

    private void logout() {
        countdownTimer.stop();
        connection.close();
        dispose();
        if (onLogout != null) {
            onLogout.run();
        }
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }
}

package com.openbid.client;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.openbid.shared.Categories;
import com.openbid.shared.Money;
import com.openbid.shared.Protocol;

final class ListItemDialog extends JDialog {

    private record DurationOption(String label, int seconds) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final ServerConnection connection;
    private final JTextField titleField = new JTextField(28);
    private final JTextArea descriptionArea = new JTextArea(4, 28);
    private final JTextField priceField = new JTextField("10.00", 10);
    private final JTextField reserveField = new JTextField("", 10);
    private final JTextField buyNowField = new JTextField("", 10);
    private final JComboBox<String> categoryBox = new JComboBox<>(Categories.ALL);
    private final JComboBox<DurationOption> durationBox = new JComboBox<>(new DurationOption[] {
            new DurationOption("45 seconds (demo)", 45),
            new DurationOption("1 minute", 60),
            new DurationOption("2 minutes", 120),
            new DurationOption("5 minutes", 300),
            new DurationOption("10 minutes", 600),
            new DurationOption("30 minutes", 1800),
            new DurationOption("1 hour", 3600)
    });
    private final JLabel photoLabel = new JLabel("No photo selected (a catalog image will be generated)");
    private byte[] photoJpeg;

    ListItemDialog(Frame owner, ServerConnection connection) {
        super(owner, "Sell an item", true);
        this.connection = connection;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(UiTheme.cardBorder());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 4, 6, 4);
        c.anchor = GridBagConstraints.WEST;

        int row = 0;
        addRow(form, c, row++, "Title", titleField);
        c.gridx = 0;
        c.gridy = row;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel("Description"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 1;
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        form.add(new JScrollPane(descriptionArea), c);
        row++;

        addRow(form, c, row++, "Category", categoryBox);
        addRow(form, c, row++, "Starting price", priceField);
        addRow(form, c, row++, "Reserve (optional)", reserveField);
        addHint(form, c, row++, "Lowest price you will actually sell at. If the high bid is below this when time runs out, the item is unsold. Other bidders only see whether the reserve is met — not the amount.");
        addRow(form, c, row++, "Buy It Now (optional)", buyNowField);
        addHint(form, c, row++, "A buyer can pay this price immediately instead of bidding. The button stays available until the current price reaches this amount.");
        durationBox.setSelectedIndex(3);
        addRow(form, c, row++, "Duration", durationBox);

        JButton photo = new JButton("Choose photo…");
        photo.addActionListener(e -> choosePhoto());
        c.gridx = 0;
        c.gridy = row;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.weighty = 0;
        form.add(photo, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(photoLabel, c);

        JButton cancel = new JButton("Cancel");
        JButton list = new JButton("List item");
        list.putClientProperty("JButton.buttonType", "default");
        cancel.addActionListener(e -> dispose());
        list.addActionListener(e -> submit());

        JPanel buttons = new JPanel();
        buttons.add(cancel);
        buttons.add(list);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(list);
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label, java.awt.Component field) {
        c.gridwidth = 1;
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.weighty = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
    }

    private static void addHint(JPanel form, GridBagConstraints c, int row, String text) {
        JLabel hint = new JLabel("<html><body style='width:360px'>" + text + "</body></html>");
        hint.setForeground(UiTheme.MUTED);
        hint.setFont(new Font("SansSerif", Font.PLAIN, 11));
        c.gridwidth = 1;
        c.gridx = 1;
        c.gridy = row;
        c.weightx = 1;
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 4, 10, 4);
        form.add(hint, c);
        c.insets = new Insets(6, 4, 6, 4);
    }

    private void choosePhoto() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Images", "jpg", "jpeg", "png", "gif"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            BufferedImage src = ImageIO.read(file);
            if (src == null) {
                photoJpeg = Files.readAllBytes(file.toPath());
            } else {
                photoJpeg = compressJpeg(src, 320, 200, 0.75f);
            }
            if (photoJpeg != null && photoJpeg.length > 120_000) {
                JOptionPane.showMessageDialog(this, "Please choose a smaller photo (under ~120 KB after compression).",
                        "Photo too large", JOptionPane.WARNING_MESSAGE);
                photoJpeg = null;
                return;
            }
            photoLabel.setText(file.getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not read that image.", "Photo", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static byte[] compressJpeg(BufferedImage src, int maxW, int maxH, float quality) throws Exception {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(maxW / (double) w, maxH / (double) h);
        if (scale > 1) {
            scale = 1;
        }
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage rgb = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ByteArrayOutputStream fallback = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", fallback);
            return fallback.toByteArray();
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private void submit() {
        String title = titleField.getText().trim();
        if (title.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a title.", "Missing title",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        long cents;
        long reserve = 0;
        long buyNow = 0;
        try {
            cents = Money.parseToCents(priceField.getText());
            if (!reserveField.getText().isBlank()) {
                reserve = Money.parseToCents(reserveField.getText());
            }
            if (!buyNowField.getText().isBlank()) {
                buyNow = Money.parseToCents(buyNowField.getText());
            }
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid price", JOptionPane.WARNING_MESSAGE);
            return;
        }
        DurationOption duration = (DurationOption) durationBox.getSelectedItem();
        int seconds = duration == null ? 300 : duration.seconds();
        String category = (String) categoryBox.getSelectedItem();
        String image = photoJpeg == null ? "" : Base64.getEncoder().encodeToString(photoJpeg);
        connection.send(Protocol.encode(
                Protocol.LIST_ITEM,
                title,
                descriptionArea.getText(),
                Long.toString(cents),
                Integer.toString(seconds),
                category == null ? "Other" : category,
                Long.toString(reserve),
                Long.toString(buyNow),
                image
        ));
        dispose();
    }
}

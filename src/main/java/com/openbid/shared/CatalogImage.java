package com.openbid.shared;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/** Generates a catalog-style JPEG so demo lots (and listings without a photo) still look finished. */
public final class CatalogImage {

    private CatalogImage() {}

    public static byte[] jpeg(String title, String category) {
        Color accent = colorFor(Categories.normalize(category));
        BufferedImage img = new BufferedImage(640, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, accent.darker(), 640, 400, accent.brighter()));
        g.fillRect(0, 0, 640, 400);
        g.setColor(new Color(255, 255, 255, 40));
        g.fillRoundRect(36, 36, 568, 328, 28, 28);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.drawString(Categories.normalize(category).toUpperCase(), 56, 90);
        g.setFont(new Font("SansSerif", Font.BOLD, 32));
        drawWrapped(g, title == null ? "OpenBid lot" : title, 56, 150, 520, 36);
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g.drawString("OpenBid  ·  live auction", 56, 340);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static void drawWrapped(Graphics2D g, String text, int x, int y, int width, int lineHeight) {
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        int row = 0;
        for (String word : words) {
            String trial = line.isEmpty() ? word : line + " " + word;
            if (g.getFontMetrics().stringWidth(trial) > width && !line.isEmpty()) {
                g.drawString(line.toString(), x, y + row * lineHeight);
                line = new StringBuilder(word);
                row++;
                if (row >= 3) {
                    break;
                }
            } else {
                line = new StringBuilder(trial);
            }
        }
        if (row < 3 && !line.isEmpty()) {
            g.drawString(line.toString(), x, y + row * lineHeight);
        }
    }

    private static Color colorFor(String category) {
        return switch (category) {
            case "Electronics" -> new Color(0x1A5276);
            case "Collectibles" -> new Color(0x6C3483);
            case "Fashion" -> new Color(0x922B21);
            case "Sports" -> new Color(0x1E8449);
            case "Music" -> new Color(0xB9770E);
            case "Home" -> new Color(0x1B4F72);
            default -> new Color(0x148F77);
        };
    }
}

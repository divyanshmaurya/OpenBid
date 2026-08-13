package com.openbid.client;

import java.awt.Color;
import java.awt.Font;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.border.Border;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;

final class UiTheme {

    static final Color NAVY = new Color(0x1B4F72);
    static final Color TEAL = new Color(0x148F77);
    static final Color SNIPE_RED = new Color(0xC0392B);
    static final Color MUTED = new Color(0x5D6D7E);
    static final Color SURFACE = new Color(0xF4F6F7);
    static final Color CARD = Color.WHITE;
    static final Color SOLD = new Color(0x7F8C8D);
    static final long ENDING_SOON_MS = 120_000L;

    private UiTheme() {}

    static boolean isDark() {
        return Preferences.userNodeForPackage(ClientMain.class).getBoolean("dark", false);
    }

    static void setDark(boolean dark) {
        Preferences.userNodeForPackage(ClientMain.class).putBoolean("dark", dark);
        install();
    }

    static void install() {
        if (isDark()) {
            FlatDarculaLaf.setup();
        } else {
            FlatIntelliJLaf.setup();
        }
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("Button.default.background", TEAL);
        UIManager.put("Button.default.foreground", Color.WHITE);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.rowHeight", 32);
        UIManager.put("Table.selectionBackground", new Color(0xD5F5E3));
        UIManager.put("Table.selectionForeground", NAVY);
    }

    static Font titleFont() {
        return new Font("SansSerif", Font.BOLD, 22);
    }

    static Font headingFont() {
        return new Font("SansSerif", Font.BOLD, 16);
    }

    static Font priceFont() {
        return new Font("SansSerif", Font.BOLD, 28);
    }

    static Font monoFont() {
        return new Font("Monospaced", Font.PLAIN, 13);
    }

    static Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD5D8DC)),
                BorderFactory.createEmptyBorder(16, 18, 16, 18)
        );
    }

    static String formatRemaining(long endTimeMs, long now) {
        long remaining = endTimeMs - now;
        if (remaining <= 0) {
            return "Ended";
        }
        long totalSec = remaining / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%d:%02d", m, s);
    }
}

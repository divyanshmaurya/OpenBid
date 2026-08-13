package com.openbid.client;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class ClientMain {

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "OpenBid");
        SwingUtilities.invokeLater(() -> {
            UiTheme.install();
            UIManager.put("OptionPane.okButtonText", "OK");
            LoginFrame login = new LoginFrame();
            login.setVisible(true);
        });
    }
}

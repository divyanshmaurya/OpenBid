package com.openbid.client;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;

import com.openbid.shared.Protocol;

final class LoginFrame extends JFrame {

    private final JTextField hostField = new JTextField("localhost", 16);
    private final JTextField portField = new JTextField(Integer.toString(Protocol.DEFAULT_PORT), 6);
    private final JTextField userField = new JTextField(16);
    private final JPasswordField passField = new JPasswordField(16);
    private final JLabel errorLabel = new JLabel(" ");
    private final JButton loginButton = new JButton("Sign in");
    private final JButton registerButton = new JButton("Create account");

    private final ServerConnection connection = new ServerConnection();

    LoginFrame() {
        super("OpenBid");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(420, 400));

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(UiTheme.SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(28, 36, 28, 36));

        JLabel brand = new JLabel("OpenBid", SwingConstants.CENTER);
        brand.setFont(UiTheme.titleFont());
        brand.setForeground(UiTheme.NAVY);
        JLabel tagline = new JLabel("An online auction house", SwingConstants.CENTER);
        tagline.setForeground(UiTheme.MUTED);
        tagline.setFont(new Font("SansSerif", Font.PLAIN, 13));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(brand, BorderLayout.NORTH);
        header.add(tagline, BorderLayout.CENTER);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 18, 0));

        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(UiTheme.CARD);
        card.setBorder(UiTheme.cardBorder());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 4, 6, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        int row = 0;
        addRow(card, c, row++, "Server", hostField);
        addRow(card, c, row++, "Port", portField);
        addRow(card, c, row++, "Username", userField);
        addRow(card, c, row++, "Password", passField);

        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        errorLabel.setForeground(UiTheme.SNIPE_RED);
        card.add(errorLabel, c);

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        loginButton.putClientProperty("JButton.buttonType", "default");
        buttons.add(registerButton);
        buttons.add(loginButton);
        c.gridy = row;
        card.add(buttons, c);

        root.add(header, BorderLayout.NORTH);
        root.add(card, BorderLayout.CENTER);
        setContentPane(root);
        pack();
        setLocationRelativeTo(null);
        getRootPane().setDefaultButton(loginButton);

        loginButton.addActionListener(e -> submit(false));
        registerButton.addActionListener(e -> submit(true));
        connection.setOnMessage(this::onMessage);
        connection.setOnDisconnect(() -> errorLabel.setText("Disconnected from server."));
    }

    private static void addRow(JPanel card, GridBagConstraints c, int row, String label, JTextField field) {
        c.gridwidth = 1;
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        card.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        card.add(field, c);
    }

    private void submit(boolean register) {
        errorLabel.setText(" ");
        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            errorLabel.setText("Port must be a number.");
            return;
        }
        String user = userField.getText().trim();
        String pass = new String(passField.getPassword());
        if (user.isEmpty() || pass.isEmpty()) {
            errorLabel.setText("Enter a username and password.");
            return;
        }
        setBusy(true);
        try {
            connection.close();
            connection.connect(host, port);
            String type = register ? Protocol.REGISTER : Protocol.LOGIN;
            connection.send(Protocol.encode(type, user, pass));
        } catch (Exception ex) {
            errorLabel.setText("Cannot connect: " + ex.getMessage());
            setBusy(false);
        }
    }

    private void onMessage(String line) {
        String[] f = Protocol.decode(line);
        String type = Protocol.typeOf(f);
        switch (type) {
            case Protocol.LOGIN_OK, Protocol.REGISTER_OK -> {
                long userId = Protocol.parseLong(f, 1, -1);
                String name = f.length > 2 ? f[2] : userField.getText().trim();
                openMain(userId, name);
            }
            case Protocol.LOGIN_FAIL, Protocol.REGISTER_FAIL -> {
                errorLabel.setText(f.length > 1 ? f[1] : "Authentication failed");
                setBusy(false);
            }
            default -> {
                // ignore events that arrive before the main window is up
            }
        }
    }

    private void openMain(long userId, String username) {
        MainFrame main = new MainFrame(connection, userId, username, () -> {
            LoginFrame again = new LoginFrame();
            again.setVisible(true);
        });
        main.setVisible(true);
        dispose();
    }

    private void setBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        registerButton.setEnabled(!busy);
        if (busy) {
            errorLabel.setForeground(UiTheme.MUTED);
            errorLabel.setText("Connecting…");
        } else {
            errorLabel.setForeground(UiTheme.SNIPE_RED);
        }
    }
}

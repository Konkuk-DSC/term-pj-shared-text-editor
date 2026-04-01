package com.editor.client;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.payload.LoginRequest;
import com.editor.common.payload.LoginResponse;
import com.editor.common.payload.RegisterRequest;
import com.editor.common.payload.RegisterResponse;

import javax.swing.*;
import java.awt.*;

/**
 * 로그인/회원가입 UI.
 * 로그인 성공 시 메인 화면으로 전환한다 (Phase 3.4에서 구현).
 */
public class LoginFrame extends JFrame {

    private final ClientMain client;

    private JTextField userIdField;
    private JPasswordField passwordField;
    private JButton loginButton;
    private JButton registerButton;
    private JLabel statusLabel;

    private String loggedInUserId;

    public LoginFrame(ClientMain client) {
        this.client = client;
        initUI();
    }

    private void initUI() {
        setTitle("Shared Text Editor — Login");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(30, 40, 30, 40));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // 제목
        JLabel titleLabel = new JLabel("Shared Text Editor", SwingConstants.CENTER);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        mainPanel.add(titleLabel, gbc);

        // ID
        gbc.gridwidth = 1; gbc.gridy = 1;
        gbc.gridx = 0;
        mainPanel.add(new JLabel("ID:"), gbc);
        userIdField = new JTextField(15);
        gbc.gridx = 1;
        mainPanel.add(userIdField, gbc);

        // PW
        gbc.gridy = 2; gbc.gridx = 0;
        mainPanel.add(new JLabel("PW:"), gbc);
        passwordField = new JPasswordField(15);
        gbc.gridx = 1;
        mainPanel.add(passwordField, gbc);

        // 버튼 패널
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        loginButton = new JButton("Login");
        registerButton = new JButton("Register");
        buttonPanel.add(loginButton);
        buttonPanel.add(registerButton);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        mainPanel.add(buttonPanel, gbc);

        // 상태 라벨
        statusLabel = new JLabel(" ", SwingConstants.CENTER);
        statusLabel.setForeground(Color.RED);
        gbc.gridy = 4;
        mainPanel.add(statusLabel, gbc);

        add(mainPanel);
        pack();
        setLocationRelativeTo(null);

        // 이벤트 연결
        loginButton.addActionListener(e -> doLogin());
        registerButton.addActionListener(e -> doRegister());

        // Enter 키로 로그인
        passwordField.addActionListener(e -> doLogin());
        userIdField.addActionListener(e -> passwordField.requestFocus());
    }

    private void doLogin() {
        String userId = userIdField.getText().trim();
        String password = new String(passwordField.getPassword());

        if (userId.isEmpty() || password.isEmpty()) {
            setStatus("Please enter ID and Password.", true);
            return;
        }

        setButtonsEnabled(false);
        setStatus("Logging in...", false);

        Message msg = new Message(MessageType.LOGIN_REQUEST, userId);
        msg.setPayloadFromObject(new LoginRequest(userId, password));
        if (!client.send(msg)) {
            setStatus("Failed to send to server.", true);
            setButtonsEnabled(true);
        }
    }

    private void doRegister() {
        String userId = userIdField.getText().trim();
        String password = new String(passwordField.getPassword());

        if (userId.isEmpty() || password.isEmpty()) {
            setStatus("Please enter ID and Password.", true);
            return;
        }

        setButtonsEnabled(false);
        setStatus("Registering...", false);

        Message msg = new Message(MessageType.REGISTER_REQUEST, userId);
        msg.setPayloadFromObject(new RegisterRequest(userId, password));
        if (!client.send(msg)) {
            setStatus("Failed to send to server.", true);
            setButtonsEnabled(true);
        }
    }

    // ── 서버 응답 처리 (MessageListener에서 호출) ──

    public void handleLoginResponse(Message msg) {
        LoginResponse resp = msg.getPayloadAs(LoginResponse.class);
        SwingUtilities.invokeLater(() -> {
            if (resp.isSuccess()) {
                loggedInUserId = userIdField.getText().trim();
                setStatus("Login successful!", false);
                // TODO: Phase 3.4 — switch to main editor screen
                JOptionPane.showMessageDialog(this,
                        "Login successful!\nOnline users: " + resp.getOnlineUsers(),
                        "Info", JOptionPane.INFORMATION_MESSAGE);
            } else {
                setStatus(resp.getMessage(), true);
                setButtonsEnabled(true);
            }
        });
    }

    public void handleRegisterResponse(Message msg) {
        RegisterResponse resp = msg.getPayloadAs(RegisterResponse.class);
        SwingUtilities.invokeLater(() -> {
            if (resp.isSuccess()) {
                setStatus("Registration successful! Please login.", false);
            } else {
                setStatus(resp.getMessage(), true);
            }
            setButtonsEnabled(true);
        });
    }

    public void handleDisconnected() {
        SwingUtilities.invokeLater(() -> {
            setStatus("Disconnected from server.", true);
            setButtonsEnabled(false);
        });
    }

    // ── 유틸 ──

    private void setStatus(String text, boolean isError) {
        statusLabel.setText(text);
        statusLabel.setForeground(isError ? Color.RED : new Color(0, 128, 0));
    }

    private void setButtonsEnabled(boolean enabled) {
        loginButton.setEnabled(enabled);
        registerButton.setEnabled(enabled);
    }

    public String getLoggedInUserId() {
        return loggedInUserId;
    }
}

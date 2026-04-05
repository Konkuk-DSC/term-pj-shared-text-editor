package com.editor.client;

import com.editor.common.Message;
import com.editor.common.payload.UserEvent;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * 메인 화면 — 접속자 목록 + 에디터 영역(Phase 4에서 추가).
 * 로그인 성공 후 LoginFrame 대신 표시된다.
 */
public class MainFrame extends JFrame {

    private final ClientMain client;
    private final String userId;

    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    private JLabel statusLabel;

    public MainFrame(ClientMain client, String userId, List<String> onlineUsers) {
        this.client = client;
        this.userId = userId;
        initUI(onlineUsers);
    }

    private void initUI(List<String> onlineUsers) {
        setTitle("Shared Text Editor — " + userId);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLayout(new BorderLayout());

        // ── 접속자 목록 패널 (우측) ──
        userListModel = new DefaultListModel<>();
        if (onlineUsers != null) {
            for (String user : onlineUsers) {
                userListModel.addElement(user);
            }
        }

        userList = new JList<>(userListModel);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setCellRenderer(new UserListCellRenderer(userId));

        JScrollPane userScrollPane = new JScrollPane(userList);
        userScrollPane.setPreferredSize(new Dimension(180, 0));

        JPanel userPanel = new JPanel(new BorderLayout());
        JLabel userPanelTitle = new JLabel("Online Users", SwingConstants.CENTER);
        userPanelTitle.setFont(new Font("SansSerif", Font.BOLD, 13));
        userPanelTitle.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        userPanel.add(userPanelTitle, BorderLayout.NORTH);
        userPanel.add(userScrollPane, BorderLayout.CENTER);

        add(userPanel, BorderLayout.EAST);

        // ── 에디터 영역 (중앙) — Phase 4에서 JTextArea로 교체 ──
        JPanel editorPlaceholder = new JPanel(new BorderLayout());
        JLabel placeholderLabel = new JLabel("Editor will be here (Phase 4)", SwingConstants.CENTER);
        placeholderLabel.setFont(new Font("SansSerif", Font.ITALIC, 14));
        placeholderLabel.setForeground(Color.GRAY);
        editorPlaceholder.add(placeholderLabel, BorderLayout.CENTER);

        add(editorPlaceholder, BorderLayout.CENTER);

        // ── 상태바 (하단) ──
        statusLabel = new JLabel("  Connected as: " + userId);
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)
        ));
        add(statusLabel, BorderLayout.SOUTH);

        setLocationRelativeTo(null);
    }

    // ── 접속자 목록 갱신 (MessageListener에서 호출) ──

    public void handleUserJoined(Message msg) {
        UserEvent event = msg.getPayloadAs(UserEvent.class);
        SwingUtilities.invokeLater(() -> {
            String joinedUser = event.getUserId();
            if (!userListModel.contains(joinedUser)) {
                userListModel.addElement(joinedUser);
            }
            setStatus(joinedUser + " joined.");
        });
    }

    public void handleUserLeft(Message msg) {
        UserEvent event = msg.getPayloadAs(UserEvent.class);
        SwingUtilities.invokeLater(() -> {
            String leftUser = event.getUserId();
            userListModel.removeElement(leftUser);
            setStatus(leftUser + " left.");
        });
    }

    public void handleDisconnected() {
        SwingUtilities.invokeLater(() -> {
            setStatus("Disconnected from server.");
            JOptionPane.showMessageDialog(this,
                    "Lost connection to server.",
                    "Disconnected", JOptionPane.WARNING_MESSAGE);
        });
    }

    private void setStatus(String text) {
        statusLabel.setText("  " + text);
    }

    public String getUserId() {
        return userId;
    }

    // ── 접속자 리스트 셀 렌더러 (자기 자신 강조) ──

    private static class UserListCellRenderer extends DefaultListCellRenderer {
        private final String myUserId;

        UserListCellRenderer(String myUserId) {
            this.myUserId = myUserId;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            String user = (String) value;
            if (user.equals(myUserId)) {
                setText(user + " (me)");
                setFont(getFont().deriveFont(Font.BOLD));
            }
            return this;
        }
    }
}

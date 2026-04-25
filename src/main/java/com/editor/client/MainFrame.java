package com.editor.client;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.payload.TextDelete;
import com.editor.common.payload.TextInsert;
import com.editor.common.payload.TextUpdate;
import com.editor.common.payload.UserEvent;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
    private JTextArea editorArea;
    private volatile boolean isRemoteChange = false;

    public MainFrame(ClientMain client, String userId, List<String> onlineUsers) {
        this(client, userId, onlineUsers, null);
    }

    public MainFrame(ClientMain client, String userId, List<String> onlineUsers, String initialContent) {
        this.client = client;
        this.userId = userId;
        initUI(onlineUsers, initialContent);
    }

    private void initUI(List<String> onlineUsers, String initialContent) {
        setTitle("Shared Text Editor — " + userId);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // LOGOUT message to server
                Message logoutMsg = new Message(MessageType.LOGOUT, userId);
                client.send(logoutMsg);
                client.disconnect();
                dispose();
                System.exit(0);
            }
        });
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

        // ── 에디터 영역 (중앙) ──
        editorArea = new JTextArea();
        editorArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        editorArea.setLineWrap(true);
        editorArea.setWrapStyleWord(true);
        editorArea.setTabSize(4);

        // 초기 문서 내용 설정 (Late-comer 동기화) — DocumentListener 등록 전에 수행
        if (initialContent != null && !initialContent.isEmpty()) {
            editorArea.setText(initialContent);
            editorArea.setCaretPosition(0);
        }

        editorArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                if (isRemoteChange) return;
                int offset = e.getOffset();
                int length = e.getLength();
                // Document lock 안에서 바로 텍스트 추출 (invokeLater 지연 중 offset 밀림 방지)
                String text;
                try {
                    text = e.getDocument().getText(offset, length);
                } catch (BadLocationException ex) {
                    ex.printStackTrace();
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    Message msg = new Message(MessageType.TEXT_INSERT, userId);
                    msg.setPayloadFromObject(new TextInsert(offset, text));
                    client.send(msg);
                });
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (isRemoteChange) return;
                int offset = e.getOffset();
                int length = e.getLength();
                SwingUtilities.invokeLater(() -> {
                    Message msg = new Message(MessageType.TEXT_DELETE, userId);
                    msg.setPayloadFromObject(new TextDelete(offset, length));
                    client.send(msg);
                });
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // 속성 변경 — 텍스트 편집과 무관, 무시
            }
        });

        JScrollPane editorScrollPane = new JScrollPane(editorArea);
        editorScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        editorScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        add(editorScrollPane, BorderLayout.CENTER);

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

    // ── 원격 텍스트 편집 수신 (Phase 4.7) ──

    public void handleTextInsert(Message msg) {
        TextInsert payload = msg.getPayloadAs(TextInsert.class);
        SwingUtilities.invokeLater(() -> {
            try {
                isRemoteChange = true;
                editorArea.getDocument().insertString(payload.getOffset(), payload.getText(), null);
            } catch (BadLocationException e) {
                System.err.println("[REMOTE INSERT] BadLocation: " + e.getMessage());
            } finally {
                isRemoteChange = false;
            }
        });
    }

    public void handleTextDelete(Message msg) {
        TextDelete payload = msg.getPayloadAs(TextDelete.class);
        SwingUtilities.invokeLater(() -> {
            try {
                isRemoteChange = true;
                editorArea.getDocument().remove(payload.getOffset(), payload.getLength());
            } catch (BadLocationException e) {
                System.err.println("[REMOTE DELETE] BadLocation: " + e.getMessage());
            } finally {
                isRemoteChange = false;
            }
        });
    }

    public void handleTextUpdate(Message msg) {
        TextUpdate payload = msg.getPayloadAs(TextUpdate.class);
        SwingUtilities.invokeLater(() -> {
            try {
                isRemoteChange = true;
                editorArea.getDocument().remove(payload.getOffset(), payload.getLength());
                editorArea.getDocument().insertString(payload.getOffset(), payload.getNewText(), null);
            } catch (BadLocationException e) {
                System.err.println("[REMOTE UPDATE] BadLocation: " + e.getMessage());
            } finally {
                isRemoteChange = false;
            }
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

    public JTextArea getEditorArea() {
        return editorArea;
    }

    public void setRemoteChange(boolean remote) {
        this.isRemoteChange = remote;
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

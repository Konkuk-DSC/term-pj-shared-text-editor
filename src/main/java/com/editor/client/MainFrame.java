package com.editor.client;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.payload.SessionCreateRequest;
import com.editor.common.payload.SessionCreateResponse;
import com.editor.common.payload.SessionInfo;
import com.editor.common.payload.SessionJoinRequest;
import com.editor.common.payload.SessionJoinResponse;
import com.editor.common.payload.SessionListResponse;
import com.editor.common.payload.TextDelete;
import com.editor.common.payload.TextInsert;
import com.editor.common.payload.TextUpdate;
import com.editor.common.payload.UserEvent;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
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
    private DefaultListModel<String> sessionListModel;
    private JList<String> sessionList;
    private JLabel currentSessionLabel; // Phase 5.7 — 현재 참여 중인 세션 표시
    private JButton joinSessionBtn;     // Phase 5.7
    private java.util.List<SessionInfo> currentSessions = new java.util.ArrayList<>();
    private volatile String currentSessionId; // Phase 5.4 — null이면 로비 상태
    private volatile String currentSessionName;

    public MainFrame(ClientMain client, String userId, List<String> onlineUsers) {
        this.client = client;
        this.userId = userId;
        initUI(onlineUsers);
        requestSessionList();
    }

    private void requestSessionList() {
        Message msg = new Message(MessageType.SESSION_LIST_REQUEST, userId);
        client.send(msg);
    }

    private void initUI(List<String> onlineUsers) {
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

        // ── 세션 목록 패널 (좌측) ──
        sessionListModel = new DefaultListModel<>();
        sessionList = new JList<>(sessionListModel);
        sessionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionList.setFont(new Font("SansSerif", Font.PLAIN, 12));

        JScrollPane sessionScrollPane = new JScrollPane(sessionList);
        sessionScrollPane.setPreferredSize(new Dimension(200, 0));

        JPanel sessionPanel = new JPanel(new BorderLayout());

        // 세션 패널 상단: 제목 + 현재 참여 세션 표시
        JPanel sessionHeader = new JPanel(new BorderLayout());
        JLabel sessionTitle = new JLabel("Sessions", SwingConstants.CENTER);
        sessionTitle.setFont(new Font("SansSerif", Font.BOLD, 13));
        sessionTitle.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        sessionHeader.add(sessionTitle, BorderLayout.NORTH);

        currentSessionLabel = new JLabel("Current: (lobby)", SwingConstants.CENTER);
        currentSessionLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        currentSessionLabel.setForeground(Color.GRAY);
        currentSessionLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 6, 4));
        sessionHeader.add(currentSessionLabel, BorderLayout.SOUTH);

        sessionPanel.add(sessionHeader, BorderLayout.NORTH);
        sessionPanel.add(sessionScrollPane, BorderLayout.CENTER);

        // 세션 패널 하단: 버튼 (New / Join / Refresh)
        JPanel sessionButtonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 5));
        JButton newSessionBtn = new JButton("New");
        joinSessionBtn = new JButton("Join");
        JButton refreshBtn = new JButton("Refresh");
        joinSessionBtn.setEnabled(false); // 선택된 세션 없을 때 비활성
        sessionButtonPanel.add(newSessionBtn);
        sessionButtonPanel.add(joinSessionBtn);
        sessionButtonPanel.add(refreshBtn);
        sessionPanel.add(sessionButtonPanel, BorderLayout.SOUTH);

        // 선택 변경 시 Join 버튼 활성/비활성 갱신 (이미 참여 중인 세션은 비활성)
        sessionList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            updateJoinButtonState();
        });

        joinSessionBtn.addActionListener(e -> {
            int idx = sessionList.getSelectedIndex();
            if (idx < 0 || idx >= currentSessions.size()) return;
            SessionInfo info = currentSessions.get(idx);
            if (info.getSessionId().equals(currentSessionId)) return;
            joinSession(info.getSessionId());
        });

        newSessionBtn.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this, "Session name:", "New Session", JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.trim().isEmpty()) {
                Message msg = new Message(MessageType.SESSION_CREATE, userId);
                msg.setPayloadFromObject(new SessionCreateRequest(name.trim()));
                client.send(msg);
            }
        });

        refreshBtn.addActionListener(e -> {
            Message msg = new Message(MessageType.SESSION_LIST_REQUEST, userId);
            client.send(msg);
        });

        // 더블 클릭 → 해당 세션 참여
        sessionList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int idx = sessionList.locationToIndex(e.getPoint());
                if (idx < 0 || idx >= currentSessions.size()) return;
                SessionInfo info = currentSessions.get(idx);
                if (info.getSessionId().equals(currentSessionId)) return; // 이미 참여 중
                joinSession(info.getSessionId());
            }
        });

        add(sessionPanel, BorderLayout.WEST);

        // ── 에디터 영역 (중앙) ──
        editorArea = new JTextArea();
        editorArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        editorArea.setLineWrap(true);
        editorArea.setWrapStyleWord(true);
        editorArea.setTabSize(4);

        // Phase 5.4 — 세션에 참여하기 전에는 편집 불가 (로비 상태)
        editorArea.setEditable(false);

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
        statusLabel = new JLabel("  Connected as: " + userId + " — Double-click a session or select + Join to start editing");
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

    // ── 세션 관리 수신 (Phase 5.3) ──

    public void handleSessionCreateResponse(Message msg) {
        SessionCreateResponse resp = msg.getPayloadAs(SessionCreateResponse.class);
        SwingUtilities.invokeLater(() -> {
            if (resp.isSuccess()) {
                setStatus("Session created: " + resp.getSessionName());
            } else {
                JOptionPane.showMessageDialog(this, resp.getMessage(), "Session Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public void handleSessionListResponse(Message msg) {
        SessionListResponse resp = msg.getPayloadAs(SessionListResponse.class);
        SwingUtilities.invokeLater(() -> {
            currentSessions = resp.getSessions();
            sessionListModel.clear();
            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm");
            for (SessionInfo info : currentSessions) {
                String marker = info.getSessionId().equals(currentSessionId) ? "★ " : "  ";
                String display = marker + info.getSessionName()
                        + "  [" + info.getParticipantCount() + "명]"
                        + "  " + sdf.format(new Date(info.getLastModifiedAt()));
                sessionListModel.addElement(display);
            }
            updateJoinButtonState();
        });
    }

    /** Phase 5.7 — 선택된 세션과 현재 참여 세션을 비교해 Join 버튼 활성화 갱신 */
    private void updateJoinButtonState() {
        int idx = sessionList.getSelectedIndex();
        boolean enabled = false;
        if (idx >= 0 && idx < currentSessions.size()) {
            SessionInfo info = currentSessions.get(idx);
            enabled = !info.getSessionId().equals(currentSessionId);
        }
        joinSessionBtn.setEnabled(enabled);
    }

    public void handleSessionJoinResponse(Message msg) {
        SessionJoinResponse resp = msg.getPayloadAs(SessionJoinResponse.class);
        SwingUtilities.invokeLater(() -> {
            if (!resp.isSuccess()) {
                JOptionPane.showMessageDialog(this,
                        "Failed to join session: " + resp.getMessage(),
                        "Session Join Failed", JOptionPane.ERROR_MESSAGE);
                // joinSession()이 응답 대기 동안 에디터를 잠가뒀음 — 실패 시 복원.
                // 이전에 다른 세션에 참여 중이었다면 편집 가능 상태로 되돌리고,
                // 로비 상태(currentSessionId == null)였다면 그대로 read-only 유지.
                if (currentSessionId != null) {
                    editorArea.setEditable(true);
                }
                return;
            }

            currentSessionId = resp.getSessionId();
            currentSessionName = resp.getSessionName();

            // 서버가 보낸 세션 내용으로 에디터 교체. 원격 변경 플래그로 DocumentListener 재전송 차단.
            try {
                isRemoteChange = true;
                String content = resp.getDocumentContent() == null ? "" : resp.getDocumentContent();
                editorArea.setText(content);
                editorArea.setCaretPosition(0);
            } finally {
                isRemoteChange = false;
            }

            editorArea.setEditable(true);
            setTitle("Shared Text Editor — " + userId + " @ " + currentSessionName);
            currentSessionLabel.setText("Current: " + currentSessionName);
            currentSessionLabel.setForeground(new Color(0, 100, 0));
            setStatus("Joined session: " + currentSessionName);

            // 세션 목록의 ★ 마커 및 Join 버튼 상태 갱신을 위해 재요청
            // (참여자 수 변경 broadcast가 곧 도착하지만, 즉시 반영을 위해 한 번 더 요청)
            updateJoinButtonState();
            // 새로 참여한 세션 항목에 ★ 표시
            refreshSessionListDisplay();
        });
    }

    /** Phase 5.7 — 세션 목록 표시만 재구성 (서버 재요청 없이 ★ 마커 갱신) */
    private void refreshSessionListDisplay() {
        sessionListModel.clear();
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm");
        for (SessionInfo info : currentSessions) {
            String marker = info.getSessionId().equals(currentSessionId) ? "★ " : "  ";
            String display = marker + info.getSessionName()
                    + "  [" + info.getParticipantCount() + "명]"
                    + "  " + sdf.format(new Date(info.getLastModifiedAt()));
            sessionListModel.addElement(display);
        }
    }

    private void joinSession(String sessionId) {
        // 응답 도착 전 사이 입력으로 인한 race를 막기 위해 에디터를 잠시 잠근다.
        editorArea.setEditable(false);
        Message msg = new Message(MessageType.SESSION_JOIN, userId);
        msg.setPayloadFromObject(new SessionJoinRequest(sessionId));
        client.send(msg);
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

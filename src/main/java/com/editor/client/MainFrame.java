package com.editor.client;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.payload.LockReply;
import com.editor.common.payload.LockRelease;
import com.editor.common.payload.LockRequest;
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
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.DocumentFilter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

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

    // Phase 7 — 분산 mutex
    private final LockManager lockManager;
    private volatile int currentLine = -1; // 현재 커서가 위치한 줄 (잠금 단위)
    private volatile long lockBlockNotifyAt = 0; // 잠금 차단 알림 throttling용

    // Phase 7.6 — 잠금 상태 시각화
    /** Highlighter.addHighlight()가 반환한 tag — 다음 redraw 때 제거하기 위해 보관. */
    private final List<Object> activeLockHighlights = new ArrayList<>();
    /** 원격 사용자 보유/획득 중인 줄: 분홍색 (편집 차단됨을 알림) */
    private static final DefaultHighlighter.DefaultHighlightPainter REMOTE_LOCK_PAINTER =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 210, 210));
    /** 내가 응답 대기 중인 줄: 연노랑 */
    private static final DefaultHighlighter.DefaultHighlightPainter MY_PENDING_PAINTER =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 245, 180));
    /** 내가 보유 중인 줄: 연녹색 (편집 가능 표시) */
    private static final DefaultHighlighter.DefaultHighlightPainter MY_HELD_PAINTER =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(220, 245, 220));

    // Phase 7.7 — 줄 추가/삭제로 인한 region id shift 추적
    private int lastLineCount = 1; // 빈 Document는 line count = 1

    public MainFrame(ClientMain client, String userId, List<String> onlineUsers) {
        this.client = client;
        this.userId = userId;
        this.lockManager = new LockManager(
                userId,
                client::send,
                new LockManager.LockListener() {
                    @Override
                    public void onLockAcquired(int regionId) {
                        SwingUtilities.invokeLater(() -> {
                            if (regionId == currentLine && currentSessionId != null) {
                                setStatus("Lock acquired — you can edit line " + (regionId + 1));
                            }
                        });
                    }

                    @Override
                    public void onLockTimeout(int regionId) {
                        SwingUtilities.invokeLater(() ->
                                setStatus("Line " + (regionId + 1) + ": peer reply timeout — forced grant"));
                    }

                    @Override
                    public void onLockStateChanged() {
                        SwingUtilities.invokeLater(MainFrame.this::redrawLockHighlights);
                    }
                });
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
                // 보유 중인 잠금이 있다면 해제 (Phase 7)
                if (currentLine >= 0 && currentSessionId != null) {
                    lockManager.releaseLock(currentLine);
                }
                lockManager.shutdown();
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
        // Phase 7.6 — getToolTipText를 override해서 hover 시 잠금 보유자 표시
        editorArea = new JTextArea() {
            @Override
            public String getToolTipText(MouseEvent event) {
                int offset = viewToModel2D(event.getPoint());
                if (offset < 0) return null;
                int line;
                try {
                    line = getLineOfOffset(offset);
                } catch (BadLocationException ex) {
                    return null;
                }
                String holder = lockManager.getRemoteHolder(line);
                if (holder != null) {
                    return "Line " + (line + 1) + ": locked by " + holder;
                }
                LockManager.State myState = lockManager.getState(line);
                if (myState == LockManager.State.HELD) {
                    return "Line " + (line + 1) + ": held by you";
                }
                if (myState == LockManager.State.REQUESTED) {
                    return "Line " + (line + 1) + ": acquiring lock...";
                }
                return null;
            }
        };
        // 짧은 hover 후 툴팁이 뜨도록 등록
        ToolTipManager.sharedInstance().registerComponent(editorArea);
        editorArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        editorArea.setLineWrap(true);
        editorArea.setWrapStyleWord(true);
        editorArea.setTabSize(4);

        // Phase 5.4 — 세션에 참여하기 전에는 편집 불가 (로비 상태)
        editorArea.setEditable(false);

        // Phase 7 — DocumentFilter로 잠금 미보유 편집 차단.
        // 원격 변경(isRemoteChange == true)은 항상 통과시킨다.
        ((AbstractDocument) editorArea.getDocument()).setDocumentFilter(new LockingDocumentFilter());

        // Phase 7 — 커서 이동(줄 변경) 시 자동으로 잠금 release/request
        editorArea.addCaretListener(e -> {
            if (isRemoteChange) return;
            if (currentSessionId == null) return;
            int dot = e.getDot();
            int newLine;
            try {
                newLine = editorArea.getLineOfOffset(dot);
            } catch (BadLocationException ex) {
                return;
            }
            if (newLine == currentLine) return;
            int oldLine = currentLine;
            currentLine = newLine;
            if (oldLine >= 0) lockManager.releaseLock(oldLine);
            lockManager.requestLock(newLine);
        });

        editorArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                trackLineShift(e); // Phase 7.7 — 항상 (로컬/원격 모두) 호출
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
                trackLineShift(e);
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
            rebindLockAfterRemoteChange();
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
            rebindLockAfterRemoteChange();
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
            rebindLockAfterRemoteChange();
        });
    }

    /**
     * Phase 7 버그 수정 — 원격 편집으로 인해 내 커서의 줄 번호가 shift되었는지 확인하고
     * lock을 재바인딩한다. CaretListener는 isRemoteChange 가드로 skip되므로 직접 처리해야 한다.
     *
     * 예: 줄 5에 잠금 보유 중 + 누군가 내 커서 이전 offset에 "\n"을 삽입 →
     *     내 커서가 줄 6으로 자동 이동했지만 currentLine은 stale 상태로 5로 남음 →
     *     줄 6에서 입력 시도하면 DocumentFilter가 차단.
     */
    /**
     * Phase 7.7 — DocumentListener에서 호출. 줄 개수가 바뀌었으면 LockManager에 shift를 알려
     * 내 상태와 원격 holders 맵의 region id를 조정한다. 모든 클라이언트가 같은 텍스트 이벤트를
     * 같은 순서로 받으므로 같은 shift가 적용되어 일관성이 유지된다.
     */
    private void trackLineShift(DocumentEvent e) {
        int newCount = editorArea.getLineCount();
        int delta = newCount - lastLineCount;
        lastLineCount = newCount;
        if (delta == 0) return;
        int changeLine;
        try {
            changeLine = editorArea.getLineOfOffset(e.getOffset());
        } catch (BadLocationException ex) {
            return;
        }
        lockManager.shiftRegions(changeLine, delta);
    }

    /**
     * Phase 7.6 — 잠금 상태를 줄 배경색으로 시각화.
     *  - 원격 사용자가 보유/획득 중인 줄: 분홍색
     *  - 내가 보유 중인 줄: 연녹색
     *  - 내가 응답 대기 중인 줄: 연노랑
     * onLockStateChanged 리스너 콜백에서 invokeLater로 호출됨 (EDT 보장).
     */
    private void redrawLockHighlights() {
        Highlighter h = editorArea.getHighlighter();
        for (Object tag : activeLockHighlights) {
            h.removeHighlight(tag);
        }
        activeLockHighlights.clear();

        if (currentSessionId == null) return;
        int totalLines = editorArea.getLineCount();

        // 원격 잠금 영역
        Map<Integer, String> remote = lockManager.getRemoteHoldersSnapshot();
        for (int regionId : remote.keySet()) {
            if (regionId < 0 || regionId >= totalLines) continue;
            // 내가 들고 있는 줄에는 내 색을 우선 (아래에서 다시 칠함)
            if (regionId == currentLine) {
                LockManager.State myState = lockManager.getState(regionId);
                if (myState == LockManager.State.HELD || myState == LockManager.State.REQUESTED) continue;
            }
            paintLine(h, regionId, REMOTE_LOCK_PAINTER);
        }

        // 내 상태
        if (currentLine >= 0 && currentLine < totalLines) {
            LockManager.State myState = lockManager.getState(currentLine);
            if (myState == LockManager.State.HELD) {
                paintLine(h, currentLine, MY_HELD_PAINTER);
            } else if (myState == LockManager.State.REQUESTED) {
                paintLine(h, currentLine, MY_PENDING_PAINTER);
            }
        }
    }

    private void paintLine(Highlighter h, int line, DefaultHighlighter.DefaultHighlightPainter painter) {
        try {
            int start = editorArea.getLineStartOffset(line);
            int end = editorArea.getLineEndOffset(line);
            // 빈 줄도 시각화될 수 있도록 end >= start 보장
            if (end > start) {
                Object tag = h.addHighlight(start, end, painter);
                activeLockHighlights.add(tag);
            } else if (end == start) {
                // 마지막 빈 줄: addHighlight(start, start)는 invisible — skip하거나 +1로 확장
                // 단순 skip
            }
        } catch (BadLocationException ex) {
            // 줄이 사라진 경우 무시
        }
    }

    private void rebindLockAfterRemoteChange() {
        if (currentSessionId == null) return;
        int newLine;
        try {
            newLine = editorArea.getLineOfOffset(editorArea.getCaretPosition());
        } catch (BadLocationException ex) {
            return;
        }
        if (newLine == currentLine) return;
        int oldLine = currentLine;
        currentLine = newLine;
        if (oldLine >= 0) lockManager.releaseLock(oldLine);
        lockManager.requestLock(newLine);
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

            // Phase 7 — 현재 참여 중인 세션의 참여자 목록이 바뀌었을 수 있으니 LockManager에 반영
            if (currentSessionId != null) {
                for (SessionInfo info : currentSessions) {
                    if (currentSessionId.equals(info.getSessionId()) && info.getParticipants() != null) {
                        lockManager.setPeers(info.getParticipants());
                        break;
                    }
                }
            }
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
                    // Phase 7 — joinSession()이 currentLine=-1로 만들었으므로 현재 커서 위치의
                    // 줄에 대해 lock을 재획득해야 한다. CaretListener의 newLine==currentLine 단락 평가가
                    // 같은 줄이라 발동하지 않으므로 명시적으로 트리거.
                    int line;
                    try {
                        line = editorArea.getLineOfOffset(editorArea.getCaretPosition());
                    } catch (BadLocationException ex) {
                        line = 0;
                    }
                    currentLine = line;
                    lockManager.requestLock(line);
                }
                return;
            }

            // Phase 7 — 이전 세션의 잠금 상태 정리
            if (currentLine >= 0 && currentSessionId != null) {
                lockManager.releaseLock(currentLine);
            }
            lockManager.reset();
            currentLine = -1;

            currentSessionId = resp.getSessionId();
            currentSessionName = resp.getSessionName();

            // Phase 7 — 새 세션 참여자로 peer 초기화
            if (resp.getParticipants() != null) {
                lockManager.setPeers(resp.getParticipants());
            }

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

            // Phase 7 — 커서가 있는 줄에 대해 잠금 요청 트리거
            int initialLine;
            try {
                initialLine = editorArea.getLineOfOffset(editorArea.getCaretPosition());
            } catch (BadLocationException ex) {
                initialLine = 0;
            }
            currentLine = initialLine;
            lockManager.requestLock(initialLine);
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
        // Phase 7 버그 수정 — SESSION_JOIN을 보내기 전에 OLD 세션의 잠금을 해제해야 한다.
        // 서버는 SESSION_JOIN을 처리하면서 즉시 currentSessionId를 새 세션으로 갱신하므로,
        // 응답 수신 후 release를 보내면 LOCK_RELEASE/deferred reply가 NEW 세션 참여자에게
        // 잘못 라우팅되고, OLD 세션의 대기 중인 요청자는 5초 timeout으로 강제 grant된다.
        if (currentLine >= 0 && currentSessionId != null) {
            lockManager.releaseLock(currentLine);
            currentLine = -1;
        }
        // 응답 도착 전 사이 입력으로 인한 race를 막기 위해 에디터를 잠시 잠근다.
        editorArea.setEditable(false);
        Message msg = new Message(MessageType.SESSION_JOIN, userId);
        msg.setPayloadFromObject(new SessionJoinRequest(sessionId));
        client.send(msg);
    }

    public void handleDisconnected() {
        SwingUtilities.invokeLater(() -> {
            setStatus("Disconnected from server.");
            // Phase 7 — 끊긴 후 사용자가 에디터를 만지면 CaretListener가 cancelled timer에 schedule을 시도해
            // 예외가 나거나 lock 상태가 망가질 수 있다. 모든 인터랙션을 차단한다.
            editorArea.setEditable(false);
            currentSessionId = null;
            currentLine = -1;
            lockManager.shutdown();
            // Phase 7.6 — shutdown은 notifyStateChanged를 호출하지 않으므로 명시적으로 highlight 제거
            redrawLockHighlights();
            JOptionPane.showMessageDialog(this,
                    "Lost connection to server.",
                    "Disconnected", JOptionPane.WARNING_MESSAGE);
        });
    }

    // ── Phase 7: 분산 mutex 메시지 수신 ──

    public void handleLockRequest(Message msg) {
        LockRequest payload = msg.getPayloadAs(LockRequest.class);
        String from = msg.getSender();
        lockManager.onLockRequest(from, payload.getRegionId(), payload.getTimestamp());
    }

    public void handleLockReply(Message msg) {
        LockReply payload = msg.getPayloadAs(LockReply.class);
        lockManager.onLockReply(msg.getSender(), payload.getRegionId(),
                payload.getRequestTimestamp(), payload.getRequestSender());
    }

    public void handleLockRelease(Message msg) {
        LockRelease payload = msg.getPayloadAs(LockRelease.class);
        lockManager.onLockRelease(msg.getSender(), payload.getRegionId());
    }

    /**
     * Phase 7 — 잠금 미보유 시 편집을 차단하는 DocumentFilter.
     *
     * - isRemoteChange == true: 원격 편집 적용 중 — 항상 통과
     * - 로비 상태(currentSessionId == null): 에디터가 read-only라 도달 안 함, 안전상 차단
     * - 해당 offset이 속한 줄에 대해 잠금을 보유 중이면 통과, 아니면 silently drop + status bar 안내
     */
    private class LockingDocumentFilter extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String text, AttributeSet attr) throws BadLocationException {
            // 삽입은 시작 줄만 보유하면 OK — 새로 생기는 줄은 내가 만드는 것이므로 안전.
            if (allowRange(offset, 0)) {
                fb.insertString(offset, text, attr);
            } else {
                notifyBlocked(offset);
            }
        }

        @Override
        public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
            // Phase 7.7 — 다중 줄 삭제 시 모든 영향 라인을 보유해야 한다.
            if (allowRange(offset, length)) {
                fb.remove(offset, length);
            } else {
                notifyBlocked(offset);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            // replace = remove + insert. 삭제 범위 안의 모든 라인이 우리 것이어야 한다.
            if (allowRange(offset, length)) {
                fb.replace(offset, length, text, attrs);
            } else {
                notifyBlocked(offset);
            }
        }

        /**
         * [offset, offset+length] 구간이 모두 우리가 보유한 라인 안에 있는지 검사한다.
         * length == 0이면 시작 라인만 검사 (insert).
         */
        private boolean allowRange(int offset, int length) {
            if (isRemoteChange) return true;
            if (currentSessionId == null) return false;
            int startLine, endLine;
            try {
                startLine = editorArea.getLineOfOffset(offset);
                int endOffset = (length > 0) ? offset + length - 1 : offset;
                int docLen = editorArea.getDocument().getLength();
                if (endOffset > docLen) endOffset = docLen;
                endLine = editorArea.getLineOfOffset(endOffset);
            } catch (BadLocationException ex) {
                return false;
            }
            for (int line = startLine; line <= endLine; line++) {
                if (!lockManager.holds(line)) return false;
            }
            return true;
        }

        private void notifyBlocked(int offset) {
            // 자주 호출되므로 1초당 한 번 정도로 throttle
            long now = System.currentTimeMillis();
            if (now - lockBlockNotifyAt < 1000) return;
            lockBlockNotifyAt = now;
            int line;
            try {
                line = editorArea.getLineOfOffset(offset);
            } catch (BadLocationException ex) {
                return;
            }
            LockManager.State st = lockManager.getState(line);
            String reason = (st == LockManager.State.REQUESTED)
                    ? "acquiring lock"
                    : "locked by another user";
            setStatus("Line " + (line + 1) + " " + reason + " — wait...");
            Toolkit.getDefaultToolkit().beep();
        }
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

package com.editor.server;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.NetworkUtil;
import com.editor.common.payload.LoginRequest;
import com.editor.common.payload.LoginResponse;
import com.editor.common.payload.RegisterRequest;
import com.editor.common.payload.RegisterResponse;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 개별 클라이언트 소켓을 담당하는 핸들러.
 * 스레드풀에서 실행되며, 메시지 수신 루프를 돌린다.
 */
public class ClientHandler implements Runnable {

    private final NetworkUtil networkUtil;
    private final ServerMain server;
    private volatile String userId;
    private volatile String currentSessionId; // Phase 5.4 — 현재 참여 중인 세션 ID (null이면 로비)
    private final AtomicBoolean disconnected = new AtomicBoolean(false);

    public ClientHandler(NetworkUtil networkUtil, ServerMain server) {
        this.networkUtil = networkUtil;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Message msg;
                try {
                    msg = networkUtil.receive();
                } catch (IOException e) {
                    System.err.println("[ERROR] Client communication error: " + e.getMessage());
                    break;
                } catch (RuntimeException e) {
                    // 잘못된 JSON 등 — 해당 메시지만 버리고 계속 수신
                    System.err.println("[WARN] Malformed message ignored: " + e.getMessage());
                    continue;
                }
                if (msg == null) break;
                try {
                    handleMessage(msg);
                } catch (RuntimeException e) {
                    // 핸들러 내부 예외 — 해당 메시지만 실패 처리, 핸들러 스레드는 유지
                    System.err.println("[ERROR] Handler error for " + msg.getType() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } finally {
            disconnect();
        }
    }

    private void handleMessage(Message msg) {
        switch (msg.getType()) {
            // ── 인증 ──
            case LOGIN_REQUEST:
                handleLogin(msg);
                break;
            case REGISTER_REQUEST:
                handleRegister(msg);
                break;
            case LOGOUT:
                handleLogout(msg);
                break;

            // ── 텍스트 편집 ──
            case TEXT_INSERT:
            case TEXT_DELETE:
            case TEXT_UPDATE:
                handleTextEdit(msg);
                break;

            // ── 세션 관리 ──
            case SESSION_CREATE:
            case SESSION_LIST_REQUEST:
            case SESSION_JOIN:
                handleSession(msg);
                break;

            // ── 동시성 제어 ──
            case LOCK_REQUEST:
            case LOCK_REPLY:
            case LOCK_RELEASE:
                handleLock(msg);
                break;

            // ── 실시간 편집 과정 ──
            case REALTIME_EDIT:
            case CURSOR_MOVE:
            case SELECTION:
                handleRealtime(msg);
                break;

            default:
                System.out.println("[WARN] Unknown message: [" + msg.getType() + "] from " + msg.getSender());
                break;
        }
    }

    // ── 인증 처리 ──

    private void handleLogin(Message msg) {
        // 이미 인증된 클라이언트가 LOGIN을 또 보내면 거부 (자기 자신을 ghost로 만드는 race 방지)
        if (this.userId != null) {
            Message reject = new Message(MessageType.LOGIN_RESPONSE, "server");
            reject.setPayloadFromObject(new LoginResponse(false, "Already authenticated.", null));
            networkUtil.send(reject);
            return;
        }

        LoginRequest req = msg.getPayloadAs(LoginRequest.class);
        AccountStore store = server.getAccountStore();

        String error = store.login(req.getUserId(), req.getPassword());

        Message response = new Message(MessageType.LOGIN_RESPONSE, "server");

        if (error != null) {
            // 로그인 실패
            response.setPayloadFromObject(new LoginResponse(false, error, null));
            networkUtil.send(response);
            System.out.println("[LOGIN FAIL] " + req.getUserId() + " — " + error);
            return;
        }

        // 로그인 성공 — 원자적으로 중복 접속 체크 + 등록
        this.userId = req.getUserId();
        if (!server.addClient(userId, this)) {
            this.userId = null;
            response.setPayloadFromObject(new LoginResponse(false, "Already logged in.", null));
            networkUtil.send(response);
            System.out.println("[LOGIN FAIL] " + req.getUserId() + " — duplicate login");
            return;
        }

        // 로그인 직후 클라이언트는 로비 상태(어떤 세션에도 참여하지 않음).
        // 텍스트 편집은 세션 안에서만 동작하므로 documentContent 동봉이 필요 없다 (Phase 5.4~5.5).
        response.setPayloadFromObject(new LoginResponse(true, "Login successful.", server.getOnlineUsers()));
        networkUtil.send(response);

        // 전체에게 입장 알림
        Message joinMsg = new Message(MessageType.USER_JOINED, "server");
        joinMsg.setPayloadFromObject(new UserEvent(userId));
        server.broadcast(joinMsg, userId);

        System.out.println("[LOGIN OK] " + userId);
    }

    private void handleRegister(Message msg) {
        RegisterRequest req = msg.getPayloadAs(RegisterRequest.class);
        AccountStore store = server.getAccountStore();

        String error = store.register(req.getUserId(), req.getPassword());

        Message response = new Message(MessageType.REGISTER_RESPONSE, "server");
        if (error != null) {
            response.setPayloadFromObject(new RegisterResponse(false, error));
            System.out.println("[REGISTER FAIL] " + req.getUserId() + " — " + error);
        } else {
            response.setPayloadFromObject(new RegisterResponse(true, "Registration successful."));
            System.out.println("[REGISTER OK] " + req.getUserId());
        }
        networkUtil.send(response);
    }

    private void handleLogout(Message msg) {
        System.out.println("[LOGOUT] " + userId);
        disconnect();
    }

    // ── 텍스트 편집 처리 ──

    private void handleTextEdit(Message msg) {
        String uid = this.userId; // 로컬 복사 — disconnect()와의 TOCTOU 방지
        if (uid == null) return; // 미인증 클라이언트 무시

        // Phase 5.4 — 텍스트 편집은 세션 안에서만 허용
        String sid = this.currentSessionId;
        if (sid == null) {
            System.out.println("[TEXT EDIT IGNORED] " + uid + " is not in any session");
            return;
        }
        Session session = server.getSessionStore().get(sid);
        if (session == null) {
            // 세션이 사라졌으면 로비로 되돌림
            this.currentSessionId = null;
            return;
        }

        // 세션 buffer 적용 + 같은 세션 참여자에게 broadcast를 같은 lock 안에서 원자적으로 수행.
        // SessionJoinResponse 전송(같은 buffer lock 보호)과 직렬화되어, 신규 참여자가 stale snapshot을 받는 race를 차단한다.
        DocumentBuffer buffer = session.getBuffer();
        synchronized (buffer) {
            boolean applied = false;
            switch (msg.getType()) {
                case TEXT_INSERT: {
                    TextInsert payload = msg.getPayloadAs(TextInsert.class);
                    applied = buffer.insert(payload.getOffset(), payload.getText());
                    System.out.println("[TEXT_INSERT] by " + uid + " in " + session.getSessionName()
                            + " offset=" + payload.getOffset()
                            + " text=\"" + payload.getText() + "\""
                            + " (bufferLen=" + buffer.length() + ")");
                    break;
                }
                case TEXT_DELETE: {
                    TextDelete payload = msg.getPayloadAs(TextDelete.class);
                    applied = buffer.delete(payload.getOffset(), payload.getLength());
                    System.out.println("[TEXT_DELETE] by " + uid + " in " + session.getSessionName()
                            + " offset=" + payload.getOffset()
                            + " length=" + payload.getLength()
                            + " (bufferLen=" + buffer.length() + ")");
                    break;
                }
                case TEXT_UPDATE: {
                    TextUpdate payload = msg.getPayloadAs(TextUpdate.class);
                    applied = buffer.update(payload.getOffset(), payload.getLength(), payload.getNewText());
                    System.out.println("[TEXT_UPDATE] by " + uid + " in " + session.getSessionName()
                            + " offset=" + payload.getOffset()
                            + " length=" + payload.getLength()
                            + " newText=\"" + payload.getNewText() + "\""
                            + " (bufferLen=" + buffer.length() + ")");
                    break;
                }
                default:
                    break;
            }

            if (!applied) {
                System.err.println("[TEXT EDIT REJECTED] " + msg.getType() + " from " + uid);
                return;
            }

            session.touchModified();
            // 같은 세션 참여자에게만 broadcast (송신자 제외)
            server.broadcastToSession(session, msg, uid);
        }
    }

    // ── 세션 관리 처리 ──

    private void handleSession(Message msg) {
        switch (msg.getType()) {
            case SESSION_CREATE:
                handleSessionCreate(msg);
                break;
            case SESSION_LIST_REQUEST:
                handleSessionListRequest(msg);
                break;
            case SESSION_JOIN:
                handleSessionJoin(msg);
                break;
            default:
                break;
        }
    }

    private void handleSessionCreate(Message msg) {
        String uid = this.userId;
        if (uid == null) {
            // 미인증 클라이언트는 세션을 만들 수 없다
            Message resp = new Message(MessageType.SESSION_CREATE_RESPONSE, "server");
            resp.setPayloadFromObject(new SessionCreateResponse(false, "Not authenticated.", null, null));
            networkUtil.send(resp);
            return;
        }

        SessionCreateRequest req = msg.getPayloadAs(SessionCreateRequest.class);
        String name = req == null ? null : req.getSessionName();

        Session created = server.getSessionStore().create(name);

        Message resp = new Message(MessageType.SESSION_CREATE_RESPONSE, "server");
        if (created == null) {
            resp.setPayloadFromObject(new SessionCreateResponse(false, "Session name is empty.", null, null));
            networkUtil.send(resp);
            System.out.println("[SESSION_CREATE FAIL] by " + uid + " — empty name");
            return;
        }

        resp.setPayloadFromObject(new SessionCreateResponse(
                true, "Session created.", created.getSessionId(), created.getSessionName()));
        networkUtil.send(resp);
        System.out.println("[SESSION_CREATE OK] " + created.getSessionName()
                + " (" + created.getSessionId() + ") by " + uid);

        // 전체 클라이언트에게 갱신된 세션 목록 push
        Message listMsg = new Message(MessageType.SESSION_LIST_RESPONSE, "server");
        listMsg.setPayloadFromObject(new SessionListResponse(buildSessionInfoList()));
        server.broadcast(listMsg);
    }

    private void handleSessionJoin(Message msg) {
        String uid = this.userId;
        if (uid == null) {
            sendJoinFailure("Not authenticated.");
            return;
        }

        SessionJoinRequest req = msg.getPayloadAs(SessionJoinRequest.class);
        String sid = (req == null) ? null : req.getSessionId();
        if (sid == null || sid.isEmpty()) {
            sendJoinFailure("Session ID required.");
            return;
        }

        Session target = server.getSessionStore().get(sid);
        if (target == null) {
            sendJoinFailure("Session not found.");
            return;
        }

        // 이미 같은 세션이면 idempotent하게 현재 상태 재전송
        String previousSid = this.currentSessionId;
        if (previousSid != null && !previousSid.equals(sid)) {
            Session prev = server.getSessionStore().get(previousSid);
            if (prev != null) {
                prev.removeParticipant(uid);
            }
        }

        // target session 추가 + 현재 내용 스냅샷 + 응답 전송을 buffer lock 안에서 원자적으로 수행.
        // handleTextEdit이 같은 lock으로 buffer + broadcast를 직렬화하므로,
        // SessionJoinResponse 전송 도중 같은 세션의 TEXT_INSERT가 먼저 도달하는 race를 차단한다.
        DocumentBuffer buffer = target.getBuffer();
        synchronized (buffer) {
            target.addParticipant(uid);
            this.currentSessionId = sid;

            String content = buffer.getText();
            Set<String> participants = target.getParticipants();
            List<String> participantList = new ArrayList<>(participants);

            Message resp = new Message(MessageType.SESSION_JOIN_RESPONSE, "server");
            resp.setPayloadFromObject(new SessionJoinResponse(
                    true, "Joined session.",
                    target.getSessionId(), target.getSessionName(),
                    content, participantList));
            networkUtil.send(resp);
        }

        System.out.println("[SESSION_JOIN OK] " + uid + " → " + target.getSessionName()
                + " (" + target.getSessionId() + ")");

        // 참여자 수가 변경되었으므로 전체에게 갱신된 세션 목록 push
        Message listMsg = new Message(MessageType.SESSION_LIST_RESPONSE, "server");
        listMsg.setPayloadFromObject(new SessionListResponse(buildSessionInfoList()));
        server.broadcast(listMsg);
    }

    private void sendJoinFailure(String reason) {
        Message resp = new Message(MessageType.SESSION_JOIN_RESPONSE, "server");
        resp.setPayloadFromObject(new SessionJoinResponse(false, reason, null, null, null, null));
        networkUtil.send(resp);
        System.out.println("[SESSION_JOIN FAIL] " + userId + " — " + reason);
    }

    private void handleSessionListRequest(Message msg) {
        String uid = this.userId;
        if (uid == null) return;

        Message resp = new Message(MessageType.SESSION_LIST_RESPONSE, "server");
        resp.setPayloadFromObject(new SessionListResponse(buildSessionInfoList()));
        networkUtil.send(resp);
        System.out.println("[SESSION_LIST] sent to " + uid + " (" + server.getSessionStore().size() + " sessions)");
    }

    private List<SessionInfo> buildSessionInfoList() {
        List<Session> all = server.getSessionStore().list();
        List<SessionInfo> infos = new ArrayList<>(all.size());
        for (Session s : all) {
            infos.add(new SessionInfo(
                    s.getSessionId(),
                    s.getSessionName(),
                    s.getParticipantCount(),
                    s.getLastModifiedAt()));
        }
        return infos;
    }

    // ── 동시성 제어 처리 ──

    private void handleLock(Message msg) {
        // TODO: Phase 7에서 구현
        System.out.println("[LOCK] " + msg.getType() + " from " + userId);
    }

    // ── 실시간 편집 과정 처리 ──

    private void handleRealtime(Message msg) {
        String uid = this.userId;
        if (uid == null) return;

        // 송신자 제외 전체에게 중계
        server.broadcast(msg, uid);
    }

    // ── 접속 해제 ──

    /**
     * 접속 해제 처리. AtomicBoolean으로 중복 실행을 방지한다.
     * - 핸들러 자신의 스레드(run finally)에서 호출될 수 있고
     * - 다른 스레드(broadcast 전송 실패)에서도 호출될 수 있으므로
     *   한 번만 실행되도록 보장한다.
     */
    private void disconnect() {
        if (!disconnected.compareAndSet(false, true)) {
            return; // 이미 해제 처리됨
        }

        String uid = this.userId;
        this.userId = null;

        // Phase 5.4 — 참여 중인 세션에서 빠지기 (참여자 수 업데이트 위해 broadcast)
        String sid = this.currentSessionId;
        this.currentSessionId = null;
        boolean leftSession = false;
        if (sid != null && uid != null) {
            Session session = server.getSessionStore().get(sid);
            if (session != null) {
                leftSession = session.removeParticipant(uid);
            }
        }

        if (uid != null) {
            server.removeClient(uid);

            // 전체에게 퇴장 알림
            Message leftMsg = new Message(MessageType.USER_LEFT, "server");
            leftMsg.setPayloadFromObject(new UserEvent(uid));
            server.broadcast(leftMsg);

            // 세션 참여자가 감소했다면 세션 목록도 갱신해서 전체에게 push
            if (leftSession) {
                Message listMsg = new Message(MessageType.SESSION_LIST_RESPONSE, "server");
                listMsg.setPayloadFromObject(new SessionListResponse(buildSessionInfoList()));
                server.broadcast(listMsg);
            }
        }
        networkUtil.close();
    }

    /** 전송 실패로 인한 강제 연결 해제 (broadcast/sendTo에서 호출) */
    public void disconnectOnSendFailure() {
        System.out.println("[FORCE DISCONNECT] " + userId + " — send failure");
        disconnect();
    }

    public NetworkUtil getNetworkUtil() {
        return networkUtil;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}

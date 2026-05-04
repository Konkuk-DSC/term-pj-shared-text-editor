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
import com.editor.common.payload.SessionListResponse;
import com.editor.common.payload.TextDelete;
import com.editor.common.payload.TextInsert;
import com.editor.common.payload.TextUpdate;
import com.editor.common.payload.UserEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 개별 클라이언트 소켓을 담당하는 핸들러.
 * 스레드풀에서 실행되며, 메시지 수신 루프를 돌린다.
 */
public class ClientHandler implements Runnable {

    private final NetworkUtil networkUtil;
    private final ServerMain server;
    private volatile String userId;
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

        // 로그인 성공 시 현재 문서 전체 내용 + LoginResponse 전송을 buffer lock 안에서 원자적으로 수행.
        // handleTextEdit이 같은 lock을 잡고 buffer 적용 + broadcast 하므로,
        // LoginResponse 전송 도중 다른 클라이언트의 INSERT 메시지가 이 클라이언트에 먼저 도착하는 race를 차단한다.
        DocumentBuffer buffer = server.getDocumentBuffer();
        synchronized (buffer) {
            String currentDoc = buffer.getText();
            response.setPayloadFromObject(new LoginResponse(true, "Login successful.", server.getOnlineUsers(), currentDoc));
            networkUtil.send(response);
        }

        // 전체에게 입장 알림 (buffer와 무관하므로 lock 밖에서 broadcast)
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

        // buffer 적용 + broadcast를 같은 lock 안에서 원자적으로 수행.
        // handleLogin의 LoginResponse 전송과 직렬화되어, 신규 접속자가 stale snapshot을 받는 race를 차단한다.
        DocumentBuffer buffer = server.getDocumentBuffer();
        synchronized (buffer) {
            boolean applied = false;
            switch (msg.getType()) {
                case TEXT_INSERT: {
                    TextInsert payload = msg.getPayloadAs(TextInsert.class);
                    applied = buffer.insert(payload.getOffset(), payload.getText());
                    System.out.println("[TEXT_INSERT] by " + uid
                            + " offset=" + payload.getOffset()
                            + " text=\"" + payload.getText() + "\""
                            + " (bufferLen=" + buffer.length() + ")");
                    break;
                }
                case TEXT_DELETE: {
                    TextDelete payload = msg.getPayloadAs(TextDelete.class);
                    applied = buffer.delete(payload.getOffset(), payload.getLength());
                    System.out.println("[TEXT_DELETE] by " + uid
                            + " offset=" + payload.getOffset()
                            + " length=" + payload.getLength()
                            + " (bufferLen=" + buffer.length() + ")");
                    break;
                }
                case TEXT_UPDATE: {
                    TextUpdate payload = msg.getPayloadAs(TextUpdate.class);
                    applied = buffer.update(payload.getOffset(), payload.getLength(), payload.getNewText());
                    System.out.println("[TEXT_UPDATE] by " + uid
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

            // 송신자 제외 전체에게 브로드캐스트 (lock 안에서 수행하여 LoginResponse와 순서 보장)
            server.broadcast(msg, uid);
        }
    }

    // ── 세션 관리 처리 ──

    private void handleSession(Message msg) {
        switch (msg.getType()) {
            case SESSION_CREATE:
                handleSessionCreate(msg);
                break;
            case SESSION_LIST_REQUEST:
            case SESSION_JOIN:
                // TODO: 5.3 / 5.4에서 구현
                System.out.println("[SESSION TODO] " + msg.getType() + " from " + userId);
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

        if (uid != null) {
            server.removeClient(uid);

            // 전체에게 퇴장 알림
            Message leftMsg = new Message(MessageType.USER_LEFT, "server");
            leftMsg.setPayloadFromObject(new UserEvent(uid));
            server.broadcast(leftMsg);
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

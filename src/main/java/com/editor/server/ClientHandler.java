package com.editor.server;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.NetworkUtil;
import com.editor.common.payload.LoginRequest;
import com.editor.common.payload.LoginResponse;
import com.editor.common.payload.RegisterRequest;
import com.editor.common.payload.RegisterResponse;
import com.editor.common.payload.UserEvent;

import java.io.IOException;
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
            Message msg;
            while ((msg = networkUtil.receive()) != null) {
                handleMessage(msg);
            }
        } catch (IOException e) {
            System.err.println("[ERROR] Client communication error: " + e.getMessage());
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

        // 이미 접속 중인 사용자 체크
        if (server.getOnlineUsers().contains(req.getUserId())) {
            response.setPayloadFromObject(new LoginResponse(false, "Already logged in.", null));
            networkUtil.send(response);
            System.out.println("[LOGIN FAIL] " + req.getUserId() + " — duplicate login");
            return;
        }

        // 로그인 성공
        this.userId = req.getUserId();
        server.addClient(userId, this);

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
        if (userId == null) return; // 미인증 클라이언트 무시

        // 송신자 제외 전체에게 브로드캐스트
        server.broadcast(msg, userId);
    }

    // ── 세션 관리 처리 ──

    private void handleSession(Message msg) {
        // TODO: Phase 5에서 구현
        System.out.println("[SESSION] " + msg.getType() + " from " + userId);
    }

    // ── 동시성 제어 처리 ──

    private void handleLock(Message msg) {
        // TODO: Phase 7에서 구현
        System.out.println("[LOCK] " + msg.getType() + " from " + userId);
    }

    // ── 실시간 편집 과정 처리 ──

    private void handleRealtime(Message msg) {
        if (userId == null) return;

        // 송신자 제외 전체에게 중계
        server.broadcast(msg, userId);
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

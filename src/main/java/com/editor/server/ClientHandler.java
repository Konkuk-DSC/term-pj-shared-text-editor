package com.editor.server;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.NetworkUtil;
import com.editor.common.payload.UserEvent;

import java.io.IOException;

/**
 * 개별 클라이언트 소켓을 담당하는 핸들러.
 * 스레드풀에서 실행되며, 메시지 수신 루프를 돌린다.
 */
public class ClientHandler implements Runnable {

    private final NetworkUtil networkUtil;
    private final ServerMain server;
    private String userId;

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
            System.err.println("클라이언트 통신 오류: " + e.getMessage());
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
                System.out.println("알 수 없는 메시지: [" + msg.getType() + "] from " + msg.getSender());
                break;
        }
    }

    // ── 인증 처리 ──

    private void handleLogin(Message msg) {
        // TODO: Phase 2.5에서 계정 검증 로직 구현
        System.out.println("[로그인 요청] from " + msg.getSender());
    }

    private void handleRegister(Message msg) {
        // TODO: Phase 2.4에서 회원가입 로직 구현
        System.out.println("[회원가입 요청] from " + msg.getSender());
    }

    private void handleLogout(Message msg) {
        System.out.println("[로그아웃] " + userId);
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
        System.out.println("[세션] " + msg.getType() + " from " + userId);
    }

    // ── 동시성 제어 처리 ──

    private void handleLock(Message msg) {
        // TODO: Phase 7에서 구현
        System.out.println("[잠금] " + msg.getType() + " from " + userId);
    }

    // ── 실시간 편집 과정 처리 ──

    private void handleRealtime(Message msg) {
        if (userId == null) return;

        // 송신자 제외 전체에게 중계
        server.broadcast(msg, userId);
    }

    // ── 접속 해제 ──

    private void disconnect() {
        if (userId != null) {
            server.removeClient(userId);

            // 전체에게 퇴장 알림
            Message leftMsg = new Message(MessageType.USER_LEFT, "server");
            leftMsg.setPayloadFromObject(new UserEvent(userId));
            server.broadcast(leftMsg);
        }
        networkUtil.close();
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

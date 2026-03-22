package com.editor.server;

import com.editor.common.Message;
import com.editor.common.NetworkUtil;

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
            // TODO: Phase 2.2 이후에서 메시지 타입별 처리 구현
            default:
                System.out.println("수신: [" + msg.getType() + "] from " + msg.getSender());
                break;
        }
    }

    private void disconnect() {
        System.out.println("클라이언트 연결 해제: " + (userId != null ? userId : "미인증"));
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

package com.editor.client;

import com.editor.common.Message;
import com.editor.common.NetworkUtil;

import java.io.IOException;

/**
 * 별도 스레드에서 서버 메시지를 계속 수신하고,
 * 메시지 타입에 따라 MessageListener의 적절한 콜백을 호출한다.
 */
public class MessageReceiver implements Runnable {

    private final NetworkUtil networkUtil;
    private final MessageListener listener;
    private volatile boolean running = true;

    public MessageReceiver(NetworkUtil networkUtil, MessageListener listener) {
        this.networkUtil = networkUtil;
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            Message msg;
            while (running && (msg = networkUtil.receive()) != null) {
                dispatch(msg);
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("[ERROR] Failed to receive message: " + e.getMessage());
            }
        } finally {
            if (running) {
                running = false;
                listener.onDisconnected();
            }
        }
    }

    private void dispatch(Message msg) {
        switch (msg.getType()) {
            // 인증
            case LOGIN_RESPONSE:
                listener.onLoginResponse(msg);
                break;
            case REGISTER_RESPONSE:
                listener.onRegisterResponse(msg);
                break;

            // 접속 상태
            case USER_JOINED:
                listener.onUserJoined(msg);
                break;
            case USER_LEFT:
                listener.onUserLeft(msg);
                break;
            case USER_LIST:
                listener.onUserList(msg);
                break;

            // 텍스트 편집
            case TEXT_INSERT:
                listener.onTextInsert(msg);
                break;
            case TEXT_DELETE:
                listener.onTextDelete(msg);
                break;
            case TEXT_UPDATE:
                listener.onTextUpdate(msg);
                break;

            // 실시간 편집 과정
            case REALTIME_EDIT:
                listener.onRealtimeEdit(msg);
                break;
            case CURSOR_MOVE:
                listener.onCursorMove(msg);
                break;
            case SELECTION:
                listener.onSelection(msg);
                break;

            default:
                System.out.println("[RECV] Unhandled message: " + msg.getType());
                break;
        }
    }

    public void stop() {
        running = false;
    }
}

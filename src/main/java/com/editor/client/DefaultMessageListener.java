package com.editor.client;

import com.editor.common.Message;
import com.editor.common.payload.LoginResponse;
import com.editor.common.payload.RegisterResponse;
import com.editor.common.payload.UserEvent;

/**
 * 콘솔 출력용 기본 MessageListener 구현.
 * Phase 3.3에서 UI 리스너로 교체된다.
 */
public class DefaultMessageListener implements MessageListener {

    @Override
    public void onLoginResponse(Message msg) {
        LoginResponse resp = msg.getPayloadAs(LoginResponse.class);
        if (resp.isSuccess()) {
            System.out.println("[로그인 성공] " + resp.getMessage());
            System.out.println("접속자: " + resp.getOnlineUsers());
        } else {
            System.out.println("[로그인 실패] " + resp.getMessage());
        }
    }

    @Override
    public void onRegisterResponse(Message msg) {
        RegisterResponse resp = msg.getPayloadAs(RegisterResponse.class);
        System.out.println("[회원가입] " + resp.getMessage());
    }

    @Override
    public void onUserJoined(Message msg) {
        UserEvent event = msg.getPayloadAs(UserEvent.class);
        System.out.println("[접속] " + event.getUserId() + " 님이 접속했습니다.");
    }

    @Override
    public void onUserLeft(Message msg) {
        UserEvent event = msg.getPayloadAs(UserEvent.class);
        System.out.println("[퇴장] " + event.getUserId() + " 님이 나갔습니다.");
    }

    @Override
    public void onUserList(Message msg) {
        System.out.println("[접속자 목록] " + msg.getPayload());
    }

    @Override
    public void onTextInsert(Message msg) {
        System.out.println("[텍스트 삽입] from " + msg.getSender());
    }

    @Override
    public void onTextDelete(Message msg) {
        System.out.println("[텍스트 삭제] from " + msg.getSender());
    }

    @Override
    public void onTextUpdate(Message msg) {
        System.out.println("[텍스트 수정] from " + msg.getSender());
    }

    @Override
    public void onRealtimeEdit(Message msg) {
        // Phase 8에서 상세 구현
    }

    @Override
    public void onCursorMove(Message msg) {
        // Phase 8에서 상세 구현
    }

    @Override
    public void onSelection(Message msg) {
        // Phase 8에서 상세 구현
    }

    @Override
    public void onDisconnected() {
        System.out.println("[연결 끊김] 서버와의 연결이 종료되었습니다.");
    }
}

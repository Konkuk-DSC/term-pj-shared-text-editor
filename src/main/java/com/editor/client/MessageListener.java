package com.editor.client;

import com.editor.common.Message;

/**
 * 서버로부터 수신한 메시지를 처리하는 콜백 인터페이스.
 * UI 또는 로직 레이어에서 구현하여 MessageReceiver에 등록한다.
 */
public interface MessageListener {

    void onLoginResponse(Message msg);

    void onRegisterResponse(Message msg);

    void onUserJoined(Message msg);

    void onUserLeft(Message msg);

    void onUserList(Message msg);

    void onTextInsert(Message msg);

    void onTextDelete(Message msg);

    void onTextUpdate(Message msg);

    void onRealtimeEdit(Message msg);

    void onCursorMove(Message msg);

    void onSelection(Message msg);

    /** 서버와의 연결이 끊어졌을 때 호출 */
    void onDisconnected();
}

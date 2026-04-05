package com.editor.client;

import com.editor.common.Message;
import com.editor.common.payload.LoginResponse;
import com.editor.common.payload.RegisterResponse;
import com.editor.common.payload.UserEvent;

/**
 * 기본 MessageListener 구현.
 * 콘솔 출력 + LoginFrame UI 위임.
 */
public class DefaultMessageListener implements MessageListener {

    private LoginFrame loginFrame;

    public void setLoginFrame(LoginFrame loginFrame) {
        this.loginFrame = loginFrame;
    }

    private MainFrame getMainFrame() {
        return (loginFrame != null) ? loginFrame.getMainFrame() : null;
    }

    @Override
    public void onLoginResponse(Message msg) {
        LoginResponse resp = msg.getPayloadAs(LoginResponse.class);
        if (resp.isSuccess()) {
            System.out.println("[LOGIN OK] " + resp.getMessage());
            System.out.println("Online users: " + resp.getOnlineUsers());
        } else {
            System.out.println("[LOGIN FAIL] " + resp.getMessage());
        }
        if (loginFrame != null) {
            loginFrame.handleLoginResponse(msg);
        }
    }

    @Override
    public void onRegisterResponse(Message msg) {
        RegisterResponse resp = msg.getPayloadAs(RegisterResponse.class);
        System.out.println("[REGISTER] " + resp.getMessage());
        if (loginFrame != null) {
            loginFrame.handleRegisterResponse(msg);
        }
    }

    @Override
    public void onUserJoined(Message msg) {
        UserEvent event = msg.getPayloadAs(UserEvent.class);
        System.out.println("[JOINED] " + event.getUserId());
        MainFrame mainFrame = getMainFrame();
        if (mainFrame != null) {
            mainFrame.handleUserJoined(msg);
        }
    }

    @Override
    public void onUserLeft(Message msg) {
        UserEvent event = msg.getPayloadAs(UserEvent.class);
        System.out.println("[LEFT] " + event.getUserId());
        MainFrame mainFrame = getMainFrame();
        if (mainFrame != null) {
            mainFrame.handleUserLeft(msg);
        }
    }

    @Override
    public void onUserList(Message msg) {
        System.out.println("[USER LIST] " + msg.getPayload());
    }

    @Override
    public void onTextInsert(Message msg) {
        System.out.println("[TEXT INSERT] from " + msg.getSender());
    }

    @Override
    public void onTextDelete(Message msg) {
        System.out.println("[TEXT DELETE] from " + msg.getSender());
    }

    @Override
    public void onTextUpdate(Message msg) {
        System.out.println("[TEXT UPDATE] from " + msg.getSender());
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
        System.out.println("[DISCONNECTED] Connection to server lost.");
        MainFrame mainFrame = getMainFrame();
        if (mainFrame != null) {
            mainFrame.handleDisconnected();
        } else if (loginFrame != null) {
            loginFrame.handleDisconnected();
        }
    }
}

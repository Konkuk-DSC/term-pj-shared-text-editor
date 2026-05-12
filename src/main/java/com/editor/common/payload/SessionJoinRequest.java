package com.editor.common.payload;

public class SessionJoinRequest {
    private String sessionId;

    public SessionJoinRequest() {}

    public SessionJoinRequest(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}

package com.editor.common.payload;

public class SessionCreateRequest {
    private String sessionName;

    public SessionCreateRequest() {}

    public SessionCreateRequest(String sessionName) {
        this.sessionName = sessionName;
    }

    public String getSessionName() { return sessionName; }
    public void setSessionName(String sessionName) { this.sessionName = sessionName; }
}

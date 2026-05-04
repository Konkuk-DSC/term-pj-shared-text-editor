package com.editor.common.payload;

public class SessionCreateResponse {
    private boolean success;
    private String message;
    private String sessionId;
    private String sessionName;

    public SessionCreateResponse() {}

    public SessionCreateResponse(boolean success, String message, String sessionId, String sessionName) {
        this.success = success;
        this.message = message;
        this.sessionId = sessionId;
        this.sessionName = sessionName;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getSessionName() { return sessionName; }
    public void setSessionName(String sessionName) { this.sessionName = sessionName; }
}

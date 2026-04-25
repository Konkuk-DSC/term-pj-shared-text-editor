package com.editor.common.payload;

import java.util.List;

public class LoginResponse {
    private boolean success;
    private String message;
    private List<String> onlineUsers;
    private String documentContent;

    public LoginResponse() {}

    public LoginResponse(boolean success, String message, List<String> onlineUsers) {
        this(success, message, onlineUsers, null);
    }

    public LoginResponse(boolean success, String message, List<String> onlineUsers, String documentContent) {
        this.success = success;
        this.message = message;
        this.onlineUsers = onlineUsers;
        this.documentContent = documentContent;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<String> getOnlineUsers() { return onlineUsers; }
    public void setOnlineUsers(List<String> onlineUsers) { this.onlineUsers = onlineUsers; }

    public String getDocumentContent() { return documentContent; }
    public void setDocumentContent(String documentContent) { this.documentContent = documentContent; }
}

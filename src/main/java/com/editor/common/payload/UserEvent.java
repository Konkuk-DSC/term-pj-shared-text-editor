package com.editor.common.payload;

public class UserEvent {
    private String userId;

    public UserEvent() {}

    public UserEvent(String userId) {
        this.userId = userId;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}

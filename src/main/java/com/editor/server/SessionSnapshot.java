package com.editor.server;

/**
 * 세션 영속화용 DTO (Phase 5.5).
 * JSON 직렬화/역직렬화 대상이며, 런타임 상태(참여자 목록)는 포함하지 않는다.
 */
public class SessionSnapshot {

    private String sessionId;
    private String sessionName;
    private String content;
    private long createdAt;
    private long lastModifiedAt;

    public SessionSnapshot() {
        // Gson용 기본 생성자
    }

    public SessionSnapshot(String sessionId, String sessionName, String content,
                           long createdAt, long lastModifiedAt) {
        this.sessionId = sessionId;
        this.sessionName = sessionName;
        this.content = content;
        this.createdAt = createdAt;
        this.lastModifiedAt = lastModifiedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSessionName() {
        return sessionName;
    }

    public String getContent() {
        return content;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastModifiedAt() {
        return lastModifiedAt;
    }
}

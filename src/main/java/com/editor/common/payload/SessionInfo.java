package com.editor.common.payload;

import java.util.List;

/**
 * 세션 목록 항목 DTO.
 * 클라이언트가 세션 목록 화면에 표시할 메타데이터를 담는다.
 * Phase 7에서 participants 필드 추가 — LockManager가 peer 집합을 갱신하는 데 사용.
 */
public class SessionInfo {
    private String sessionId;
    private String sessionName;
    private int participantCount;
    private long lastModifiedAt;
    private List<String> participants;

    public SessionInfo() {}

    public SessionInfo(String sessionId, String sessionName, int participantCount, long lastModifiedAt) {
        this(sessionId, sessionName, participantCount, lastModifiedAt, null);
    }

    public SessionInfo(String sessionId, String sessionName, int participantCount,
                       long lastModifiedAt, List<String> participants) {
        this.sessionId = sessionId;
        this.sessionName = sessionName;
        this.participantCount = participantCount;
        this.lastModifiedAt = lastModifiedAt;
        this.participants = participants;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getSessionName() { return sessionName; }
    public void setSessionName(String sessionName) { this.sessionName = sessionName; }

    public int getParticipantCount() { return participantCount; }
    public void setParticipantCount(int participantCount) { this.participantCount = participantCount; }

    public long getLastModifiedAt() { return lastModifiedAt; }
    public void setLastModifiedAt(long lastModifiedAt) { this.lastModifiedAt = lastModifiedAt; }

    public List<String> getParticipants() { return participants; }
    public void setParticipants(List<String> participants) { this.participants = participants; }
}

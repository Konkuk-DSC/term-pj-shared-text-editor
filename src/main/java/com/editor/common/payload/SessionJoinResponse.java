package com.editor.common.payload;

import java.util.List;

/**
 * 세션 참여 응답.
 * 성공 시: 해당 세션의 현재 문서 전체 내용 + 현재 참여자 목록 동봉 (Late-comer 동기화).
 */
public class SessionJoinResponse {
    private boolean success;
    private String message;
    private String sessionId;
    private String sessionName;
    private String documentContent;
    private List<String> participants;

    public SessionJoinResponse() {}

    public SessionJoinResponse(boolean success, String message, String sessionId,
                                String sessionName, String documentContent, List<String> participants) {
        this.success = success;
        this.message = message;
        this.sessionId = sessionId;
        this.sessionName = sessionName;
        this.documentContent = documentContent;
        this.participants = participants;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getSessionName() { return sessionName; }
    public void setSessionName(String sessionName) { this.sessionName = sessionName; }

    public String getDocumentContent() { return documentContent; }
    public void setDocumentContent(String documentContent) { this.documentContent = documentContent; }

    public List<String> getParticipants() { return participants; }
    public void setParticipants(List<String> participants) { this.participants = participants; }
}

package com.editor.common.payload;

import java.util.List;

/**
 * 세션 목록 응답.
 * SESSION_LIST_REQUEST 응답으로도 사용되고,
 * 세션 생성/삭제 시 전체 클라이언트에게 push 통보로도 사용된다.
 */
public class SessionListResponse {
    private List<SessionInfo> sessions;

    public SessionListResponse() {}

    public SessionListResponse(List<SessionInfo> sessions) {
        this.sessions = sessions;
    }

    public List<SessionInfo> getSessions() { return sessions; }
    public void setSessions(List<SessionInfo> sessions) { this.sessions = sessions; }
}

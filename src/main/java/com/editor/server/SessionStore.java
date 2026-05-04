package com.editor.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서버 측 세션 목록 관리소.
 * sessionId → Session 매핑을 thread-safe하게 보관한다.
 */
public class SessionStore {

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 새 세션을 생성하여 등록한다.
     * 이름이 비어있으면 null 반환.
     */
    public Session create(String sessionName) {
        if (sessionName == null || sessionName.isBlank()) {
            return null;
        }
        String id = UUID.randomUUID().toString();
        Session session = new Session(id, sessionName.trim());
        sessions.put(id, session);
        return session;
    }

    public Session get(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessions.get(sessionId);
    }

    /** 현재 모든 세션의 스냅샷 */
    public List<Session> list() {
        return new ArrayList<>(sessions.values());
    }

    public Session remove(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessions.remove(sessionId);
    }

    public int size() {
        return sessions.size();
    }
}

package com.editor.server;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 공유 편집 세션.
 * 각 세션은 자체 DocumentBuffer와 참여자 목록을 가진다.
 *
 * 동시성:
 *  - 텍스트 자체의 동시 편집은 내부 {@link DocumentBuffer}가 synchronized로 보호한다.
 *  - 메타데이터(이름, 참여자, 수정 시각) 접근은 본 클래스의 synchronized 메서드로 직렬화한다.
 */
public class Session {

    private final String sessionId;
    private final String sessionName;
    private final DocumentBuffer buffer;
    private final long createdAt;
    private long lastModifiedAt;
    private final Set<String> participants = new LinkedHashSet<>();

    public Session(String sessionId, String sessionName) {
        this.sessionId = sessionId;
        this.sessionName = sessionName;
        this.buffer = new DocumentBuffer();
        this.createdAt = System.currentTimeMillis();
        this.lastModifiedAt = this.createdAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSessionName() {
        return sessionName;
    }

    public DocumentBuffer getBuffer() {
        return buffer;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public synchronized long getLastModifiedAt() {
        return lastModifiedAt;
    }

    /** 편집이 발생했음을 기록. 호출 측에서 buffer 변경 직후 호출한다. */
    public synchronized void touchModified() {
        lastModifiedAt = System.currentTimeMillis();
    }

    /** 새 참여자 등록. 이미 있으면 false. */
    public synchronized boolean addParticipant(String userId) {
        return participants.add(userId);
    }

    /** 참여자 제거. 없었으면 false. */
    public synchronized boolean removeParticipant(String userId) {
        return participants.remove(userId);
    }

    /** 현재 참여자 스냅샷 (방어적 복사) */
    public synchronized Set<String> getParticipants() {
        return new LinkedHashSet<>(participants);
    }

    public synchronized int getParticipantCount() {
        return participants.size();
    }
}

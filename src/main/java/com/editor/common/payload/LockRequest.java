package com.editor.common.payload;

/**
 * Phase 7 — Ricart-Agrawala 분산 mutex의 잠금 요청 메시지.
 * 요청자가 특정 영역(regionId)의 잠금을 얻고자 할 때 같은 세션 참여자에게 broadcast된다.
 *
 * - regionId: 잠그려는 영역 (현재 구현: 줄 번호)
 * - timestamp: Lamport 논리 시계 값. 동시 요청 충돌 시 우선순위 결정에 사용 (작을수록 우선)
 *
 * 요청자(sender)는 Message.sender 필드에 담긴다.
 */
public class LockRequest {
    private int regionId;
    private long timestamp;

    public LockRequest() {}

    public LockRequest(int regionId, long timestamp) {
        this.regionId = regionId;
        this.timestamp = timestamp;
    }

    public int getRegionId() { return regionId; }
    public void setRegionId(int regionId) { this.regionId = regionId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}

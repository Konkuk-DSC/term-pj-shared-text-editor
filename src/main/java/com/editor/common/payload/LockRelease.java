package com.editor.common.payload;

/**
 * Phase 7 — 잠금 해제 알림 메시지.
 * R-A 알고리즘 자체는 deferred reply로 잠금 이양이 일어나므로 LOCK_RELEASE는 필수가 아니지만,
 * UI 표시(다른 사용자의 영역이 풀렸음을 시각적으로 알림) 및 디버깅을 위해 broadcast한다.
 *
 * - regionId: 해제된 영역
 * 해제자(sender)는 Message.sender 필드에 담긴다.
 */
public class LockRelease {
    private int regionId;

    public LockRelease() {}

    public LockRelease(int regionId) {
        this.regionId = regionId;
    }

    public int getRegionId() { return regionId; }
    public void setRegionId(int regionId) { this.regionId = regionId; }
}

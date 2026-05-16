package com.editor.common.payload;

/**
 * Phase 7 — Ricart-Agrawala 분산 mutex의 잠금 응답 메시지.
 * 어떤 LOCK_REQUEST에 대한 응답인지 식별하기 위해 (regionId, requestTimestamp, requestSender) 3개를 함께 담는다.
 *
 * - regionId: 응답 대상 영역
 * - requestTimestamp: 원본 요청의 Lamport timestamp (stale reply 식별용)
 * - requestSender: 원본 요청을 보낸 userId. 다른 사용자에게도 reply가 broadcast될 수 있으므로
 *                  수신 측에서 자신이 대상인지 확인할 때 사용한다.
 *
 * 응답자(sender)는 Message.sender 필드에 담긴다.
 */
public class LockReply {
    private int regionId;
    private long requestTimestamp;
    private String requestSender;

    public LockReply() {}

    public LockReply(int regionId, long requestTimestamp, String requestSender) {
        this.regionId = regionId;
        this.requestTimestamp = requestTimestamp;
        this.requestSender = requestSender;
    }

    public int getRegionId() { return regionId; }
    public void setRegionId(int regionId) { this.regionId = regionId; }

    public long getRequestTimestamp() { return requestTimestamp; }
    public void setRequestTimestamp(long requestTimestamp) { this.requestTimestamp = requestTimestamp; }

    public String getRequestSender() { return requestSender; }
    public void setRequestSender(String requestSender) { this.requestSender = requestSender; }
}

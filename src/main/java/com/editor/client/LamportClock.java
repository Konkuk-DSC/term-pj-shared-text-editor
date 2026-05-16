package com.editor.client;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 7 — Lamport 논리 시계.
 *
 * 이벤트마다 단조 증가하는 정수 시각을 발급한다.
 * - tick(): 로컬 이벤트 발생 → +1 후 반환
 * - update(remote): 메시지 수신 시 max(local, remote) + 1로 갱신
 *
 * 모든 메서드는 AtomicLong 기반으로 thread-safe하다.
 */
public class LamportClock {

    private final AtomicLong clock = new AtomicLong(0);

    /** 로컬 이벤트 발생 — 시각을 1 증가시키고 반환. */
    public long tick() {
        return clock.incrementAndGet();
    }

    /** 외부에서 받은 timestamp로 시각을 max(local, remote) + 1로 갱신. */
    public long update(long remote) {
        return clock.updateAndGet(local -> Math.max(local, remote) + 1);
    }

    /** 현재 시각 조회. */
    public long get() {
        return clock.get();
    }
}

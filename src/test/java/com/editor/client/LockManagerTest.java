package com.editor.client;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.payload.LockReply;
import com.editor.common.payload.LockRequest;
import com.editor.common.payload.LockRelease;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7 — LockManager (Ricart-Agrawala) 알고리즘 단위 테스트.
 *
 * Timer 기반 타임아웃 회피를 위해 모든 케이스는 명시적 시점에 해결되도록 구성한다.
 */
class LockManagerTest {

    private static class TestSender implements LockManager.MessageSender {
        final List<Message> sent = new ArrayList<>();
        @Override
        public void send(Message msg) { sent.add(msg); }
        Message lastOfType(MessageType type) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i).getType() == type) return sent.get(i);
            }
            return null;
        }
        int countOfType(MessageType type) {
            int c = 0;
            for (Message m : sent) if (m.getType() == type) c++;
            return c;
        }
    }

    private static class TestListener implements LockManager.LockListener {
        final List<Integer> acquired = new ArrayList<>();
        final List<Integer> timeouts = new ArrayList<>();
        @Override
        public void onLockAcquired(int regionId) { acquired.add(regionId); }
        @Override
        public void onLockTimeout(int regionId) { timeouts.add(regionId); }
    }

    @Test
    void requestLockWithNoPeersGrantsImmediately() {
        TestSender sender = new TestSender();
        TestListener listener = new TestListener();
        LockManager lm = new LockManager("alice", sender, listener);
        lm.setPeers(java.util.Collections.emptyList());

        lm.requestLock(3);

        assertTrue(lm.holds(3), "혼자 있는 경우 즉시 잠금 획득");
        assertEquals(List.of(3), listener.acquired);
        assertEquals(0, sender.countOfType(MessageType.LOCK_REQUEST), "broadcast 불필요");
    }

    @Test
    void requestLockBroadcastsAndWaitsForReplies() {
        TestSender sender = new TestSender();
        TestListener listener = new TestListener();
        LockManager lm = new LockManager("alice", sender, listener);
        lm.setPeers(Arrays.asList("bob", "carol"));

        lm.requestLock(5);

        assertFalse(lm.holds(5), "응답 대기 중");
        assertEquals(LockManager.State.REQUESTED, lm.getState(5));
        assertEquals(1, sender.countOfType(MessageType.LOCK_REQUEST));

        Message req = sender.lastOfType(MessageType.LOCK_REQUEST);
        LockRequest payload = req.getPayloadAs(LockRequest.class);
        assertEquals(5, payload.getRegionId());
        long reqTs = payload.getTimestamp();

        // bob과 carol의 reply가 모두 도착하면 잠금 획득
        lm.onLockReply("bob", 5, reqTs, "alice");
        assertFalse(lm.holds(5), "한 명만 응답한 상태");
        lm.onLockReply("carol", 5, reqTs, "alice");
        assertTrue(lm.holds(5), "모두 응답 → 획득");
        assertEquals(List.of(5), listener.acquired);
    }

    @Test
    void incomingRequestRepliedWhenNotInterested() {
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.setPeers(Arrays.asList("bob"));

        lm.onLockRequest("bob", 7, 10L);

        Message reply = sender.lastOfType(MessageType.LOCK_REPLY);
        assertNotNull(reply, "NOT_INTERESTED 상태에서는 즉시 reply");
        LockReply payload = reply.getPayloadAs(LockReply.class);
        assertEquals(7, payload.getRegionId());
        assertEquals("bob", payload.getRequestSender());
        assertEquals(10L, payload.getRequestTimestamp());
    }

    @Test
    void incomingRequestDeferredWhenHeld() {
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.setPeers(java.util.Collections.emptyList());

        lm.requestLock(2); // 혼자라 즉시 HELD
        assertTrue(lm.holds(2));

        // bob의 요청은 defer되어야 함
        int beforeReplies = sender.countOfType(MessageType.LOCK_REPLY);
        lm.onLockRequest("bob", 2, 5L);
        assertEquals(beforeReplies, sender.countOfType(MessageType.LOCK_REPLY),
                "HELD 상태에서는 즉시 reply 보내지 않음 (defer)");

        // release 시 deferred reply가 송신되어야 함
        lm.releaseLock(2);
        assertEquals(beforeReplies + 1, sender.countOfType(MessageType.LOCK_REPLY));
        Message reply = sender.lastOfType(MessageType.LOCK_REPLY);
        LockReply rp = reply.getPayloadAs(LockReply.class);
        assertEquals("bob", rp.getRequestSender());
        assertEquals(5L, rp.getRequestTimestamp());

        assertEquals(1, sender.countOfType(MessageType.LOCK_RELEASE),
                "보유 잠금 해제 시 LOCK_RELEASE도 broadcast");
    }

    @Test
    void concurrentRequestsPrioritizeByTimestamp() {
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.setPeers(Arrays.asList("bob"));

        // alice가 먼저 요청 (ts = clock.tick() = 1)
        lm.requestLock(0);
        long aliceTs = sender.lastOfType(MessageType.LOCK_REQUEST)
                .getPayloadAs(LockRequest.class).getTimestamp();
        assertEquals(1L, aliceTs);

        // bob의 ts=0 요청 도착 — alice보다 빠름 → 즉시 reply
        int repliesBefore = sender.countOfType(MessageType.LOCK_REPLY);
        lm.onLockRequest("bob", 0, 0L);
        assertEquals(repliesBefore + 1, sender.countOfType(MessageType.LOCK_REPLY));

        // bob의 ts=99 요청 도착 — alice보다 느림 → defer (reply 없음)
        repliesBefore = sender.countOfType(MessageType.LOCK_REPLY);
        lm.onLockRequest("bob", 0, 99L);
        assertEquals(repliesBefore, sender.countOfType(MessageType.LOCK_REPLY), "느린 요청은 defer");
    }

    @Test
    void staleReplyIgnored() {
        TestSender sender = new TestSender();
        TestListener listener = new TestListener();
        LockManager lm = new LockManager("alice", sender, listener);
        lm.setPeers(Arrays.asList("bob"));

        lm.requestLock(1);
        long reqTs = sender.lastOfType(MessageType.LOCK_REQUEST)
                .getPayloadAs(LockRequest.class).getTimestamp();

        // 다른 timestamp의 reply는 무시되어야 함
        lm.onLockReply("bob", 1, reqTs + 100, "alice");
        assertFalse(lm.holds(1));
        assertTrue(listener.acquired.isEmpty());

        // 올바른 reply는 grant
        lm.onLockReply("bob", 1, reqTs, "alice");
        assertTrue(lm.holds(1));
    }

    @Test
    void replyForOtherUserIgnored() {
        TestSender sender = new TestSender();
        TestListener listener = new TestListener();
        LockManager lm = new LockManager("alice", sender, listener);
        lm.setPeers(Arrays.asList("bob", "carol"));

        lm.requestLock(8);
        long reqTs = sender.lastOfType(MessageType.LOCK_REQUEST)
                .getPayloadAs(LockRequest.class).getTimestamp();

        // bob → carol에게 보낸 reply가 echo로 들어옴 (requestSender = "carol")
        lm.onLockReply("bob", 8, reqTs, "carol");
        assertFalse(lm.holds(8), "다른 사용자 대상 reply는 카운트하지 않음");

        // bob → alice
        lm.onLockReply("bob", 8, reqTs, "alice");
        // carol → alice
        lm.onLockReply("carol", 8, reqTs, "alice");
        assertTrue(lm.holds(8));
    }

    @Test
    void peerLeavingAutoGrants() {
        TestSender sender = new TestSender();
        TestListener listener = new TestListener();
        LockManager lm = new LockManager("alice", sender, listener);
        lm.setPeers(Arrays.asList("bob", "carol"));

        lm.requestLock(9);
        long reqTs = sender.lastOfType(MessageType.LOCK_REQUEST)
                .getPayloadAs(LockRequest.class).getTimestamp();
        lm.onLockReply("bob", 9, reqTs, "alice");
        assertFalse(lm.holds(9));

        // carol이 세션을 떠났다 → setPeers 갱신
        lm.setPeers(Arrays.asList("bob"));
        assertTrue(lm.holds(9), "남은 peer의 응답을 모두 받으면 grant");
    }

    @Test
    void releaseSendsLockReleaseBroadcast() {
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.setPeers(java.util.Collections.emptyList());

        lm.requestLock(4);
        assertTrue(lm.holds(4));

        lm.releaseLock(4);
        assertFalse(lm.holds(4));
        assertEquals(LockManager.State.NOT_INTERESTED, lm.getState(4));

        Message release = sender.lastOfType(MessageType.LOCK_RELEASE);
        assertNotNull(release);
        assertEquals(4, release.getPayloadAs(LockRelease.class).getRegionId());
    }

    @Test
    void timeoutForcesGrant() throws InterruptedException {
        TestSender sender = new TestSender();
        TestListener listener = new TestListener();
        // 짧은 timeout으로 테스트
        LockManager lm = new LockManager("alice", sender, listener, 100);
        lm.setPeers(Arrays.asList("ghost"));

        lm.requestLock(11);
        assertFalse(lm.holds(11));

        Thread.sleep(300);
        assertTrue(lm.holds(11), "응답 없는 peer는 timeout 후 강제 grant");
        assertEquals(List.of(11), listener.timeouts);
        assertEquals(List.of(11), listener.acquired);

        lm.shutdown();
    }

    @Test
    void requestLockAfterShutdownIsNoop() {
        // 회귀 테스트 — shutdown 후 requestLock이 cancelled timer에 schedule을 시도해
        // IllegalStateException을 던지는 버그 (Bug A) 방지
        TestSender sender = new TestSender();
        TestListener listener = new TestListener();
        LockManager lm = new LockManager("alice", sender, listener, 100);
        lm.setPeers(Arrays.asList("bob"));

        lm.shutdown();

        // shutdown 이후 requestLock은 조용히 무시되어야 한다 (예외 X)
        assertDoesNotThrow(() -> lm.requestLock(0));
        assertFalse(lm.holds(0));
    }

    @Test
    void shutdownIsIdempotent() {
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.shutdown();
        assertDoesNotThrow(lm::shutdown, "shutdown 중복 호출 안전");
    }

    // ── Phase 7.6 / 7.7 ──

    @Test
    void remoteHolderRecordedOnRequestAndClearedOnRelease() {
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.setPeers(Arrays.asList("bob"));

        assertNull(lm.getRemoteHolder(3), "초기에는 원격 보유자 없음");

        lm.onLockRequest("bob", 3, 5L);
        assertEquals("bob", lm.getRemoteHolder(3), "LOCK_REQUEST 수신 시 보유자로 기록");

        lm.onLockRelease("bob", 3);
        assertNull(lm.getRemoteHolder(3), "LOCK_RELEASE 수신 시 정리");
    }

    @Test
    void remoteHolderClearedWhenPeerLeaves() {
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.setPeers(Arrays.asList("bob", "carol"));

        lm.onLockRequest("bob", 7, 1L);
        assertEquals("bob", lm.getRemoteHolder(7));

        // bob이 세션을 떠남 → 원격 보유자에서도 제거되어야 함
        lm.setPeers(Arrays.asList("carol"));
        assertNull(lm.getRemoteHolder(7), "떠난 peer의 원격 잠금은 정리");
    }

    @Test
    void promotingToHeldClearsRemoteHolderForSameRegion() {
        // 정합성 회귀: bob이 요청해서 remoteHolders[5]=bob이 된 뒤,
        // 우리가 같은 영역을 요청하고 즉시 획득(혼자) → remoteHolders에 stale 값이 남으면 안 됨.
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.setPeers(Arrays.asList("bob"));

        lm.onLockRequest("bob", 5, 1L);
        assertEquals("bob", lm.getRemoteHolder(5));

        // bob이 떠나서 alice 혼자 남았다고 가정
        lm.setPeers(java.util.Collections.emptyList());
        lm.requestLock(5);
        assertTrue(lm.holds(5));
        assertNull(lm.getRemoteHolder(5), "내가 획득한 영역은 원격 표시에서 제거");
    }

    @Test
    void shiftRegionsAdjustsBothMyStateAndRemoteHolders() {
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.setPeers(java.util.Collections.emptyList());

        // 내 보유 영역 = 3
        lm.requestLock(3);
        assertTrue(lm.holds(3));

        // 가짜 원격 holder 등록 (peer 추가 후 LOCK_REQUEST 수신)
        lm.setPeers(Arrays.asList("bob"));
        lm.onLockRequest("bob", 7, 1L);
        assertEquals("bob", lm.getRemoteHolder(7));

        // 줄 2에 1줄 삽입됨 → 줄 2 이후 영역들이 +1 shift
        lm.shiftRegions(2, 1);

        assertFalse(lm.holds(3), "regionId=3은 더 이상 보유 상태가 아님 (shifted to 4)");
        assertTrue(lm.holds(4), "내 보유 영역이 4로 이동");
        assertNull(lm.getRemoteHolder(7), "원격 holder도 shifted");
        assertEquals("bob", lm.getRemoteHolder(8), "bob의 영역이 7→8로 이동");
    }

    @Test
    void shiftRegionsBeforeChangePointLeavesEntriesAlone() {
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.setPeers(Arrays.asList("bob"));

        lm.onLockRequest("bob", 1, 1L);
        assertEquals("bob", lm.getRemoteHolder(1));

        // 줄 5에 변화가 생겨도 줄 1은 안 움직임
        lm.shiftRegions(5, 3);
        assertEquals("bob", lm.getRemoteHolder(1));
    }

    @Test
    void shiftCollisionShiftedSideYields() {
        // 줄 병합 시나리오: alice는 region 6 HELD, 원격 bob은 region 5 HELD.
        // 줄 5에서 1줄 삭제 → alice의 6 → 5 (shifted), bob의 5 → 5 (안 shifted).
        // 충돌 — shifted된 alice가 양보해야 함.
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());

        // alice는 혼자라 즉시 HELD
        lm.setPeers(java.util.Collections.emptyList());
        lm.requestLock(6);
        assertTrue(lm.holds(6));

        // 이제 peer 추가 + bob의 LOCK_REQUEST 수신
        lm.setPeers(Arrays.asList("bob"));
        lm.onLockRequest("bob", 5, 1L);
        assertEquals("bob", lm.getRemoteHolder(5));

        int releasesBefore = sender.countOfType(MessageType.LOCK_RELEASE);
        lm.shiftRegions(5, -1);

        // 결과: alice의 lock은 release, bob의 원격 표시는 유지
        assertFalse(lm.holds(5), "shifted 쪽(alice)이 release");
        assertFalse(lm.holds(6), "원래 key 6은 사라짐");
        assertEquals("bob", lm.getRemoteHolder(5), "bob의 원격 표시 유지");
        assertEquals(releasesBefore + 1, sender.countOfType(MessageType.LOCK_RELEASE),
                "shifted 쪽이 LOCK_RELEASE broadcast");
    }

    @Test
    void shiftCollisionNonShiftedSideKeeps() {
        // 위와 정반대 시점 — 내(alice)가 안 shift된 쪽인 경우.
        // alice는 region 5 HELD. 원격 bob은 region 6 HELD.
        // 줄 5에서 1줄 삭제 → alice의 5 → 5 (안 shift), bob의 6 → 5 (shifted).
        // 충돌 — alice는 유지, bob의 원격 표시만 정리.
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());

        lm.setPeers(java.util.Collections.emptyList());
        lm.requestLock(5);
        assertTrue(lm.holds(5));

        lm.setPeers(Arrays.asList("bob"));
        lm.onLockRequest("bob", 6, 1L);
        assertEquals("bob", lm.getRemoteHolder(6));

        int releasesBefore = sender.countOfType(MessageType.LOCK_RELEASE);
        lm.shiftRegions(5, -1);

        assertTrue(lm.holds(5), "non-shifted 쪽(alice)은 lock 유지");
        assertNull(lm.getRemoteHolder(5), "원격 표시(6→5 shifted)는 정리");
        assertEquals(releasesBefore, sender.countOfType(MessageType.LOCK_RELEASE),
                "release 안 함");
    }

    @Test
    void replyAfterRegionShiftStillMatches() {
        // 회귀: LOCK_REQUEST 보낸 뒤 줄 추가로 regionId가 shift됐을 때, peer는 원래 regionId로
        // reply를 보낸다. ts 기반 매칭이 없으면 reply가 drop되어 timeout까지 대기하게 된다.
        TestSender sender = new TestSender();
        TestListener listener = new TestListener();
        LockManager lm = new LockManager("alice", sender, listener);
        lm.setPeers(Arrays.asList("bob"));

        lm.requestLock(5);
        long reqTs = sender.lastOfType(MessageType.LOCK_REQUEST)
                .getPayloadAs(LockRequest.class).getTimestamp();
        assertFalse(lm.holds(5));

        // 누군가 줄 2에 1줄 삽입해서 내 region 5 → 6
        lm.shiftRegions(2, 1);
        assertFalse(lm.holds(5));
        assertEquals(LockManager.State.REQUESTED, lm.getState(6));

        // bob의 reply는 원래 regionId=5로 도착 — ts 매칭으로 처리되어야 함
        lm.onLockReply("bob", 5, reqTs, "alice");
        assertTrue(lm.holds(6), "shift된 새 regionId로 grant되어야 함");
        assertEquals(List.of(6), listener.acquired);
    }

    @Test
    void shiftSkipsNotInterestedRecords() {
        // onLockRequest는 computeIfAbsent로 NOT_INTERESTED 레코드를 만든다.
        // shiftRegions가 이걸 옮기지 않고 정리해야 충돌 판정이 정확하다.
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.setPeers(Arrays.asList("bob"));

        lm.onLockRequest("bob", 8, 1L); // alice의 regions[8] = NOT_INTERESTED 잔여
        assertEquals(LockManager.State.NOT_INTERESTED, lm.getState(8));

        lm.shiftRegions(2, 1);

        // regions[8]의 NOT_INTERESTED는 옮기지 않고 버려져야 한다 (state 그대로 NOT_INTERESTED)
        assertEquals(LockManager.State.NOT_INTERESTED, lm.getState(9), "9는 빈 상태");
        // 원격 holder만 정상 shift
        assertEquals("bob", lm.getRemoteHolder(9));
    }

    @Test
    void shiftRegionsWithDeleteRemovesNegativeKeys() {
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.setPeers(Arrays.asList("bob"));

        lm.onLockRequest("bob", 2, 1L);
        lm.onLockRequest("bob", 5, 2L);

        // 줄 0에서 3줄 제거 → 2→-1(제거), 5→2
        lm.shiftRegions(0, -3);
        assertNull(lm.getRemoteHolder(5), "원래 키는 사라짐");
        assertEquals("bob", lm.getRemoteHolder(2), "5→2로 이동, 원래 2의 holder는 음수 키로 사라짐");
    }

    @Test
    void releaseDuringRequestedSendsDeferredReplies() {
        TestSender sender = new TestSender();
        LockManager lm = new LockManager("alice", sender, new TestListener());
        lm.setPeers(Arrays.asList("bob"));

        lm.requestLock(6);
        long reqTs = sender.lastOfType(MessageType.LOCK_REQUEST)
                .getPayloadAs(LockRequest.class).getTimestamp();

        // 내가 alice(REQUESTED, ts=1)이고 bob의 ts=99 요청 → defer
        lm.onLockRequest("bob", 6, reqTs + 50);
        int repliesBefore = sender.countOfType(MessageType.LOCK_REPLY);

        // 내가 잠금 획득
        lm.onLockReply("bob", 6, reqTs, "alice");
        assertTrue(lm.holds(6));

        // 해제 시 defer된 bob에게 reply
        lm.releaseLock(6);
        assertEquals(repliesBefore + 1, sender.countOfType(MessageType.LOCK_REPLY),
                "release 시 deferred bob에게 reply 송신");
    }
}

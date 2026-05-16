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

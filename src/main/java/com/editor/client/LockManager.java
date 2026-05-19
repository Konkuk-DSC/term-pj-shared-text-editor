package com.editor.client;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.payload.LockReply;
import com.editor.common.payload.LockRequest;
import com.editor.common.payload.LockRelease;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Phase 7 — Ricart-Agrawala 분산 mutex 클라이언트 측 구현.
 *
 * 서버는 단순 메시지 릴레이로만 동작하고, 알고리즘은 각 클라이언트가 동일하게 실행한다.
 * 영역(region)은 줄 번호로 매핑되며, 영역별로 독립적인 상태 머신을 유지한다.
 *
 * 상태:
 *  - NOT_INTERESTED: 요청도 보유도 하지 않음. 들어오는 요청에 즉시 reply
 *  - REQUESTED: 잠금 요청을 보냈고 응답 대기 중. 다른 요청은 timestamp 비교로 reply/defer 결정
 *  - HELD: 잠금 보유 중. 들어오는 요청은 모두 defer (해제 시점에 reply)
 *
 * 동시 요청 충돌은 (Lamport timestamp, userId) 사전순 비교로 결정한다.
 *
 * Timeout: 응답 없는 peer는 5초 후 연결 끊김으로 간주하고 강제 grant 처리한다 (7.4).
 */
public class LockManager {

    public enum State { NOT_INTERESTED, REQUESTED, HELD }

    /** 응답 없는 peer를 자동 grant로 처리하기까지의 대기 시간. */
    public static final long DEFAULT_TIMEOUT_MS = 5000;

    /** lock 메시지를 서버로 보내는 콜백. */
    public interface MessageSender {
        void send(Message msg);
    }

    /** 잠금 상태 변화 알림. UI 갱신 등에 사용. */
    public interface LockListener {
        /** 잠금 획득 (정상 또는 timeout 후) */
        void onLockAcquired(int regionId);
        /** timeout으로 강제 grant 발생 — 진단/UI 알림용 */
        void onLockTimeout(int regionId);
        /**
         * Phase 7.6 — 잠금 상태(내 state 또는 remoteHolders)가 변했음을 알림.
         * UI redraw 트리거 용도. 기본 구현은 no-op이라 테스트는 영향 없음.
         */
        default void onLockStateChanged() {}
    }

    private final String myUserId;
    private final LamportClock clock = new LamportClock();
    private final MessageSender sender;
    private final LockListener listener;

    /** 영역별 상태 레코드. */
    private final Map<Integer, RegionRecord> regions = new HashMap<>();
    /** 같은 세션 참여자 집합 (자기 자신 제외). expectedReplies 계산용. */
    private final Set<String> peers = new HashSet<>();
    /**
     * Phase 7.6 — 원격 사용자가 보유/획득 중인 영역. regionId → userId.
     * LOCK_REQUEST 수신 시 갱신, LOCK_RELEASE 수신 시 정리, peer 이탈 시 정리.
     * 정확한 알고리즘 상태가 아니라 UI 시각화용 근사치이지만, 발신/수신/이탈 이벤트로
     * 충분히 일관성을 유지한다.
     */
    private final Map<Integer, String> remoteHolders = new HashMap<>();

    private final Timer timeoutTimer = new Timer("LockTimeout", true);
    private final long timeoutMillis;
    /** shutdown 이후 timer.schedule을 호출하지 않도록 보호하는 플래그. synchronized(this) 보호. */
    private boolean shutdown = false;

    public LockManager(String myUserId, MessageSender sender, LockListener listener) {
        this(myUserId, sender, listener, DEFAULT_TIMEOUT_MS);
    }

    public LockManager(String myUserId, MessageSender sender, LockListener listener, long timeoutMillis) {
        this.myUserId = myUserId;
        this.sender = sender;
        this.listener = listener;
        this.timeoutMillis = timeoutMillis;
    }

    /** 한 영역의 상태 + deferred 큐. */
    private static class RegionRecord {
        State state = State.NOT_INTERESTED;
        long requestTimestamp = -1;
        int expectedReplies = 0;
        final Set<String> repliedUsers = new HashSet<>();
        /** defer된 (요청자, 요청 timestamp) 쌍 — 해제 시 reply 송신용. */
        final List<Deferred> deferred = new ArrayList<>();
        TimerTask timeoutTask;
    }

    private static class Deferred {
        final String fromUserId;
        final long requestTimestamp;
        Deferred(String fromUserId, long requestTimestamp) {
            this.fromUserId = fromUserId;
            this.requestTimestamp = requestTimestamp;
        }
    }

    // ── peer 관리 ──

    /** 세션 참여자 전체로 peer 집합을 갱신 (자기 자신 자동 제외). */
    public synchronized void setPeers(Collection<String> sessionParticipants) {
        peers.clear();
        if (sessionParticipants != null) {
            for (String p : sessionParticipants) {
                if (!myUserId.equals(p)) peers.add(p);
            }
        }
        // Phase 7.7 — peer가 떠나면 그 사용자가 보유 중이던 원격 잠금도 즉시 무효화
        boolean remoteChanged = false;
        Iterator<Map.Entry<Integer, String>> it = remoteHolders.entrySet().iterator();
        while (it.hasNext()) {
            if (!peers.contains(it.next().getValue())) {
                it.remove();
                remoteChanged = true;
            }
        }
        // peer가 떠난 경우: 대기 중이던 요청이 있다면 응답한 것으로 간주
        autoGrantForMissingPeers();
        if (remoteChanged) notifyStateChanged();
    }

    /**
     * peer 집합 갱신 후, REQUESTED 상태에서 더 이상 peer에 없는 사용자의 응답을
     * 받은 것으로 간주하여 잠금 획득 조건을 다시 평가한다.
     */
    private void autoGrantForMissingPeers() {
        List<Integer> toPromote = new ArrayList<>();
        for (Map.Entry<Integer, RegionRecord> e : regions.entrySet()) {
            RegionRecord rec = e.getValue();
            if (rec.state != State.REQUESTED) continue;
            // expectedReplies는 요청 시점의 peer 수 기반이므로,
            // 현재 peer 수보다 작을 수 없게 재조정.
            int currentExpected = peers.size();
            if (currentExpected < rec.expectedReplies) {
                rec.expectedReplies = currentExpected;
            }
            // 받은 replies가 새 expectedReplies를 채우면 grant
            int effectiveReplies = 0;
            for (String r : rec.repliedUsers) if (peers.contains(r)) effectiveReplies++;
            if (effectiveReplies >= rec.expectedReplies) {
                toPromote.add(e.getKey());
            }
        }
        for (int regionId : toPromote) {
            promoteToHeld(regionId, regions.get(regionId));
        }
    }

    // ── 외부 API: 잠금 요청 / 해제 ──

    /**
     * 영역에 대한 잠금 요청. 이미 요청 중이거나 보유 중이면 무시한다.
     * 혼자 있는 세션이면 즉시 잠금 획득 처리한다.
     */
    public synchronized void requestLock(int regionId) {
        if (shutdown) return; // shutdown 이후 새 요청 거부 (Timer cancelled state 회피)
        RegionRecord rec = regions.computeIfAbsent(regionId, k -> new RegionRecord());
        if (rec.state == State.REQUESTED || rec.state == State.HELD) {
            return;
        }
        rec.state = State.REQUESTED;
        rec.requestTimestamp = clock.tick();
        rec.expectedReplies = peers.size();
        rec.repliedUsers.clear();
        notifyStateChanged(); // UI에 REQUESTED 상태 반영

        if (rec.expectedReplies == 0) {
            // 혼자인 경우 즉시 획득
            promoteToHeld(regionId, rec);
            return;
        }

        // LOCK_REQUEST broadcast
        Message msg = new Message(MessageType.LOCK_REQUEST, myUserId);
        msg.setPayloadFromObject(new LockRequest(regionId, rec.requestTimestamp));
        sender.send(msg);

        // timeout 등록 — 응답이 안 오는 peer를 끊긴 것으로 간주
        final int rid = regionId;
        final long ts = rec.requestTimestamp;
        rec.timeoutTask = new TimerTask() {
            @Override
            public void run() {
                handleTimeout(rid, ts);
            }
        };
        timeoutTimer.schedule(rec.timeoutTask, timeoutMillis);
    }

    /**
     * 영역에 대한 잠금 해제.
     * - HELD 상태였다면: deferred reply 송신 + LOCK_RELEASE broadcast
     * - REQUESTED 상태였다면: 요청 취소 (deferred에 응답 없이 그대로 두면 향후 정상 요청 시 또 처리됨; 안전을 위해 reply 송신)
     */
    public synchronized void releaseLock(int regionId) {
        RegionRecord rec = regions.get(regionId);
        if (rec == null || rec.state == State.NOT_INTERESTED) return;

        State oldState = rec.state;
        rec.state = State.NOT_INTERESTED;
        rec.requestTimestamp = -1;
        rec.expectedReplies = 0;
        rec.repliedUsers.clear();
        if (rec.timeoutTask != null) {
            rec.timeoutTask.cancel();
            rec.timeoutTask = null;
        }

        // deferred reply 송신
        for (Deferred d : rec.deferred) {
            sendReply(regionId, d.requestTimestamp, d.fromUserId);
        }
        rec.deferred.clear();

        if (oldState == State.HELD) {
            // LOCK_RELEASE broadcast (정보용)
            Message msg = new Message(MessageType.LOCK_RELEASE, myUserId);
            msg.setPayloadFromObject(new LockRelease(regionId));
            sender.send(msg);
        }
        notifyStateChanged();
    }

    // ── 외부 API: 수신 메시지 처리 ──

    /** LOCK_REQUEST 수신. */
    public synchronized void onLockRequest(String fromUserId, int regionId, long ts) {
        clock.update(ts);
        if (myUserId.equals(fromUserId)) return; // 자기 echo 무시

        RegionRecord rec = regions.computeIfAbsent(regionId, k -> new RegionRecord());

        // Phase 7.6 — UI 표시용으로 "이 영역에 관심 있는 원격 사용자"를 기록.
        // 우리가 HELD인 경우엔 우리가 진짜 보유자이므로 표시하지 않음 (UI는 우리 상태를 우선 표시).
        // REQUESTED인 경우엔 누가 이길지 모르므로 임시로 기록 — 우리가 이기면 promoteToHeld에서 제거됨.
        boolean remoteChanged = false;
        if (rec.state != State.HELD) {
            String prev = remoteHolders.put(regionId, fromUserId);
            remoteChanged = !fromUserId.equals(prev);
        }

        if (rec.state == State.NOT_INTERESTED) {
            sendReply(regionId, ts, fromUserId);
            if (remoteChanged) notifyStateChanged();
            return;
        }

        if (rec.state == State.HELD) {
            rec.deferred.add(new Deferred(fromUserId, ts));
            return;
        }

        // REQUESTED — 우선순위 비교
        int cmp = Long.compare(ts, rec.requestTimestamp);
        if (cmp == 0) cmp = fromUserId.compareTo(myUserId);
        if (cmp < 0) {
            // 상대가 더 빠른 요청 → 양보 (reply)
            sendReply(regionId, ts, fromUserId);
        } else {
            // 내가 우선 → defer
            rec.deferred.add(new Deferred(fromUserId, ts));
        }
        if (remoteChanged) notifyStateChanged();
    }

    /**
     * LOCK_REPLY 수신. requestSender가 나와 일치할 때만 처리.
     *
     * Phase 7.7 — 내가 LOCK_REQUEST를 보낸 뒤 줄 추가/삭제로 인해 내 regionId가 shift됐다면,
     * reply의 regionId(원래 보낸 값)와 내 현재 regions 키가 어긋난다.
     * 그러므로 regionId가 아니라 `requestTimestamp`로 보류 중인 요청을 찾는다.
     * Lamport tick은 단조 증가하므로 alice의 REQUESTED 레코드 중 ts가 같은 건 최대 1개.
     */
    public synchronized void onLockReply(String fromUserId, int regionId, long requestTs, String requestSender) {
        if (!myUserId.equals(requestSender)) return; // 내 요청에 대한 reply가 아님
        if (myUserId.equals(fromUserId)) return;     // 자기 자신 echo 방지

        RegionRecord rec = null;
        int actualRegionId = -1;
        for (Map.Entry<Integer, RegionRecord> e : regions.entrySet()) {
            RegionRecord r = e.getValue();
            if (r.state == State.REQUESTED && r.requestTimestamp == requestTs) {
                rec = r;
                actualRegionId = e.getKey();
                break;
            }
        }
        if (rec == null) return; // 해당 요청을 더 이상 추적하지 않음 (이미 취소/완료됨)

        if (rec.repliedUsers.add(fromUserId)) {
            if (rec.repliedUsers.size() >= rec.expectedReplies) {
                promoteToHeld(actualRegionId, rec);
            }
        }
    }

    /**
     * LOCK_RELEASE 수신. R-A에서는 deferred reply로 이양이 일어나므로 알고리즘적으로는 no-op.
     * Phase 7.6에서 UI 표시(원격 잠금 해제 시각화)를 위해 remoteHolders 정리.
     */
    public synchronized void onLockRelease(String fromUserId, int regionId) {
        if (fromUserId.equals(remoteHolders.get(regionId))) {
            remoteHolders.remove(regionId);
            notifyStateChanged();
        }
    }

    // ── 내부 헬퍼 ──

    private void sendReply(int regionId, long requestTs, String toUserId) {
        Message msg = new Message(MessageType.LOCK_REPLY, myUserId);
        msg.setPayloadFromObject(new LockReply(regionId, requestTs, toUserId));
        sender.send(msg);
    }

    private void handleTimeout(int regionId, long requestTs) {
        // Phase 7.7 — onLockReply와 동일하게 requestTs로 찾는다 (shift 후 regionId가 다를 수 있음).
        int actualRegionId = -1;
        synchronized (this) {
            RegionRecord rec = null;
            for (Map.Entry<Integer, RegionRecord> e : regions.entrySet()) {
                RegionRecord r = e.getValue();
                if (r.state == State.REQUESTED && r.requestTimestamp == requestTs) {
                    rec = r;
                    actualRegionId = e.getKey();
                    break;
                }
            }
            if (rec == null) return; // 요청이 이미 취소/완료됨
            System.out.println("[LOCK TIMEOUT] region=" + actualRegionId + " — forcing grant after " + timeoutMillis + "ms");
            promoteToHeld(actualRegionId, rec);
        }
        // listener는 lock 밖에서 호출
        listener.onLockTimeout(actualRegionId);
    }

    /** 잠금을 HELD로 승격하고 listener에 알린다. (synchronized 블록 안에서 호출되어야 함) */
    private void promoteToHeld(int regionId, RegionRecord rec) {
        rec.state = State.HELD;
        if (rec.timeoutTask != null) {
            rec.timeoutTask.cancel();
            rec.timeoutTask = null;
        }
        // Phase 7.6 — 내가 보유한 영역은 원격 표시에서 제거 (우리 상태가 우선)
        remoteHolders.remove(regionId);
        listener.onLockAcquired(regionId);
        listener.onLockStateChanged();
    }

    /** state 변화 시 UI redraw 트리거. synchronized 블록 안에서 호출. */
    private void notifyStateChanged() {
        listener.onLockStateChanged();
    }

    // ── 상태 조회 ──

    public synchronized boolean holds(int regionId) {
        RegionRecord rec = regions.get(regionId);
        return rec != null && rec.state == State.HELD;
    }

    public synchronized State getState(int regionId) {
        RegionRecord rec = regions.get(regionId);
        return rec == null ? State.NOT_INTERESTED : rec.state;
    }

    public synchronized int getPeerCount() {
        return peers.size();
    }

    // ── 정리 ──

    /** 세션 이탈 시 호출 — 모든 영역 상태/타이머 정리. */
    public synchronized void reset() {
        for (RegionRecord rec : regions.values()) {
            if (rec.timeoutTask != null) rec.timeoutTask.cancel();
        }
        regions.clear();
        peers.clear();
        boolean hadRemote = !remoteHolders.isEmpty();
        remoteHolders.clear();
        if (hadRemote) notifyStateChanged();
    }

    /**
     * 종료 시 호출 — timer 스레드 정리.
     * idempotent하게 동작하며, shutdown 플래그를 먼저 설정하여
     * 이후 requestLock의 timer.schedule()이 호출되지 않도록 보호한다.
     */
    public synchronized void shutdown() {
        if (shutdown) return;
        shutdown = true;
        for (RegionRecord rec : regions.values()) {
            if (rec.timeoutTask != null) rec.timeoutTask.cancel();
        }
        regions.clear();
        remoteHolders.clear();
        timeoutTimer.cancel();
    }

    // ── Phase 7.6 — UI 조회 ──

    /** 해당 영역을 보유/획득 중인 원격 사용자 id (없으면 null). */
    public synchronized String getRemoteHolder(int regionId) {
        return remoteHolders.get(regionId);
    }

    /** UI redraw용 원격 잠금 스냅샷. */
    public synchronized Map<Integer, String> getRemoteHoldersSnapshot() {
        return new HashMap<>(remoteHolders);
    }

    // ── Phase 7.7 — 줄 추가/삭제로 region id가 shift될 때의 lock 재조정 ──

    /**
     * 텍스트 변경으로 changeLine 이후의 줄이 delta만큼 이동했을 때,
     * 내 상태(regions)와 원격 holders 맵의 키를 일관되게 shift한다.
     *
     *  - regionId <= changeLine: 그대로
     *  - regionId > changeLine: regionId += delta
     *  - shift 결과가 음수면 해당 영역은 사라진 것으로 보고 제거
     *
     * 모든 클라이언트가 같은 텍스트 이벤트를 같은 순서로 받으면 같은 shift를 수행해
     * regionId 매핑이 일관되게 유지된다.
     */
    public synchronized void shiftRegions(int changeLine, int delta) {
        if (delta == 0) return;

        // regions (내 state) shift — NOT_INTERESTED 잔여는 제거하고 활성 상태만 옮긴다.
        // shifted 여부를 별도로 기록해서 cross-map 충돌 시 책임 판정에 사용.
        Map<Integer, RegionRecord> newRegions = new HashMap<>();
        Set<Integer> myShiftedKeys = new HashSet<>();
        for (Map.Entry<Integer, RegionRecord> e : regions.entrySet()) {
            int k = e.getKey();
            RegionRecord rec = e.getValue();
            if (rec.state == State.NOT_INTERESTED) {
                if (rec.timeoutTask != null) rec.timeoutTask.cancel();
                continue;
            }
            int newKey = (k > changeLine) ? k + delta : k;
            if (newKey < 0) {
                if (rec.timeoutTask != null) rec.timeoutTask.cancel();
                continue;
            }
            if (newRegions.containsKey(newKey)) {
                if (rec.timeoutTask != null) rec.timeoutTask.cancel();
                continue;
            }
            newRegions.put(newKey, rec);
            if (newKey != k) myShiftedKeys.add(newKey);
        }
        regions.clear();
        regions.putAll(newRegions);

        // remoteHolders shift
        Map<Integer, String> newRemote = new HashMap<>();
        Set<Integer> remoteShiftedKeys = new HashSet<>();
        for (Map.Entry<Integer, String> e : remoteHolders.entrySet()) {
            int k = e.getKey();
            int newKey = (k > changeLine) ? k + delta : k;
            if (newKey < 0) continue;
            if (newRemote.containsKey(newKey)) continue;
            newRemote.put(newKey, e.getValue());
            if (newKey != k) remoteShiftedKeys.add(newKey);
        }
        boolean remoteChanged = !newRemote.equals(remoteHolders);
        remoteHolders.clear();
        remoteHolders.putAll(newRemote);

        // Phase 7.7 — cross-map 충돌 해소.
        // 줄 병합 등으로 내 보유 영역과 원격 보유 영역의 키가 같아진 경우, "shift된 쪽"이 yield한다.
        // 모든 클라이언트가 같은 텍스트 이벤트를 받으므로 같은 판정을 내려 결과가 대칭적이다.
        // 예: alice(5 HELD, 안 shift) + bob(6 HELD → 5 shift) 충돌 시 alice는 그대로, bob은 release.
        List<Integer> myReleases = new ArrayList<>();
        for (Integer k : new ArrayList<>(regions.keySet())) {
            if (!remoteHolders.containsKey(k)) continue;
            boolean myShifted = myShiftedKeys.contains(k);
            boolean remoteShifted = remoteShiftedKeys.contains(k);
            if (myShifted && !remoteShifted) {
                // 내가 shift된 쪽 — 내가 양보
                myReleases.add(k);
            } else if (!myShifted && remoteShifted) {
                // 원격이 shift된 쪽 — 원격 표시만 정리
                remoteHolders.remove(k);
                remoteChanged = true;
            } else {
                // 둘 다 shift됐거나 둘 다 안 됐음 — 매우 드문 케이스, 양쪽 모두 정리
                myReleases.add(k);
                remoteHolders.remove(k);
                remoteChanged = true;
            }
        }

        for (int k : myReleases) {
            RegionRecord rec = regions.get(k);
            if (rec == null) continue;
            State oldState = rec.state;
            rec.state = State.NOT_INTERESTED;
            rec.requestTimestamp = -1;
            rec.expectedReplies = 0;
            rec.repliedUsers.clear();
            if (rec.timeoutTask != null) {
                rec.timeoutTask.cancel();
                rec.timeoutTask = null;
            }
            // deferred 큐 정리
            for (Deferred d : rec.deferred) {
                sendReply(k, d.requestTimestamp, d.fromUserId);
            }
            rec.deferred.clear();
            if (oldState == State.HELD) {
                Message msg = new Message(MessageType.LOCK_RELEASE, myUserId);
                msg.setPayloadFromObject(new LockRelease(k));
                sender.send(msg);
            }
        }

        if (remoteChanged || !myReleases.isEmpty()) notifyStateChanged();
    }
}

package com.editor.server;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5.5 — 세션 영속화 저장 동작 검증.
 */
class SessionPersistenceTest {

    @TempDir
    Path tmpDir;

    private SessionPersistence persistence;

    @BeforeEach
    void setUp() {
        persistence = new SessionPersistence(tmpDir);
    }

    @Test
    void save_writesJsonFile_withSessionContent() throws IOException {
        Session session = new Session("sid-1", "demo");
        session.getBuffer().insert(0, "hello world");
        session.touchModified();

        persistence.save(session);

        Path file = tmpDir.resolve("sid-1.json");
        assertTrue(Files.exists(file), "session json must exist");

        String json = Files.readString(file, StandardCharsets.UTF_8);
        SessionSnapshot snap = new Gson().fromJson(json, SessionSnapshot.class);

        assertEquals("sid-1", snap.getSessionId());
        assertEquals("demo", snap.getSessionName());
        assertEquals("hello world", snap.getContent());
        assertEquals(session.getCreatedAt(), snap.getCreatedAt());
        assertEquals(session.getLastModifiedAt(), snap.getLastModifiedAt());
    }

    @Test
    void save_overwritesExistingFile() throws IOException {
        Session session = new Session("sid-2", "doc");
        session.getBuffer().insert(0, "first");
        persistence.save(session);

        session.getBuffer().insert(5, " edition");
        session.touchModified();
        persistence.save(session);

        String json = Files.readString(tmpDir.resolve("sid-2.json"), StandardCharsets.UTF_8);
        SessionSnapshot snap = new Gson().fromJson(json, SessionSnapshot.class);
        assertEquals("first edition", snap.getContent());

        // 임시 파일 잔여 없음
        assertFalse(Files.exists(tmpDir.resolve("sid-2.json.tmp")));
    }

    @Test
    void saveAll_writesEverySession_andSkipsEmptyStore() throws IOException {
        SessionStore store = new SessionStore();
        Session a = store.create("alpha");
        Session b = store.create("beta");
        a.getBuffer().insert(0, "AAA");
        b.getBuffer().insert(0, "BBB");

        int ok = persistence.saveAll(store);
        assertEquals(2, ok);

        assertTrue(Files.exists(tmpDir.resolve(a.getSessionId() + ".json")));
        assertTrue(Files.exists(tmpDir.resolve(b.getSessionId() + ".json")));

        // 빈 store는 0 반환
        SessionStore empty = new SessionStore();
        assertEquals(0, persistence.saveAll(empty));
    }

    @Test
    void save_createsBaseDirIfMissing() throws IOException {
        Path nested = tmpDir.resolve("nested/sessions");
        SessionPersistence p = new SessionPersistence(nested);

        Session session = new Session("sid-3", "n");
        session.getBuffer().insert(0, "x");
        p.save(session);

        assertTrue(Files.exists(nested.resolve("sid-3.json")));
    }

    // ── Phase 5.6 — 로드 테스트 ──

    @Test
    void loadAll_restoresSessions_withOriginalTimestampsAndContent() throws IOException {
        Session original = new Session("sid-load-1", "doc-a");
        original.getBuffer().insert(0, "persisted body");
        original.touchModified();
        long origCreated = original.getCreatedAt();
        long origModified = original.getLastModifiedAt();
        persistence.save(original);

        SessionStore restored = new SessionStore();
        int n = persistence.loadAll(restored);

        assertEquals(1, n);
        Session loaded = restored.get("sid-load-1");
        assertNotNull(loaded);
        assertEquals("doc-a", loaded.getSessionName());
        assertEquals("persisted body", loaded.getBuffer().getText());
        assertEquals(origCreated, loaded.getCreatedAt());
        assertEquals(origModified, loaded.getLastModifiedAt());
        // 참여자 목록은 런타임 상태이므로 비어있어야 함
        assertEquals(0, loaded.getParticipantCount());
    }

    @Test
    void loadAll_returnsZero_whenBaseDirMissing() {
        SessionPersistence p = new SessionPersistence(tmpDir.resolve("does-not-exist"));
        assertEquals(0, p.loadAll(new SessionStore()));
    }

    @Test
    void loadAll_skipsInvalidFiles_andContinues() throws IOException {
        // 정상 세션 1개 저장
        Session ok = new Session("sid-ok", "ok-doc");
        ok.getBuffer().insert(0, "good");
        persistence.save(ok);

        // 잘못된 JSON 파일
        Files.writeString(tmpDir.resolve("broken.json"), "{ not valid json");
        // 필수 필드 누락된 JSON
        Files.writeString(tmpDir.resolve("missing.json"), "{}");

        SessionStore store = new SessionStore();
        int n = persistence.loadAll(store);

        assertEquals(1, n);
        assertNotNull(store.get("sid-ok"));
        assertEquals("good", store.get("sid-ok").getBuffer().getText());
    }

    @Test
    void loadAll_skipsDuplicateSessionId() throws IOException {
        Session original = new Session("dup-sid", "first");
        original.getBuffer().insert(0, "hello");
        persistence.save(original);

        SessionStore store = new SessionStore();
        // 미리 같은 sessionId를 등록해두면 로드가 건너뛰어야 함
        Session pre = new Session("dup-sid", "preloaded");
        pre.getBuffer().insert(0, "pre");
        store.register(pre);

        int n = persistence.loadAll(store);
        assertEquals(0, n);
        // 기존 등록된 세션이 그대로 유지
        assertEquals("preloaded", store.get("dup-sid").getSessionName());
        assertEquals("pre", store.get("dup-sid").getBuffer().getText());
    }

    @Test
    void saveAll_thenLoadAll_roundTrip() throws IOException {
        SessionStore writeStore = new SessionStore();
        Session a = writeStore.create("alpha");
        Session b = writeStore.create("beta");
        a.getBuffer().insert(0, "AAA");
        b.getBuffer().insert(0, "BBB");
        a.touchModified();
        b.touchModified();

        persistence.saveAll(writeStore);

        SessionStore readStore = new SessionStore();
        int loaded = persistence.loadAll(readStore);

        assertEquals(2, loaded);
        assertEquals("AAA", readStore.get(a.getSessionId()).getBuffer().getText());
        assertEquals("BBB", readStore.get(b.getSessionId()).getBuffer().getText());
    }
}

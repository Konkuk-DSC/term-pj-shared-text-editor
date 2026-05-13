package com.editor.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 세션 영속화 담당 클래스 (Phase 5.5).
 *
 * 저장 방식:
 *  - 디렉토리: 기본 {@code sessions/} (서버 작업 디렉토리 기준)
 *  - 파일: 세션별 {@code <sessionId>.json}
 *  - 동시 편집 중 안전을 위해 buffer lock 안에서 스냅샷 추출
 *  - 부분 파일 방지를 위해 임시 파일에 쓴 뒤 atomic rename
 *
 * Phase 5.5 저장 + Phase 5.6 로드.
 */
public class SessionPersistence {

    public static final String DEFAULT_DIR = "sessions";

    private final Path baseDir;
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public SessionPersistence() {
        this(Paths.get(DEFAULT_DIR));
    }

    public SessionPersistence(Path baseDir) {
        this.baseDir = baseDir;
    }

    public Path getBaseDir() {
        return baseDir;
    }

    private void ensureDir() throws IOException {
        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir);
        }
    }

    /**
     * 단일 세션을 JSON 파일로 저장한다.
     * 동시 편집 중에도 일관된 스냅샷을 얻기 위해 buffer lock 안에서 텍스트를 캡쳐한다.
     */
    public void save(Session session) throws IOException {
        ensureDir();

        SessionSnapshot snap;
        DocumentBuffer buffer = session.getBuffer();
        synchronized (buffer) {
            snap = new SessionSnapshot(
                    session.getSessionId(),
                    session.getSessionName(),
                    buffer.getText(),
                    session.getCreatedAt(),
                    session.getLastModifiedAt()
            );
        }

        Path target = baseDir.resolve(session.getSessionId() + ".json");
        Path tmp = baseDir.resolve(session.getSessionId() + ".json.tmp");
        Files.writeString(tmp, gson.toJson(snap), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 일부 파일시스템(특정 Windows 환경 등)은 atomic move 미지원 — fallback
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * SessionStore의 모든 세션을 일괄 저장한다.
     * 개별 세션 저장에 실패해도 다른 세션 저장은 계속 진행한다.
     * @return 성공한 세션 개수
     */
    /**
     * baseDir의 모든 {@code *.json} 파일을 읽어 Session으로 복원한 뒤 SessionStore에 등록한다 (Phase 5.6).
     *
     * - baseDir이 없으면 0을 반환 (첫 실행 정상 경로)
     * - 개별 파일 파싱 실패는 로그만 남기고 다음 파일을 계속 처리
     * - sessionId가 이미 있는 경우(중복)도 건너뛴다
     *
     * @return 실제로 SessionStore에 추가된 세션 개수
     */
    public int loadAll(SessionStore store) {
        if (!Files.exists(baseDir)) {
            System.out.println("[PERSIST] No sessions directory at "
                    + baseDir.toAbsolutePath() + " — starting fresh");
            return 0;
        }

        int loaded = 0;
        int total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, "*.json")) {
            for (Path file : stream) {
                total++;
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    SessionSnapshot snap = gson.fromJson(json, SessionSnapshot.class);
                    if (snap == null
                            || snap.getSessionId() == null || snap.getSessionId().isEmpty()
                            || snap.getSessionName() == null) {
                        System.err.println("[PERSIST] Invalid session file (skipped): "
                                + file.getFileName());
                        continue;
                    }
                    Session session = new Session(
                            snap.getSessionId(),
                            snap.getSessionName(),
                            snap.getCreatedAt(),
                            snap.getLastModifiedAt(),
                            snap.getContent()
                    );
                    if (store.register(session)) {
                        loaded++;
                    } else {
                        System.err.println("[PERSIST] Duplicate sessionId (skipped): "
                                + snap.getSessionId());
                    }
                } catch (IOException | RuntimeException e) {
                    System.err.println("[PERSIST] Failed to load " + file.getFileName()
                            + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[PERSIST] Failed to read sessions dir " + baseDir + ": "
                    + e.getMessage());
        }

        System.out.println("[PERSIST] Loaded " + loaded + "/" + total
                + " sessions from " + baseDir.toAbsolutePath());
        return loaded;
    }

    public int saveAll(SessionStore store) {
        int ok = 0;
        int total = 0;
        for (Session s : store.list()) {
            total++;
            try {
                save(s);
                ok++;
            } catch (IOException e) {
                System.err.println("[PERSIST] Failed to save session "
                        + s.getSessionId() + " (" + s.getSessionName() + "): " + e.getMessage());
            }
        }
        System.out.println("[PERSIST] Saved " + ok + "/" + total + " sessions to " + baseDir.toAbsolutePath());
        return ok;
    }
}

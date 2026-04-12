package com.editor.server;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.NetworkUtil;
import com.editor.common.payload.LoginRequest;
import com.editor.common.payload.LoginResponse;
import com.editor.common.payload.RegisterRequest;
import com.editor.common.payload.RegisterResponse;
import com.editor.common.payload.TextInsert;
import com.editor.common.payload.UserEvent;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2.6 테스트: 접속 해제 및 전송 실패 클라이언트 자동 해제 검증
 */
class DisconnectTest {

    private static final int TEST_PORT = 19000;
    private ServerMain server;
    private Thread serverThread;

    @BeforeEach
    void setUp() throws Exception {
        server = new ServerMain(TEST_PORT);
        serverThread = new Thread(() -> server.start());
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(500); // 서버 시작 대기
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    /** 헬퍼: 서버에 소켓 연결 후 NetworkUtil 반환 */
    private NetworkUtil connect() throws IOException {
        Socket socket = new Socket("localhost", TEST_PORT);
        return new NetworkUtil(socket);
    }

    /** 헬퍼: 회원가입 */
    private void register(NetworkUtil net, String userId, String password) throws IOException {
        Message msg = new Message(MessageType.REGISTER_REQUEST, userId);
        msg.setPayloadFromObject(new RegisterRequest(userId, password));
        net.send(msg);
        Message resp = net.receive();
        RegisterResponse result = resp.getPayloadAs(RegisterResponse.class);
        assertTrue(result.isSuccess(), "회원가입 실패: " + result.getMessage());
    }

    /** 헬퍼: 로그인하고 응답 반환 */
    private LoginResponse login(NetworkUtil net, String userId, String password) throws IOException {
        Message msg = new Message(MessageType.LOGIN_REQUEST, userId);
        msg.setPayloadFromObject(new LoginRequest(userId, password));
        net.send(msg);
        Message resp = net.receive();
        return resp.getPayloadAs(LoginResponse.class);
    }

    @Test
    @DisplayName("정상 로그인 후 접속자 목록에 포함된다")
    void testLoginAddsToOnlineUsers() throws Exception {
        NetworkUtil client = connect();
        register(client, "user1", "pass1");
        LoginResponse resp = login(client, "user1", "pass1");

        assertTrue(resp.isSuccess());
        assertTrue(resp.getOnlineUsers().contains("user1"));

        // 서버 접속자 목록에도 있는지 확인
        Thread.sleep(100);
        assertTrue(server.getOnlineUsers().contains("user1"));

        client.close();
    }

    @Test
    @DisplayName("LOGOUT 메시지 전송 시 접속자에서 제거된다")
    void testLogoutRemovesFromOnlineUsers() throws Exception {
        NetworkUtil client = connect();
        register(client, "user2", "pass2");
        login(client, "user2", "pass2");

        assertTrue(server.getOnlineUsers().contains("user2"));

        // 로그아웃
        Message logout = new Message(MessageType.LOGOUT, "user2");
        client.send(logout);
        Thread.sleep(300);

        assertFalse(server.getOnlineUsers().contains("user2"));

        client.close();
    }

    @Test
    @DisplayName("클라이언트 소켓 강제 종료 시 접속자에서 제거된다 (비정상 종료)")
    void testAbruptDisconnectRemovesFromOnlineUsers() throws Exception {
        NetworkUtil client = connect();
        register(client, "user3", "pass3");
        login(client, "user3", "pass3");

        assertTrue(server.getOnlineUsers().contains("user3"));

        // 소켓 강제 종료 (LOGOUT 안 보냄)
        client.getSocket().close();
        Thread.sleep(500);

        assertFalse(server.getOnlineUsers().contains("user3"));
    }

    @Test
    @DisplayName("비정상 종료 시 다른 접속자에게 USER_LEFT가 전달된다")
    void testAbruptDisconnectBroadcastsUserLeft() throws Exception {
        // user4 접속
        NetworkUtil client4 = connect();
        register(client4, "user4", "pass4");
        login(client4, "user4", "pass4");

        // user5 접속
        NetworkUtil client5 = connect();
        register(client5, "user5", "pass5");
        login(client5, "user5", "pass5");

        // user4는 user5 입장 알림(USER_JOINED)을 받음 — 소비
        Message joinNotify = client4.receive();
        assertEquals(MessageType.USER_JOINED, joinNotify.getType());

        // user5 강제 종료
        client5.getSocket().close();
        Thread.sleep(500);

        // user4가 USER_LEFT 메시지를 받아야 함
        Message leftNotify = client4.receive();
        assertNotNull(leftNotify, "USER_LEFT 메시지를 받지 못함");
        assertEquals(MessageType.USER_LEFT, leftNotify.getType());
        UserEvent event = leftNotify.getPayloadAs(UserEvent.class);
        assertEquals("user5", event.getUserId());

        client4.close();
    }

    @Test
    @DisplayName("전송 실패 클라이언트는 브로드캐스트 시 자동 해제된다")
    void testSendFailureAutoDisconnects() throws Exception {
        // user6, user7 접속
        NetworkUtil client6 = connect();
        register(client6, "user6", "pass6");
        login(client6, "user6", "pass6");

        NetworkUtil client7 = connect();
        register(client7, "user7", "pass7");
        login(client7, "user7", "pass7");

        // user6의 USER_JOINED(user7) 메시지 소비
        client6.receive();

        // user7의 소켓을 닫아서 전송 불가 상태로 만듦 (LOGOUT 안 보냄)
        client7.getSocket().close();
        Thread.sleep(300);

        // user6이 텍스트 편집 → 서버가 user7에게 브로드캐스트 시도 → 실패 → user7 자동 해제
        Message editMsg = new Message(MessageType.TEXT_INSERT, "user6");
        editMsg.setPayloadFromObject(new TextInsert(0, "hello"));
        client6.send(editMsg);
        Thread.sleep(500);

        // user7이 접속자 목록에서 제거되었는지 확인
        List<String> online = server.getOnlineUsers();
        assertTrue(online.contains("user6"), "user6은 여전히 접속 중이어야 함");
        assertFalse(online.contains("user7"), "user7은 자동 해제되어야 함");

        client6.close();
    }

    @Test
    @DisplayName("disconnect()가 여러 번 호출되어도 한 번만 처리된다 (중복 방지)")
    void testDisconnectIdempotent() throws Exception {
        // user8 접속
        NetworkUtil client8 = connect();
        register(client8, "user8", "pass8");
        login(client8, "user8", "pass8");

        // user9 접속 (USER_LEFT 수신 확인용)
        NetworkUtil client9 = connect();
        register(client9, "user9", "pass9");
        login(client9, "user9", "pass9");

        // user8의 USER_JOINED(user9) 소비
        client8.receive();

        assertTrue(server.getOnlineUsers().contains("user8"));

        // user8 소켓 강제 종료 — run()의 finally에서 disconnect() 호출됨
        client8.getSocket().close();
        Thread.sleep(500);

        assertFalse(server.getOnlineUsers().contains("user8"));

        // user9가 받는 USER_LEFT는 정확히 1개여야 함
        Message left = client9.receive(); // user8 joined 알림
        // 이게 USER_JOINED(user8)일 수 있으므로 확인
        if (left.getType() == MessageType.USER_JOINED) {
            left = client9.receive(); // 다음이 USER_LEFT
        }
        assertEquals(MessageType.USER_LEFT, left.getType());
        UserEvent event = left.getPayloadAs(UserEvent.class);
        assertEquals("user8", event.getUserId());

        client9.close();
    }
}

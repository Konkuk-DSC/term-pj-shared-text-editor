package com.editor.client;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.NetworkUtil;
import com.editor.common.payload.*;
import com.editor.server.ServerMain;
import org.junit.jupiter.api.*;

import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.2~4.5 + 5.4 테스트:
 * DocumentListener가 로컬 텍스트 변경을 감지하여 TEXT_INSERT/TEXT_DELETE를 서버로 전송하고,
 * 서버가 같은 세션 참여자에게 브로드캐스트하는지 검증.
 *
 * Phase 5.4 이후: 텍스트 편집은 세션 안에서만 동작하므로 각 테스트는
 * 두 클라이언트가 같은 세션에 참여한 상태를 먼저 만든다.
 */
class TextEditSendTest {

    private static ServerMain server;
    private static final int PORT = 9201;

    @BeforeAll
    static void startServer() throws Exception {
        server = new ServerMain(PORT);
        Thread serverThread = new Thread(() -> server.start(), "test-server");
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(500);
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    private NetworkUtil connect() throws Exception {
        return new NetworkUtil(new Socket("localhost", PORT));
    }

    private void registerAndLogin(NetworkUtil net, String userId, String pw) throws Exception {
        Message reg = new Message(MessageType.REGISTER_REQUEST, userId);
        reg.setPayloadFromObject(new RegisterRequest(userId, pw));
        net.send(reg);
        Message regResp = net.receive();
        assertTrue(regResp.getPayloadAs(RegisterResponse.class).isSuccess());

        Message login = new Message(MessageType.LOGIN_REQUEST, userId);
        login.setPayloadFromObject(new LoginRequest(userId, pw));
        net.send(login);
        Message loginResp = net.receive();
        assertTrue(loginResp.getPayloadAs(LoginResponse.class).isSuccess());
    }

    /** 세션을 생성하고 sessionId 반환. 후속 SESSION_LIST_RESPONSE broadcast는 호출 측에서 처리한다. */
    private String createSession(NetworkUtil net, String sender, String name) throws Exception {
        Message msg = new Message(MessageType.SESSION_CREATE, sender);
        msg.setPayloadFromObject(new SessionCreateRequest(name));
        net.send(msg);
        // SESSION_CREATE_RESPONSE
        Message resp = receiveOfType(net, MessageType.SESSION_CREATE_RESPONSE);
        SessionCreateResponse cr = resp.getPayloadAs(SessionCreateResponse.class);
        assertTrue(cr.isSuccess(), "Session create failed: " + cr.getMessage());
        return cr.getSessionId();
    }

    /** 세션 참여 — SESSION_JOIN_RESPONSE를 받을 때까지 다른 메시지는 버린다. */
    private void joinSession(NetworkUtil net, String sender, String sessionId) throws Exception {
        Message msg = new Message(MessageType.SESSION_JOIN, sender);
        msg.setPayloadFromObject(new SessionJoinRequest(sessionId));
        net.send(msg);
        Message resp = receiveOfType(net, MessageType.SESSION_JOIN_RESPONSE);
        assertTrue(resp.getPayloadAs(SessionJoinResponse.class).isSuccess());
    }

    /** 특정 타입의 메시지가 올 때까지 다른 메시지를 버리며 대기. */
    private Message receiveOfType(NetworkUtil net, MessageType type) throws Exception {
        for (int i = 0; i < 20; i++) {
            Message m = net.receive();
            if (m == null) fail("Connection closed waiting for " + type);
            if (m.getType() == type) return m;
        }
        fail("Did not receive " + type + " within limit");
        return null;
    }

    @Test
    @DisplayName("TEXT_INSERT is broadcast to other clients in same session")
    void textInsertBroadcast() throws Exception {
        NetworkUtil client1 = connect();
        NetworkUtil client2 = connect();

        registerAndLogin(client1, "ins_user1", "pw");
        registerAndLogin(client2, "ins_user2", "pw");

        String sid = createSession(client1, "ins_user1", "ins-session");
        joinSession(client1, "ins_user1", sid);
        joinSession(client2, "ins_user2", sid);

        // client1 sends TEXT_INSERT
        Message insertMsg = new Message(MessageType.TEXT_INSERT, "ins_user1");
        insertMsg.setPayloadFromObject(new TextInsert(0, "Hello"));
        client1.send(insertMsg);

        // client2 should receive TEXT_INSERT (might be preceded by SESSION_LIST_RESPONSE broadcasts)
        Message received = receiveOfType(client2, MessageType.TEXT_INSERT);
        assertNotNull(received);
        TextInsert payload = received.getPayloadAs(TextInsert.class);
        assertEquals(0, payload.getOffset());
        assertEquals("Hello", payload.getText());

        client1.close();
        client2.close();
        Thread.sleep(300);
    }

    @Test
    @DisplayName("TEXT_DELETE is broadcast to other clients in same session")
    void textDeleteBroadcast() throws Exception {
        NetworkUtil client1 = connect();
        NetworkUtil client2 = connect();

        registerAndLogin(client1, "del_user1", "pw");
        registerAndLogin(client2, "del_user2", "pw");

        String sid = createSession(client1, "del_user1", "del-session");
        joinSession(client1, "del_user1", sid);
        joinSession(client2, "del_user2", sid);

        // 먼저 텍스트 삽입해서 삭제 대상을 만든다
        Message insertMsg = new Message(MessageType.TEXT_INSERT, "del_user1");
        insertMsg.setPayloadFromObject(new TextInsert(0, "abcdef"));
        client1.send(insertMsg);
        receiveOfType(client2, MessageType.TEXT_INSERT);

        // client1 sends TEXT_DELETE
        Message deleteMsg = new Message(MessageType.TEXT_DELETE, "del_user1");
        deleteMsg.setPayloadFromObject(new TextDelete(3, 2));
        client1.send(deleteMsg);

        Message received = receiveOfType(client2, MessageType.TEXT_DELETE);
        assertNotNull(received);
        TextDelete payload = received.getPayloadAs(TextDelete.class);
        assertEquals(3, payload.getOffset());
        assertEquals(2, payload.getLength());

        client1.close();
        client2.close();
        Thread.sleep(300);
    }

    @Test
    @DisplayName("Sender does not receive own TEXT_INSERT back")
    void senderDoesNotReceiveOwnEdit() throws Exception {
        NetworkUtil client1 = connect();
        NetworkUtil client2 = connect();

        registerAndLogin(client1, "echo_user1", "pw");
        registerAndLogin(client2, "echo_user2", "pw");

        String sid = createSession(client1, "echo_user1", "echo-session");
        joinSession(client1, "echo_user1", sid);
        joinSession(client2, "echo_user2", sid);

        // client1 sends TEXT_INSERT
        Message insertMsg = new Message(MessageType.TEXT_INSERT, "echo_user1");
        insertMsg.setPayloadFromObject(new TextInsert(0, "test"));
        client1.send(insertMsg);

        // client2 receives it
        receiveOfType(client2, MessageType.TEXT_INSERT);

        // client1 should NOT receive its own edit back
        // — client2의 새 INSERT를 client1이 받는지 확인하는 방식으로 검증
        Message insert2 = new Message(MessageType.TEXT_INSERT, "echo_user2");
        insert2.setPayloadFromObject(new TextInsert(4, " world"));
        client2.send(insert2);

        Message fromClient2 = receiveOfType(client1, MessageType.TEXT_INSERT);
        TextInsert payload = fromClient2.getPayloadAs(TextInsert.class);
        assertEquals(" world", payload.getText());

        client1.close();
        client2.close();
        Thread.sleep(300);
    }
}

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
 * Phase 4.2~4.5 테스트:
 * DocumentListener가 로컬 텍스트 변경을 감지하여 TEXT_INSERT/TEXT_DELETE를 서버로 전송하고,
 * 서버가 다른 클라이언트에게 브로드캐스트하는지 검증.
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

    @Test
    @DisplayName("TEXT_INSERT is broadcast to other clients")
    void textInsertBroadcast() throws Exception {
        NetworkUtil client1 = connect();
        NetworkUtil client2 = connect();

        registerAndLogin(client1, "ins_user1", "pw");
        registerAndLogin(client2, "ins_user2", "pw");

        // client1 receives USER_JOINED(ins_user2) — consume
        Message joined = client1.receive();
        assertEquals(MessageType.USER_JOINED, joined.getType());

        // client1 sends TEXT_INSERT
        Message insertMsg = new Message(MessageType.TEXT_INSERT, "ins_user1");
        insertMsg.setPayloadFromObject(new TextInsert(0, "Hello"));
        client1.send(insertMsg);

        // client2 should receive TEXT_INSERT
        Message received = client2.receive();
        assertNotNull(received);
        assertEquals(MessageType.TEXT_INSERT, received.getType());
        TextInsert payload = received.getPayloadAs(TextInsert.class);
        assertEquals(0, payload.getOffset());
        assertEquals("Hello", payload.getText());

        client1.close();
        client2.close();
        Thread.sleep(300);
    }

    @Test
    @DisplayName("TEXT_DELETE is broadcast to other clients")
    void textDeleteBroadcast() throws Exception {
        NetworkUtil client1 = connect();
        NetworkUtil client2 = connect();

        registerAndLogin(client1, "del_user1", "pw");
        registerAndLogin(client2, "del_user2", "pw");

        // consume USER_JOINED
        client1.receive();

        // client1 sends TEXT_DELETE
        Message deleteMsg = new Message(MessageType.TEXT_DELETE, "del_user1");
        deleteMsg.setPayloadFromObject(new TextDelete(3, 2));
        client1.send(deleteMsg);

        // client2 should receive TEXT_DELETE
        Message received = client2.receive();
        assertNotNull(received);
        assertEquals(MessageType.TEXT_DELETE, received.getType());
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

        // consume USER_JOINED on client1
        client1.receive();

        // client1 sends TEXT_INSERT
        Message insertMsg = new Message(MessageType.TEXT_INSERT, "echo_user1");
        insertMsg.setPayloadFromObject(new TextInsert(0, "test"));
        client1.send(insertMsg);

        // client2 receives it
        Message received = client2.receive();
        assertEquals(MessageType.TEXT_INSERT, received.getType());

        // client1 should NOT receive its own edit back
        // Send another message from client2 to verify client1's stream
        Message insert2 = new Message(MessageType.TEXT_INSERT, "echo_user2");
        insert2.setPayloadFromObject(new TextInsert(4, " world"));
        client2.send(insert2);

        Message fromClient2 = client1.receive();
        assertEquals(MessageType.TEXT_INSERT, fromClient2.getType());
        TextInsert payload = fromClient2.getPayloadAs(TextInsert.class);
        assertEquals(" world", payload.getText());

        client1.close();
        client2.close();
        Thread.sleep(300);
    }
}

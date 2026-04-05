package com.editor.client;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.NetworkUtil;
import com.editor.common.payload.*;
import com.editor.server.ServerMain;
import org.junit.jupiter.api.*;

import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3.4 integration test:
 * Verifies login → online user list → USER_JOINED/USER_LEFT flow at network level.
 */
class MainFrameIntegrationTest {

    private static ServerMain server;
    private static Thread serverThread;
    private static final int PORT = 9200;

    @BeforeAll
    static void startServer() throws Exception {
        server = new ServerMain(PORT);
        serverThread = new Thread(() -> server.start(), "test-server");
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

    private void register(NetworkUtil net, String userId, String pw) throws Exception {
        Message msg = new Message(MessageType.REGISTER_REQUEST, userId);
        msg.setPayloadFromObject(new RegisterRequest(userId, pw));
        net.send(msg);
        Message resp = net.receive();
        RegisterResponse r = resp.getPayloadAs(RegisterResponse.class);
        assertTrue(r.isSuccess(), "Register failed: " + r.getMessage());
    }

    private LoginResponse login(NetworkUtil net, String userId, String pw) throws Exception {
        Message msg = new Message(MessageType.LOGIN_REQUEST, userId);
        msg.setPayloadFromObject(new LoginRequest(userId, pw));
        net.send(msg);
        Message resp = net.receive();
        return resp.getPayloadAs(LoginResponse.class);
    }

    @Test
    @DisplayName("Login response includes online user list")
    void loginReturnsOnlineUsers() throws Exception {
        NetworkUtil client1 = connect();
        register(client1, "alice", "pw1");
        LoginResponse resp1 = login(client1, "alice", "pw1");

        assertTrue(resp1.isSuccess());
        List<String> users = resp1.getOnlineUsers();
        assertNotNull(users);
        assertTrue(users.contains("alice"), "Online list should include self");

        client1.close();
        Thread.sleep(300);
    }

    @Test
    @DisplayName("Second login gets both users in online list + first user gets USER_JOINED")
    void secondLoginTriggersUserJoined() throws Exception {
        NetworkUtil client1 = connect();
        NetworkUtil client2 = connect();

        register(client1, "bob", "pw1");
        login(client1, "bob", "pw1");

        register(client2, "carol", "pw2");
        LoginResponse resp2 = login(client2, "carol", "pw2");

        // carol's online list should include both
        assertTrue(resp2.isSuccess());
        assertTrue(resp2.getOnlineUsers().contains("bob"));
        assertTrue(resp2.getOnlineUsers().contains("carol"));

        // bob should receive USER_JOINED for carol
        Message joinMsg = client1.receive();
        assertNotNull(joinMsg);
        assertEquals(MessageType.USER_JOINED, joinMsg.getType());
        UserEvent event = joinMsg.getPayloadAs(UserEvent.class);
        assertEquals("carol", event.getUserId());

        client1.close();
        client2.close();
        Thread.sleep(300);
    }

    @Test
    @DisplayName("User disconnect triggers USER_LEFT to remaining users")
    void disconnectTriggersUserLeft() throws Exception {
        NetworkUtil client1 = connect();
        NetworkUtil client2 = connect();

        register(client1, "dave", "pw1");
        login(client1, "dave", "pw1");

        register(client2, "eve", "pw2");
        login(client2, "eve", "pw2");

        // dave receives USER_JOINED(eve) — consume it
        Message joined = client1.receive();
        assertEquals(MessageType.USER_JOINED, joined.getType());

        // eve disconnects
        client2.close();
        Thread.sleep(500);

        // dave should receive USER_LEFT(eve)
        Message leftMsg = client1.receive();
        assertNotNull(leftMsg, "Should receive USER_LEFT");
        assertEquals(MessageType.USER_LEFT, leftMsg.getType());
        UserEvent event = leftMsg.getPayloadAs(UserEvent.class);
        assertEquals("eve", event.getUserId());

        client1.close();
        Thread.sleep(300);
    }
}

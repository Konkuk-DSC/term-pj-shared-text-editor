package com.editor.client;

import com.editor.common.Message;
import com.editor.common.NetworkUtil;

import java.io.IOException;
import java.net.Socket;

public class ClientMain {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 9000;

    private NetworkUtil networkUtil;
    private MessageReceiver messageReceiver;
    private Thread receiverThread;
    private volatile boolean running = false;

    /**
     * 서버에 연결한다.
     * @param host 서버 IP/호스트명
     * @param port 서버 포트
     * @return 연결 성공 시 true
     */
    public boolean connect(String host, int port) {
        try {
            Socket socket = new Socket(host, port);
            networkUtil = new NetworkUtil(socket);
            running = true;
            System.out.println("Connected to server: " + host + ":" + port);
            return true;
        } catch (IOException e) {
            System.err.println("Failed to connect: " + e.getMessage());
            return false;
        }
    }

    /**
     * 메시지 수신 스레드를 시작한다.
     * @param listener 수신 메시지를 처리할 콜백
     */
    public void startReceiver(MessageListener listener) {
        messageReceiver = new MessageReceiver(networkUtil, listener);
        receiverThread = new Thread(messageReceiver, "msg-receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    /**
     * 서버에 메시지를 전송한다.
     * @return 전송 성공 시 true
     */
    public boolean send(Message msg) {
        if (networkUtil == null) return false;
        return networkUtil.send(msg);
    }

    /**
     * 서버 연결을 종료한다.
     */
    public void disconnect() {
        running = false;
        if (messageReceiver != null) {
            messageReceiver.stop();
        }
        if (networkUtil != null) {
            networkUtil.close();
            networkUtil = null;
        }
        System.out.println("Disconnected from server.");
    }

    public NetworkUtil getNetworkUtil() {
        return networkUtil;
    }

    public boolean isRunning() {
        return running;
    }

    public static void main(String[] args) {
        System.out.println("=== Shared Text Editor Client ===");

        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;

        // 커맨드라인 인자: host port
        if (args.length >= 1) {
            host = args[0];
        }
        if (args.length >= 2) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number, using default: " + DEFAULT_PORT);
            }
        }

        ClientMain client = new ClientMain();

        if (!client.connect(host, port)) {
            javax.swing.JOptionPane.showMessageDialog(null,
                    "Cannot connect to server.\n(" + host + ":" + port + ")",
                    "Connection Failed", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Ctrl+C 종료 시 정리
        Runtime.getRuntime().addShutdownHook(new Thread(client::disconnect));

        // Phase 3.3 — 로그인 UI 시작
        LoginFrame loginFrame = new LoginFrame(client);

        // 메시지 수신 스레드 시작 — LoginFrame에 응답 위임
        DefaultMessageListener listener = new DefaultMessageListener();
        listener.setLoginFrame(loginFrame);
        client.startReceiver(listener);

        javax.swing.SwingUtilities.invokeLater(() -> loginFrame.setVisible(true));
    }
}

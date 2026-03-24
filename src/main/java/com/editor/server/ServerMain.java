package com.editor.server;

import com.editor.common.Message;
import com.editor.common.MessageType;
import com.editor.common.NetworkUtil;
import com.editor.common.payload.UserEvent;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerMain {

    private static final int DEFAULT_PORT = 9000;
    private static final int THREAD_POOL_SIZE = 10;

    private final int port;
    private final ExecutorService threadPool;
    private ServerSocket serverSocket;

    // 접속 중인 클라이언트 관리 (userId → ClientHandler)
    private final ConcurrentHashMap<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();

    // 사용자 계정 저장소
    private final AccountStore accountStore = new AccountStore();

    public ServerMain(int port) {
        this.port = port;
        this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    public AccountStore getAccountStore() {
        return accountStore;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("서버 시작됨 — 포트: " + port);
            System.out.println("클라이언트 접속 대기 중...");

            while (!serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("클라이언트 접속: " + clientSocket.getRemoteSocketAddress());

                NetworkUtil networkUtil = new NetworkUtil(clientSocket);
                ClientHandler handler = new ClientHandler(networkUtil, this);
                threadPool.execute(handler);
            }
        } catch (IOException e) {
            if (serverSocket != null && !serverSocket.isClosed()) {
                System.err.println("서버 오류: " + e.getMessage());
            }
        }
    }

    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            // 모든 클라이언트 연결 종료
            for (ClientHandler handler : connectedClients.values()) {
                handler.getNetworkUtil().close();
            }
            connectedClients.clear();
            threadPool.shutdown();
            System.out.println("서버 종료됨");
        } catch (IOException e) {
            System.err.println("서버 종료 중 오류: " + e.getMessage());
        }
    }

    /** 접속자 맵에 클라이언트 추가 */
    public void addClient(String userId, ClientHandler handler) {
        connectedClients.put(userId, handler);
        System.out.println("[접속] " + userId + " (현재 " + connectedClients.size() + "명)");
    }

    /** 접속자 맵에서 클라이언트 제거 */
    public void removeClient(String userId) {
        connectedClients.remove(userId);
        System.out.println("[해제] " + userId + " (현재 " + connectedClients.size() + "명)");
    }

    /** 현재 접속자 목록 반환 */
    public List<String> getOnlineUsers() {
        return new ArrayList<>(connectedClients.keySet());
    }

    /** 특정 사용자에게 메시지 전송 (실패 시 해당 클라이언트 해제) */
    public void sendTo(String userId, Message message) {
        ClientHandler handler = connectedClients.get(userId);
        if (handler != null) {
            if (!handler.getNetworkUtil().send(message)) {
                System.err.println("[전송 실패] " + userId + " — 연결 해제 처리");
                handler.disconnectOnSendFailure();
            }
        }
    }

    /** 전체 클라이언트에게 브로드캐스트 (송신자 제외, 전송 실패 클라이언트 자동 해제) */
    public void broadcast(Message message, String excludeUserId) {
        List<ClientHandler> failedHandlers = new ArrayList<>();

        for (var entry : connectedClients.entrySet()) {
            if (!entry.getKey().equals(excludeUserId)) {
                if (!entry.getValue().getNetworkUtil().send(message)) {
                    failedHandlers.add(entry.getValue());
                }
            }
        }

        for (ClientHandler handler : failedHandlers) {
            System.err.println("[전송 실패] " + handler.getUserId() + " — 연결 해제 처리");
            handler.disconnectOnSendFailure();
        }
    }

    /** 전체 클라이언트에게 브로드캐스트 */
    public void broadcast(Message message) {
        broadcast(message, null);
    }

    public static void main(String[] args) {
        System.out.println("=== Shared Text Editor Server ===");

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("잘못된 포트 번호, 기본 포트 사용: " + DEFAULT_PORT);
            }
        }

        ServerMain server = new ServerMain(port);

        // Ctrl+C 등으로 종료 시 정리
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.start();
    }
}

package com.editor.server;

import com.editor.common.NetworkUtil;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerMain {

    private static final int DEFAULT_PORT = 9000;
    private static final int THREAD_POOL_SIZE = 10;

    private final int port;
    private final ExecutorService threadPool;
    private ServerSocket serverSocket;

    public ServerMain(int port) {
        this.port = port;
        this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
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
            threadPool.shutdown();
            System.out.println("서버 종료됨");
        } catch (IOException e) {
            System.err.println("서버 종료 중 오류: " + e.getMessage());
        }
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

package com.editor.common;

import java.io.*;
import java.net.Socket;

/**
 * TCP 소켓 기반 메시지 송수신 유틸리티.
 * 각 메시지는 한 줄(개행 구분)의 JSON 문자열로 전송된다.
 */
public class NetworkUtil {

    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;

    public NetworkUtil(Socket socket) throws IOException {
        this.socket = socket;
        this.reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8"));
        this.writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
    }

    /**
     * Message 객체를 JSON으로 직렬화하여 전송한다.
     */
    public synchronized void send(Message message) {
        writer.println(message.toJson());
    }

    /**
     * 한 줄의 JSON 문자열을 수신하여 Message 객체로 역직렬화한다.
     * 연결이 끊기면 null을 반환한다.
     */
    public Message receive() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            return null;
        }
        return Message.fromJson(line);
    }

    /**
     * 소켓 및 스트림을 닫는다.
     */
    public void close() {
        try { reader.close(); } catch (IOException ignored) {}
        try { writer.close(); } catch (Exception ignored) {}
        try { socket.close(); } catch (IOException ignored) {}
    }

    /**
     * 연결이 유효한지 확인한다.
     */
    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    public Socket getSocket() {
        return socket;
    }
}

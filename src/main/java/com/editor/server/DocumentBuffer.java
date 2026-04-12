package com.editor.server;

/**
 * 서버 측 텍스트 상태를 관리하는 버퍼.
 * 모든 편집 연산을 적용하여 현재 문서 내용을 유지한다.
 * synchronized로 동시 접근을 보호한다.
 */
public class DocumentBuffer {

    private final StringBuilder buffer = new StringBuilder();

    /** offset 위치에 text 삽입 */
    public synchronized boolean insert(int offset, String text) {
        if (offset < 0 || offset > buffer.length()) {
            System.err.println("[BUFFER] Invalid insert offset: " + offset
                    + " (length=" + buffer.length() + ")");
            return false;
        }
        buffer.insert(offset, text);
        return true;
    }

    /** offset 위치에서 length만큼 삭제 */
    public synchronized boolean delete(int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > buffer.length()) {
            System.err.println("[BUFFER] Invalid delete: offset=" + offset
                    + ", length=" + length + " (bufferLength=" + buffer.length() + ")");
            return false;
        }
        buffer.delete(offset, offset + length);
        return true;
    }

    /** offset~offset+length 구간을 newText로 교체 */
    public synchronized boolean update(int offset, int length, String newText) {
        if (offset < 0 || length < 0 || offset + length > buffer.length()) {
            System.err.println("[BUFFER] Invalid update: offset=" + offset
                    + ", length=" + length + " (bufferLength=" + buffer.length() + ")");
            return false;
        }
        buffer.replace(offset, offset + length, newText);
        return true;
    }

    /** 현재 전체 텍스트 반환 */
    public synchronized String getText() {
        return buffer.toString();
    }

    /** 현재 텍스트 길이 반환 */
    public synchronized int length() {
        return buffer.length();
    }
}

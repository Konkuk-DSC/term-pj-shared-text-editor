package com.editor.server;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자 계정 저장소.
 * ID/PW를 메모리에 보관하며, 회원가입·로그인 검증을 담당한다.
 */
public class AccountStore {

    // userId → password
    private final ConcurrentHashMap<String, String> accounts = new ConcurrentHashMap<>();

    /**
     * 회원가입 처리.
     * @return 성공 시 null, 실패 시 사유 문자열
     */
    public String register(String userId, String password) {
        if (userId == null || userId.isBlank()) {
            return "Please enter an ID.";
        }
        if (password == null || password.isBlank()) {
            return "Please enter a password.";
        }
        if (accounts.containsKey(userId)) {
            return "ID already exists.";
        }
        accounts.put(userId, password);
        return null; // 성공
    }

    /**
     * 로그인 검증.
     * @return 성공 시 null, 실패 시 사유 문자열
     */
    public String login(String userId, String password) {
        if (userId == null || userId.isBlank()) {
            return "Please enter an ID.";
        }
        if (!accounts.containsKey(userId)) {
            return "ID does not exist.";
        }
        if (!accounts.get(userId).equals(password)) {
            return "Incorrect password.";
        }
        return null; // 성공
    }

    /** 계정 존재 여부 확인 */
    public boolean exists(String userId) {
        return accounts.containsKey(userId);
    }
}

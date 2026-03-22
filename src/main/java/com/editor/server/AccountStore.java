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
            return "아이디를 입력해주세요.";
        }
        if (password == null || password.isBlank()) {
            return "비밀번호를 입력해주세요.";
        }
        if (accounts.containsKey(userId)) {
            return "이미 존재하는 아이디입니다.";
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
            return "아이디를 입력해주세요.";
        }
        if (!accounts.containsKey(userId)) {
            return "존재하지 않는 아이디입니다.";
        }
        if (!accounts.get(userId).equals(password)) {
            return "비밀번호가 일치하지 않습니다.";
        }
        return null; // 성공
    }

    /** 계정 존재 여부 확인 */
    public boolean exists(String userId) {
        return accounts.containsKey(userId);
    }
}

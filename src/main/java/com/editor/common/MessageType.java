package com.editor.common;

public enum MessageType {
    // 인증
    LOGIN_REQUEST,
    LOGIN_RESPONSE,
    REGISTER_REQUEST,
    REGISTER_RESPONSE,
    LOGOUT,

    // 접속 상태
    USER_JOINED,
    USER_LEFT,
    USER_LIST,

    // 텍스트 편집
    TEXT_INSERT,
    TEXT_DELETE,
    TEXT_UPDATE,

    // 세션 관리
    SESSION_CREATE,
    SESSION_CREATE_RESPONSE,
    SESSION_LIST_REQUEST,
    SESSION_LIST_RESPONSE,
    SESSION_JOIN,
    SESSION_JOIN_RESPONSE,

    // 동시성 제어
    LOCK_REQUEST,
    LOCK_REPLY,
    LOCK_RELEASE,

    // 실시간 편집 과정
    REALTIME_EDIT,
    CURSOR_MOVE,
    SELECTION
}

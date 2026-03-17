package com.editor.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class Message {
    private static final Gson gson = new GsonBuilder().create();

    private MessageType type;
    private String sender;
    private long timestamp;
    private JsonObject payload;

    public Message() {
        this.timestamp = System.currentTimeMillis();
    }

    public Message(MessageType type, String sender) {
        this.type = type;
        this.sender = sender;
        this.timestamp = System.currentTimeMillis();
    }

    public Message(MessageType type, String sender, JsonObject payload) {
        this.type = type;
        this.sender = sender;
        this.timestamp = System.currentTimeMillis();
        this.payload = payload;
    }

    // 직렬화: Message → JSON 문자열
    public String toJson() {
        return gson.toJson(this);
    }

    // 역직렬화: JSON 문자열 → Message
    public static Message fromJson(String json) {
        return gson.fromJson(json, Message.class);
    }

    // Payload를 특정 클래스로 변환
    public <T> T getPayloadAs(Class<T> clazz) {
        return gson.fromJson(payload, clazz);
    }

    // 객체를 JsonObject로 변환하여 payload에 설정
    public void setPayloadFromObject(Object obj) {
        this.payload = JsonParser.parseString(gson.toJson(obj)).getAsJsonObject();
    }

    // Getters & Setters
    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public JsonObject getPayload() { return payload; }
    public void setPayload(JsonObject payload) { this.payload = payload; }
}

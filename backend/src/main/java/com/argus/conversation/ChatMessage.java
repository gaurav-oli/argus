package com.argus.conversation;

/**
 * One turn in an Ask-AI conversation (Story 7.1). {@code role} is {@code "user"} or
 * {@code "assistant"}; the server is stateless — the client sends the full prior history each turn
 * and nothing is persisted (FR-30 "session persists until the panel is dismissed" is a client-side
 * session).
 */
public record ChatMessage(String role, String content) {
}

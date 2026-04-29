package com.aipmo.agent.service;

import com.aipmo.agent.model.ChatMessage;
import com.aipmo.agent.model.ChatSession;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatSessionStore {

    public static final String DEFAULT_TITLE = "New chat";

    private final Map<String, ChatSession> sessionStore = new ConcurrentHashMap<>();
    private final Map<String, List<ChatMessage>> chatStore = new ConcurrentHashMap<>();

    public ChatSession createSession() {
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        ChatSession s =
                ChatSession.builder()
                        .sessionId(id)
                        .title(DEFAULT_TITLE)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
        sessionStore.put(id, s);
        chatStore.put(id, Collections.synchronizedList(new ArrayList<>()));
        return s;
    }

    public boolean sessionExists(String sessionId) {
        return sessionId != null && sessionStore.containsKey(sessionId.trim());
    }

    public ChatSession getSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessionStore.get(sessionId.trim());
    }

    /** Newest activity first. */
    public List<ChatSession> listSessionsNewestFirst() {
        List<ChatSession> list = new ArrayList<>(sessionStore.values());
        list.sort(Comparator.comparing(ChatSession::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    /** Ordered copy of messages for the session (empty if unknown session). */
    public List<ChatMessage> getMessagesOrdered(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        List<ChatMessage> list = chatStore.get(sessionId.trim());
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    public void touchSession(String sessionId) {
        ChatSession s = getSession(sessionId);
        if (s != null) {
            s.setUpdatedAt(LocalDateTime.now());
        }
    }

    /**
     * Sets title from the first substantive user message when the session still has the default title.
     */
    public void maybeSetTitleFromFirstMessage(String sessionId, String shortenedTitle) {
        ChatSession s = getSession(sessionId);
        if (s == null || shortenedTitle == null || shortenedTitle.isBlank()) {
            return;
        }
        if (DEFAULT_TITLE.equals(s.getTitle()) || s.getTitle() == null || s.getTitle().isBlank()) {
            s.setTitle(shortenedTitle.trim());
            s.setUpdatedAt(LocalDateTime.now());
        }
    }

    public void appendMessage(ChatMessage message) {
        if (message == null || message.getSessionId() == null) {
            return;
        }
        String sid = message.getSessionId().trim();
        List<ChatMessage> list = chatStore.get(sid);
        if (list == null) {
            return;
        }
        message.setSessionId(sid);
        synchronized (list) {
            list.add(message);
        }
        touchSession(sid);
    }
}

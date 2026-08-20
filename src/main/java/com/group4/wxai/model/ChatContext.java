package com.group4.wxai.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class ChatContext {

    private static final int MAX_HISTORY = 20;

    private static final Map<String, List<LlmMessage>> contextMap = new ConcurrentHashMap<>();
    private static final Map<String, String> lastLocationMap = new ConcurrentHashMap<>();
    private static final Map<String, String> pendingWeatherDateMap = new ConcurrentHashMap<>();

    public static List<LlmMessage> getHistory(String userId) {
        List<LlmMessage> history = contextMap.computeIfAbsent(userId, k -> new ArrayList<>());
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    public static void addUserMessage(String userId, String content) {
        addMessage(userId, LlmMessage.user(content));
    }

    public static void addAssistantMessage(String userId, String content) {
        addMessage(userId, LlmMessage.assistant(content));
    }

    public static void rememberLocation(String userId, String location) {
        if (userId != null && !userId.isBlank() && location != null && !location.isBlank()) {
            lastLocationMap.put(userId, location.trim());
        }
    }

    public static Optional<String> getLastLocation(String userId) {
        return Optional.ofNullable(lastLocationMap.get(userId));
    }

    public static void rememberPendingWeather(String userId, String date) {
        if (userId != null && !userId.isBlank()) {
            pendingWeatherDateMap.put(userId, date == null || date.isBlank() ? "TODAY" : date.trim());
        }
    }

    public static Optional<String> getPendingWeatherDate(String userId) {
        return Optional.ofNullable(pendingWeatherDateMap.get(userId));
    }

    public static void clearPendingWeather(String userId) {
        pendingWeatherDateMap.remove(userId);
    }

    public static void clear(String userId) {
        contextMap.remove(userId);
        lastLocationMap.remove(userId);
        pendingWeatherDateMap.remove(userId);
    }

    private static void addMessage(String userId, LlmMessage message) {
        List<LlmMessage> history = contextMap.computeIfAbsent(userId, k -> new ArrayList<>());
        synchronized (history) {
            history.add(message);
            trimHistory(history);
        }
    }

    private static void trimHistory(List<LlmMessage> history) {
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }
}

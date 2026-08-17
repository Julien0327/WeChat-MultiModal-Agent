package com.group4.wxai.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class ChatContext {

    private static final int MAX_HISTORY = 20;

    private static final Map<String, List<LlmMessage>> contextMap = new ConcurrentHashMap<>();

    public static List<LlmMessage> getHistory(String userId) {
        return contextMap.computeIfAbsent(userId, k -> new ArrayList<>());
    }

    public static void addUserMessage(String userId, String content) {
        List<LlmMessage> history = getHistory(userId);
        history.add(LlmMessage.user(content));
        trimHistory(history);
    }

    public static void addAssistantMessage(String userId, String content) {
        List<LlmMessage> history = getHistory(userId);
        history.add(LlmMessage.assistant(content));
        trimHistory(history);
    }

    public static void clear(String userId) {
        contextMap.remove(userId);
    }

    private static void trimHistory(List<LlmMessage> history) {
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }
}
